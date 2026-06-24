(ns metabase.driver.starrocks
  "StarRocks driver for Metabase.
   
   Extends the MySQL driver with StarRocks-specific functionality:
   - Fixes the SHOW GRANTS FOR CURRENT_USER incompatibility
   - Adds proper catalog support for multi-catalog environments
   - Handles StarRocks-specific metadata queries
   
   Based on Metabase's Starburst driver patterns for catalog handling."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.log :as log])
  (:import
   (java.io ByteArrayInputStream FileOutputStream)
   (java.security KeyStore)
   (java.security.cert Certificate CertificateFactory)
   (java.sql Connection ResultSet)
   (java.util Base64)))

(set! *warn-on-reflection* true)

;; Register StarRocks as a driver that extends sql-jdbc (not mysql directly to avoid inheriting SHOW GRANTS behavior)
(driver/register! :starrocks :parent :sql-jdbc)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Driver Features                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Declare what features StarRocks supports
(doseq [[feature supported?] {:set-timezone                    true
                              :basic-aggregations              true
                              :standard-deviation-aggregations true
                              :expressions                     true
                              :native-parameters               true
                              :expression-aggregations         true
                              :binning                         true
                              :foreign-keys                    false
                              :nested-field-columns            false
                              :connection/multiple-databases   true
                              :metadata/key-constraints        false
                              :now                             true
                              :datetime-diff                   true
                              :temporal-extract                true
                              :date-arithmetics                true
                              :advanced-math-expressions       true}]
  (defmethod driver/database-supports? [:starrocks feature] [_ _ _] supported?))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Connection Details                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- additional-options->map
  "Parse `additional-options` string (`key=value&...`) into a map with string keys."
  [additional-options]
  (when (and additional-options (not (str/blank? additional-options)))
    (into {}
          (for [pair (str/split additional-options #"&")
                :let [pair (str/trim pair)]
                :when (not (str/blank? pair))]
            (let [[k v] (str/split pair #"=" 2)]
              [(str/trim k) (or v "")])))))

(def ^:private ssl-jdbc-property-keys
  "JDBC properties that only apply when the user enables SSL in the connection form."
  #{:useSSL :sslMode :requireSSL :verifyServerCertificate :trustServerCertificate
    :trustCertificateKeyStoreUrl :trustCertificateKeyStoreType :trustCertificateKeyStorePassword
    :clientCertificateKeyStoreUrl :clientCertificateKeyStoreType :clientCertificateKeyStorePassword
    :enabledSslProtocolSuites :enabledSslCipherSuites})

(defn- ssl-enabled?
  "SSL is opt-in only: controlled by the Metabase \"Use a secure connection (SSL)\" checkbox."
  [{:keys [ssl]}]
  (boolean ssl))

(defn- additional-opt-keyword
  "Keywordize an additional-options key, matching SSL JDBC properties case-insensitively."
  [k]
  (or (some (fn [ssl-k]
              (when (= (str/lower-case (name ssl-k))
                       (str/lower-case k))
                ssl-k))
            ssl-jdbc-property-keys)
      (keyword k)))

(defn- additional-options->keyword-map
  "Parse `additional-options` into a map with canonical keyword keys."
  [additional-options]
  (when-let [opts (additional-options->map additional-options)]
    (into {} (map (fn [[k v]] [(additional-opt-keyword k) v]) opts))))

(defn- without-ssl-jdbc-keys
  "Remove SSL-related keys so additional-options cannot enable SSL when the checkbox is off."
  [opts-map]
  (into {} (remove (fn [[k _]] (contains? ssl-jdbc-property-keys k)) opts-map)))

(defn- valid-pem?
  "True when `pem-content` contains at least one parseable PEM X.509 certificate."
  [pem-content]
  (boolean
   (and (not (str/blank? pem-content))
        (try
          (pos? (alength (parse-pem-certificates (str pem-content))))
          (catch Exception _ false)))))

(defn- parse-pem-certificates
  "Parse one or more PEM X.509 certificates from `pem-content`."
  ^"[Ljava.security.cert.Certificate;" [^String pem-content]
  (let [^CertificateFactory cf (CertificateFactory/getInstance "X.509")
        blocks                (re-seq #"-----BEGIN CERTIFICATE-----\s*([\s\S]*?)\s*-----END CERTIFICATE-----"
                                    pem-content)]
    (into-array Certificate
                (mapcat (fn [[_b64-body b64-body]]
                          (let [^String cleaned (str/replace b64-body #"\s" "")
                                bytes           (.decode (Base64/getDecoder) cleaned)]
                            (.generateCertificates cf (ByteArrayInputStream. bytes))))
                        blocks))))

(defn- jks-truststore-file-url
  "Build a JKS truststore from PEM CA/certificate chain (works on all JDKs; avoids PEM KeyStore type)."
  [^String pem-content]
  (let [certs     (parse-pem-certificates pem-content)
        ^KeyStore ks (KeyStore/getInstance (KeyStore/getDefaultType))]
    (when (zero? (alength certs))
      (throw (IllegalArgumentException. "No parseable X.509 certificates in PEM content")))
    (.load ks nil nil)
    (dotimes [i (alength certs)]
      (.setCertificateEntry ks (str "ca-" i) (aget certs i)))
    (let [^java.io.File f (doto (java.io.File/createTempFile "starrocks-trust-" ".jks")
                              (.deleteOnExit))]
      (with-open [^FileOutputStream os (FileOutputStream. f)]
        (.store ks os (char-array "")))
      (.toString (.toURI f)))))

(defn- normalize-ssl-mode
  "Normalize ssl-mode from the connection form (e.g. verify-identity -> VERIFY_IDENTITY)."
  [mode]
  (when-not (str/blank? mode)
    (-> mode str/trim str/upper-case (str/replace #"-" "_"))))

(defn- ssl-mode-verifies-certificate?
  [ssl-mode]
  (boolean (and ssl-mode (re-find #"VERIFY" ssl-mode))))

(defn- default-ssl-mode
  "Default sslMode when the form field is left empty."
  [ssl-cert-pem]
  (if ssl-cert-pem "VERIFY_CA" "VERIFY_IDENTITY"))

(defn- ssl-jdbc-spec
  "Build JDBC SSL options from the ssl-mode form field and optional PEM trust material.
   Custom CA PEM is only applied for VERIFY_CA (JKS truststore). VERIFY_IDENTITY uses the JVM trust store
   and also checks that the certificate hostname matches the connection host."
  [ssl-cert-pem ssl-mode-from-form]
  (let [ssl-mode       (or (normalize-ssl-mode ssl-mode-from-form)
                           (default-ssl-mode ssl-cert-pem))
        use-custom-ca? (and (= ssl-mode "VERIFY_CA")
                            (valid-pem? ssl-cert-pem))]
    (cond-> {:useSSL "true" :sslMode ssl-mode}
      (= ssl-mode "VERIFY_IDENTITY") (assoc :verifyServerCertificate "true")
      use-custom-ca?
      (assoc :trustCertificateKeyStoreType "JKS"
             :trustCertificateKeyStoreUrl   (jks-truststore-file-url ssl-cert-pem)
             :trustCertificateKeyStorePassword ""))))

(defn- apply-ssl-spec
  "Apply SSL JDBC properties once, honoring form settings, additional-options overrides, and sslMode-dependent keys."
  [spec ssl-cert-pem form-ssl-mode addl-spec]
  (let [effective-mode (or (some-> (:sslMode addl-spec) normalize-ssl-mode)
                           (normalize-ssl-mode form-ssl-mode)
                           (default-ssl-mode ssl-cert-pem))
        derived-spec   (ssl-jdbc-spec ssl-cert-pem effective-mode)
        user-overrides (when addl-spec (select-keys addl-spec ssl-jdbc-property-keys))]
    (-> spec
        (#(reduce dissoc % ssl-jdbc-property-keys))
        (merge derived-spec)
        (merge user-overrides))))

(defn- maybe-log-ssl-hint
  "Log SSL configuration hints when verification is enabled."
  [ssl? ssl-cert ssl-mode-from-form additional-options]
  (when ssl?
    (let [addl-opts-map   (additional-options->keyword-map additional-options)
          ssl-cert?       (valid-pem? (str ssl-cert))
          form-ssl-mode   (normalize-ssl-mode ssl-mode-from-form)
          addl-ssl-mode   (some-> (:sslMode addl-opts-map) normalize-ssl-mode)
          effective-mode  (or addl-ssl-mode form-ssl-mode (default-ssl-mode (when ssl-cert? (str ssl-cert))))]
      (when (= "false" (:verifyServerCertificate addl-opts-map))
        (log/warn "StarRocks SSL: verifyServerCertificate=false disables certificate validation."))
      (when (= "REQUIRED" effective-mode)
        (log/warn "StarRocks SSL: sslMode=REQUIRED encrypts but does not verify the server certificate."))
      (when (and (not (str/blank? (str ssl-cert)))
                 (not ssl-cert?)
                 (ssl-mode-verifies-certificate? effective-mode))
        (log/warn "StarRocks SSL: ssl-cert is set but does not contain a valid PEM certificate; using JVM truststore only."))
      (when (and (ssl-mode-verifies-certificate? effective-mode)
                 (str/blank? (str ssl-cert))
                 (not (contains? addl-opts-map :trustCertificateKeyStoreUrl)))
        (log/infof "StarRocks SSL: sslMode=%s with JVM truststore. Paste the server CA PEM chain into 'Server SSL certificate chain' if you see PKIX / certificate_unknown errors."
                   effective-mode)))))

(defmethod sql-jdbc.conn/connection-details->spec :starrocks
  [_ {:keys [host port catalog dbname user password ssl ssl-mode ssl-cert additional-options]
      :or   {host "localhost"
             port 9030
             catalog "default_catalog"}}]
  (let [;; Build the database name as catalog.database if both are provided
        ;; For external catalogs, StarRocks requires catalog.database format
        ;; If only catalog is provided, we use catalog.information_schema as a valid connection target
        ;; This allows us to connect and then query SHOW DATABASES to list all databases
        catalog-trimmed (when catalog (str/trim catalog))
        dbname-trimmed (when dbname (str/trim dbname))

        db-name (cond
                  ;; Both catalog and database provided
                  (and (not (str/blank? catalog-trimmed))
                       (not (str/blank? dbname-trimmed)))
                  (str catalog-trimmed "." dbname-trimmed)

                  ;; Only catalog provided - use information_schema as connection target
                  ;; This is a system database that always exists in every catalog
                  (not (str/blank? catalog-trimmed))
                  (str catalog-trimmed ".information_schema")

                  ;; Fallback to default_catalog
                  :else
                  "default_catalog.information_schema")

        ssl?         (ssl-enabled? {:ssl ssl})
        ssl-cert-pem (when (and ssl? ssl-cert (valid-pem? (str ssl-cert)))
                       (str/trim (str ssl-cert)))

        _            (maybe-log-ssl-hint ssl? ssl-cert ssl-mode additional-options)

        ;; MySQL Connector/J — SSL with server certificate verification (VERIFY_CA / VERIFY_IDENTITY)
        base-spec (cond-> {:classname   "com.mysql.cj.jdbc.Driver"
                           :subprotocol "mysql"
                           :subname     (str "//" host ":" port "/" db-name)
                           :user        user
                           :password    password
                           ;; StarRocks-specific settings
                           :tinyInt1isBit "false"
                           :yearIsDateType "false"
                           :serverTimezone "UTC"
                           :allowPublicKeyRetrieval "true"
                           :zeroDateTimeBehavior "convertToNull"}
                     (not ssl?) (assoc :useSSL "false"))

        addl-spec (when-let [opts (additional-options->keyword-map additional-options)]
                    (if ssl?
                      opts
                      (without-ssl-jdbc-keys opts)))]
    (cond-> base-spec
      addl-spec (merge addl-spec)
      ssl?      (#(apply-ssl-spec % ssl-cert-pem ssl-mode addl-spec)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Type Mappings                                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private starrocks-type->base-type
  "Map of StarRocks types to Metabase base types."
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"(?i)^boolean$"                  :type/Boolean]
    [#"(?i)^tinyint$"                  :type/Integer]
    [#"(?i)^smallint$"                 :type/Integer]
    [#"(?i)^int$"                      :type/Integer]
    [#"(?i)^bigint$"                   :type/BigInteger]
    [#"(?i)^largeint$"                 :type/BigInteger]
    [#"(?i)^float$"                    :type/Float]
    [#"(?i)^double$"                   :type/Float]
    [#"(?i)^decimal.*"                 :type/Decimal]
    [#"(?i)^varchar.*"                 :type/Text]
    [#"(?i)^char.*"                    :type/Text]
    [#"(?i)^string$"                   :type/Text]
    [#"(?i)^text$"                     :type/Text]
    [#"(?i)^json$"                     :type/JSON]
    [#"(?i)^date$"                     :type/Date]
    [#"(?i)^datetime$"                 :type/DateTime]
    [#"(?i)^timestamp$"                :type/DateTime]
    [#"(?i)^array.*"                   :type/Array]
    [#"(?i)^map.*"                     :type/Dictionary]
    [#"(?i)^struct.*"                  :type/*]
    [#"(?i)^bitmap$"                   :type/*]
    [#"(?i)^hll$"                      :type/*]
    [#"(?i)^percentile$"               :type/*]
    [#".*"                             :type/*]]))

(defmethod sql-jdbc.sync/database-type->base-type :starrocks
  [_ field-type]
  (starrocks-type->base-type field-type))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Metadata / Sync                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Schemas to exclude from sync
(def ^:private excluded-schemas
  #{"information_schema" "_statistics_" "INFORMATION_SCHEMA"})

;; CRITICAL: Override current-user-table-privileges to avoid SHOW GRANTS FOR CURRENT_USER
;; StarRocks doesn't support this MySQL syntax
(defmethod sql-jdbc.sync/current-user-table-privileges :starrocks
  [_driver _conn-spec & _options]
  ;; Return nil to skip privilege checking - StarRocks handles permissions differently
  nil)

(defn- describe-catalog-sql
  "The SHOW DATABASES statement that will list all schemas/databases for the current catalog."
  [_driver]
  "SHOW DATABASES")

(defn- describe-schema-sql
  "The SHOW TABLES statement that will list all tables for the given schema/database."
  [_driver schema]
  (str "SHOW TABLES FROM `" schema "`"))

(defn- describe-table-sql
  "The DESCRIBE statement that will list information about the given table."
  [_driver schema table]
  (str "DESCRIBE `" schema "`.`" table "`"))

(defn- get-schemas
  "Gets all schemas/databases in the current catalog."
  [driver ^Connection conn]
  (with-open [stmt (.createStatement conn)]
    (let [sql (describe-catalog-sql driver)
          rs  (.executeQuery stmt sql)]
      (loop [schemas []]
        (if (.next ^ResultSet rs)
          (let [schema-name (.getString ^ResultSet rs 1)]
            (recur (if (contains? excluded-schemas schema-name)
                     schemas
                     (conj schemas schema-name))))
          schemas)))))

(defn- get-tables-in-schema
  "Gets all tables in the given schema/database."
  [driver ^Connection conn schema]
  (try
    (with-open [stmt (.createStatement conn)]
      (let [sql (describe-schema-sql driver schema)
            rs  (.executeQuery stmt sql)]
        (loop [tables []]
          (if (.next ^ResultSet rs)
            (recur (conj tables {:name   (.getString ^ResultSet rs 1)
                                 :schema schema}))
            tables))))
    (catch Exception e
      (log/warnf "Could not get tables from schema %s: %s" schema (.getMessage e))
      [])))

(defmethod driver/describe-database :starrocks
  [driver database]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (let [schemas (get-schemas driver conn)
           tables  (into #{}
                         (mapcat (fn [schema]
                                   (get-tables-in-schema driver conn schema)))
                         schemas)]
       {:tables tables}))))

(defmethod driver/describe-table :starrocks
  [driver database {schema :schema, table-name :name}]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (with-open [stmt (.createStatement conn)]
       (let [sql (describe-table-sql driver schema table-name)
             rs  (.executeQuery stmt sql)]
         {:schema schema
          :name   table-name
          :fields (loop [fields []
                         idx 0]
                    (if (.next ^ResultSet rs)
                      (let [col-name (.getString ^ResultSet rs "Field")
                            col-type (.getString ^ResultSet rs "Type")]
                        (recur (conj fields {:name              col-name
                                             :database-type     col-type
                                             :base-type         (starrocks-type->base-type col-type)
                                             :database-position idx})
                               (inc idx)))
                      (set fields)))})))))

;;; The StarRocks JDBC doesn't support foreign keys
(defmethod driver/describe-table-fks :starrocks
  [_driver _database _table]
  nil)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Query Processing                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Use MySQL-style quoting since StarRocks is MySQL-compatible
(defmethod sql.qp/quote-style :starrocks [_] :mysql)

;; Date/time handling
(defmethod sql.qp/unix-timestamp->honeysql [:starrocks :seconds]
  [_ _ expr]
  [:from_unixtime expr])

(defmethod sql.qp/unix-timestamp->honeysql [:starrocks :milliseconds]
  [_ _ expr]
  [:from_unixtime [:/ expr 1000]])

(defmethod sql.qp/current-datetime-honeysql-form :starrocks
  [_]
  :%now)

(defmethod sql.qp/date [:starrocks :default] [_ _ expr] expr)

(defmethod sql.qp/date [:starrocks :minute]
  [_ _ expr]
  [:date_trunc "minute" expr])

(defmethod sql.qp/date [:starrocks :hour]
  [_ _ expr]
  [:date_trunc "hour" expr])

(defmethod sql.qp/date [:starrocks :day]
  [_ _ expr]
  [:date_trunc "day" expr])

(defmethod sql.qp/date [:starrocks :week]
  [_ _ expr]
  [:date_trunc "week" expr])

(defmethod sql.qp/date [:starrocks :month]
  [_ _ expr]
  [:date_trunc "month" expr])

(defmethod sql.qp/date [:starrocks :quarter]
  [_ _ expr]
  [:date_trunc "quarter" expr])

(defmethod sql.qp/date [:starrocks :year]
  [_ _ expr]
  [:date_trunc "year" expr])

(defmethod sql.qp/date [:starrocks :minute-of-hour] [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:starrocks :hour-of-day]   [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:starrocks :day-of-month]  [_ _ expr] [:day expr])
(defmethod sql.qp/date [:starrocks :month-of-year] [_ _ expr] [:month expr])
(defmethod sql.qp/date [:starrocks :year-of-era]   [_ _ expr] [:year expr])
(defmethod sql.qp/date [:starrocks :day-of-week]   [_ _ expr] [:dayofweek expr])
(defmethod sql.qp/date [:starrocks :week-of-year]  [_ _ expr] [:week expr])
(defmethod sql.qp/date [:starrocks :quarter-of-year] [_ _ expr] [:quarter expr])

(defmethod sql.qp/add-interval-honeysql-form :starrocks
  [_ hsql-form amount unit]
  [:date_add hsql-form [:interval amount (keyword (name unit))]])

(defmethod sql.qp/datetime-diff [:starrocks :year]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/datetime-diff [:starrocks :month]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/datetime-diff [:starrocks :day]
  [_ unit x y]
  [:datediff y x])

(defmethod sql.qp/datetime-diff [:starrocks :hour]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/datetime-diff [:starrocks :minute]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/datetime-diff [:starrocks :second]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/cast-temporal-string [:starrocks :Coercion/ISO8601->DateTime]
  [_ _ expr]
  [:cast expr :datetime])

(defmethod sql.qp/cast-temporal-string [:starrocks :Coercion/ISO8601->Date]
  [_ _ expr]
  [:cast expr :date])

(defmethod sql.qp/cast-temporal-string [:starrocks :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_ _ expr]
  [:cast expr :datetime])

(defmethod sql.qp/cast-temporal-byte [:starrocks :Coercion/YYYYMMDDHHMMSSBytes->Temporal]
  [_ _ expr]
  [:cast expr :datetime])

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Driver Metadata                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver/display-name :starrocks [_]
  "StarRocks")

(defmethod driver/db-start-of-week :starrocks [_]
  :monday)

(defmethod driver/db-default-timezone :starrocks
  [driver database]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (try
       (with-open [stmt (.createStatement conn)]
         (let [rs (.executeQuery stmt "SELECT @@system_time_zone")]
           (when (.next ^ResultSet rs)
             (.getString ^ResultSet rs 1))))
       (catch Exception _
         "UTC")))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Connection Testing                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver/can-connect? :starrocks
  [driver details]
  (try
    (sql-jdbc.conn/with-connection-spec-for-testing-connection [spec [driver details]]
      ;; Just try a simple query to verify connection
      (jdbc/query spec ["SELECT 1"])
      true)
    (catch Exception e
      (let [msg (loop [ex ^Exception e, acc []]
                  (if ex
                    (recur (ex-cause ex) (conj acc (.getMessage ex)))
                    (str/join " -> " (remove str/blank? acc))))]
        (log/errorf "StarRocks connection failed: %s" msg))
      false)))

(defmethod driver/humanize-connection-error-message :starrocks
  [_ message]
  ;; Ensure message is a string
  (let [msg (if (string? message) message (str message))]
    (cond
      (re-find #"(?i)communications link failure" msg)
      "Unable to connect to StarRocks. Please check that the host and port are correct."
      
      (re-find #"(?i)access denied" msg)
      "Access denied. Please check your username and password."
      
      (re-find #"(?i)unknown database" msg)
      "Database not found. Please check the catalog and database names."
      
      (re-find #"(?i)unknown catalog" msg)
      "Catalog not found. Please check the catalog name."

      (re-find #"(?i)insecure transport" msg)
      (str msg " — This StarRocks instance requires SSL. Enable \"Use a secure connection (SSL)\" in Metabase.")

      (re-find #"(?i)could not load system variables" msg)
      (str msg " — JDBC driver could not read MySQL session variables from StarRocks. "
           "Ensure SSL is enabled if required, then retry.")

      (re-find #"(?i)PKIX|certificate_unknown|certpath|unable to find valid certification path" msg)
      (str msg " — Certificate verification failed. Use SSL mode VERIFY_CA and paste the FE CA PEM chain, "
           "or VERIFY_IDENTITY if the CA is in the JVM trust store. Internal hostnames often need VERIFY_CA, not VERIFY_IDENTITY.")

      (re-find #"(?i)hostname.*not match|name mismatch|No subject alternative" msg)
      (str msg " — Hostname does not match the server certificate. Use SSL mode VERIFY_CA with the CA PEM chain "
           "(does not require hostname match), or connect using the hostname on the certificate.")

      (re-find #"(?i)PEM KeyStore not available|PEM not found|Failed to create keystore" msg)
      (str msg " — Invalid or unsupported certificate material. Paste a valid PEM chain (-----BEGIN CERTIFICATE-----) "
           "and use SSL mode VERIFY_CA.")

      (re-find #"(?i)ssl|tls|handshake" msg)
      (str msg " — Ensure StarRocks FE has SSL enabled (ssl_keystore_* in fe.conf, v3.4.1+)"
           " and that \"Use a secure connection (SSL)\" is checked in Metabase.")

      :else
      msg)))