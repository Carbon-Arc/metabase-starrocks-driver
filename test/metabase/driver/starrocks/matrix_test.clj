(ns metabase.driver.starrocks.matrix-test
  "Compiles the REAL driver namespace against a stub Metabase of each supported shape.

   This is the guard that matters. A single compile-time reference to a var the host does not
   have aborts the whole namespace, so 'did it load at all' is the primary assertion -- and it
   catches references nobody thought to denylist. On unmodified `main` v50/v57 fail on
   `transform-literal-like-pattern-honeysql` and v63 fails on `describe-table-fks`. A `:require`
   of `metabase.driver.sync` fails on `nosync`, the one shape without that namespace.

   Each shape needs its own JVM: a single JVM can only load `metabase.driver` once. Each shape
   is therefore run exactly once and the result shared across the deftests below."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [metabase.driver.starrocks.test-common :as tc]))

(def ^:private tracked
  '[metabase.driver/describe-table-fks
    metabase.driver/describe-fks
    metabase.driver/describe-database*
    metabase.driver/describe-database
    metabase.driver.sql.query-processor/transform-literal-like-pattern-honeysql])

(def ^:private marker "#RESULT#")

;; Built as data and `pr-str`d into `-e`, so there is no string escaping to get wrong.
(def ^:private probe-form
  `(do
     (require 'metabase.driver.starrocks)
     (require 'metabase.driver.sql-jdbc.execute)
     (require 'stubs.fake-jdbc)
     (let [probe#       (fn [s#] (some-> (namespace s#) symbol find-ns (ns-resolve (symbol (name s#)))))
           registered#  (fn [s#] (when-let [v# (probe# s#)]
                                   (contains? (methods (var-get v#)) :starrocks)))
           call#        (fn [s# args#]
                          (if-let [v# (probe# s#)]
                            (try
                              {:ok (apply (var-get v#) args#)}
                              (catch Throwable t#
                                {:err (str (.getName (class t#)) ": " (.getMessage t#))}))
                            :absent))
           db#          {:id 1 :name "test" :details {:catalog "default_catalog"}}
           ;; Same catalog and the same canned answers; only the Schemas filter differs.
           schemas-db#  (fn [type# patterns#]
                          {:id 2
                           :name "filtered"
                           :details {:catalog                 "default_catalog"
                                     :schema-filters-type     type#
                                     :schema-filters-patterns patterns#}})
           ;; No catalog at all -- the connection spans every catalog it can see. Metabase submits a
           ;; cleared optional text field as "", not nil, so blank is the shape this actually
           ;; arrives in; `{}` only covers a connection saved before the property existed.
           all-db#      {:id 4 :name "all-catalogs" :details {}}
           blank-db#    {:id 5 :name "blank-catalog" :details {:catalog "   "}}
           all-sql#     {"SHOW CATALOGS"
                         [["Catalog"]  [{"Catalog" "default_catalog"}
                                        {"Catalog" "hive_catalog"}
                                        ;; visible but unusable -- no canned answer, so listing it
                                        ;; throws and must be survived rather than abort the sync
                                        {"Catalog" "unreachable_catalog"}]]
                         "SHOW DATABASES FROM `default_catalog`"
                         [["Database"] [{"Database" "sales_kr"} {"Database" "information_schema"}]]
                         "SHOW DATABASES FROM `hive_catalog`"
                         [["Database"] [{"Database" "sales_hive"} {"Database" "information_schema"}]]
                         "SHOW TABLES FROM `default_catalog`.`sales_kr`"
                         [["Tables"]   [{"Tables" "mv_daily_totals"}]]
                         "SHOW TABLES FROM `hive_catalog`.`sales_hive`"
                         [["Tables"]   [{"Tables" "orders"}]]
                         "DESCRIBE `hive_catalog`.`sales_hive`.`orders`"
                         [["Field" "Type"] [{"Field" "order_id" "Type" "bigint"}]]}
           ;; Returns both what sync got back AND every SQL the driver ran, because the point of
           ;; filtering in `get-schemas` is a query that is never issued -- invisible in the result.
           describe-db# (fn [dbx# sql-results#]
                          (with-bindings
                            {(resolve 'metabase.driver.sql-jdbc.execute/*sql-results*) sql-results#}
                            ((var-get (resolve 'stubs.fake-jdbc/reset-executed-sql!)))
                            (let [mmx# (if (probe# 'metabase.driver/describe-database*)
                                         'metabase.driver/describe-database*
                                         'metabase.driver/describe-database)]
                              {:call  (call# mmx# [:starrocks dbx#])
                               :sql   @(var-get (resolve 'stubs.fake-jdbc/executed-sql))
                               ;; Every WARN logged so far -- cumulative, not per call, because
                               ;; the probes in this map run in an order the child JVM chooses.
                               ;; Lets the degrade path's log contract be asserted: a WARN without
                               ;; the host functions, none with them.
                               :warns @(var-get (resolve 'metabase.util.log/warnings))})))
           ;; A honeysql identifier as the host builds it: `[::identifier type [component ...]]`.
           ;; Written out rather than made with `h2x/identifier` because no stub provides that
           ;; function -- the driver does not call it, and a stub in `test/stubs/common` would only
           ;; prove that every shape resolves the same stub. The layout is the host's public
           ;; `Identifier` schema, identical from 0.50 through 0.63.
           ident#       (fn [t# & cs#]
                          [:metabase.util.honey-sql-2/identifier t# (vec cs#)])
           to-hsql#     (fn [x#]
                          (call# 'metabase.driver.sql.query-processor/->honeysql [:starrocks x#]))
           filter-sql#  {"SHOW DATABASES"              [["Database"] [{"Database" "sales_kr"}
                                                                      {"Database" "scratch"}
                                                                      {"Database" "information_schema"}]]
                         "SHOW TABLES FROM `sales_kr`" [["Tables"]   [{"Tables" "mv_daily_totals"}]]
                         "SHOW TABLES FROM `scratch`"  [["Tables"]   [{"Tables" "temp_log"}]]}]
       (println ~marker)
       (prn
        {:present    (into #{} (filter probe#) '~tracked)
         :registered (into #{} (filter registered#) '~tracked)

         ;; Does this shape's host HAVE `metabase.driver.sync` -- loadable, not merely loaded?
         ;; Nothing in the driver `:require`s it any more, so asking `find-ns` alone would depend
         ;; on whether another probe in this map (evaluated in hash order, not source order) had
         ;; already run `host-fn` and loaded it. A missing namespace is the expected `false`; any
         ;; other failure comes back as text, so the assertion shows the cause rather than a bare
         ;; `false`.
         :host-sync?
         (try
           (require 'metabase.driver.sync)
           (boolean (probe# 'metabase.driver.sync/include-schema?))
           (catch java.io.FileNotFoundException _#
             false)
           (catch Throwable t#
             (str (.getName (class t#)) ": " (.getMessage t#))))
         :calls
         ;; Exercises the real call shapes. Nothing else in the suite reaches these: FK sync is
         ;; gated off by `:metadata/key-constraints false`, so a wrong arity would ship silently.
         {:describe-fks-trailing-map
          (call# 'metabase.driver/describe-fks
                 [:starrocks db# {:schema-names ["s"] :table-names ["t"]}])

          :describe-fks-kwargs
          (call# 'metabase.driver/describe-fks
                 [:starrocks db# :schema-names ["s"]])

          :describe-fks-no-options
          (call# 'metabase.driver/describe-fks [:starrocks db#])

          :describe-table-fks
          (call# 'metabase.driver/describe-table-fks
                 [:starrocks db# {:name "t" :schema "s"}])

          ;; Upstream v1.0.6 added a result-column metadata correction. Keep behavioural coverage
          ;; while compiling it against every Metabase shape: precision-1 TINYINT is StarRocks
          ;; BOOLEAN, while a real TINYINT (precision 4) must remain an integer.
          :column-metadata
          (call# 'metabase.driver.sql-jdbc.execute/column-metadata
                 [:starrocks
                  (reify java.sql.ResultSetMetaData
                    (getPrecision [_# i#]
                      (case i# 1 1 2 4)))])}

         ;; Behavioural coverage of `describe-database-impl`, which the matrix rewired from a
         ;; literal defmethod. The stub connection answers the driver's real SHOW statements, so
         ;; this asserts the extracted body still produces the shape sync expects -- not merely
         ;; that *something* is attached to the multimethod.
         ;; `with-bindings` (map of var->value, resolved at runtime) rather than `binding`,
         ;; which needs a literal symbol -- and a literal reference to a stub var would be
         ;; resolved when this form is compiled, before the `require` above has run.
         :describe-database
         (with-bindings
           {(resolve 'metabase.driver.sql-jdbc.execute/*sql-results*)
            {"SHOW DATABASES"            [["Database"] [{"Database" "silver"}
                                                        {"Database" "information_schema"}]]
             "SHOW TABLES FROM `silver`" [["Tables"]   [{"Tables" "orders"}
                                                        {"Tables" "customers"}]]}}
           (let [mm# (if (probe# 'metabase.driver/describe-database*)
                       'metabase.driver/describe-database*
                       'metabase.driver/describe-database)]
             (call# mm# [:starrocks db#])))

         ;; `scratch` is given a canned answer on purpose. Omitting it would prove nothing: the
         ;; driver swallows a failed `SHOW TABLES` and returns [], so the result would look
         ;; filtered either way.
         :filter-inclusion (describe-db# (schemas-db# "inclusion" "sales_*") filter-sql#)
         :filter-exclusion (describe-db# (schemas-db# "exclusion" "scratch") filter-sql#)

         ;; `*` matches `information_schema` too, which is the only case where the two gates in
         ;; `schema-filter-fn` are in tension: `excluded-schemas` has to win, as it does in
         ;; Metabase's own `filtered-syncable-schemas`. `scratch` is not a system database, so the
         ;; wildcard correctly keeps it.
         :filter-wildcard  (describe-db# (schemas-db# "inclusion" "*") filter-sql#)

         ;; With no catalog pinned, `SHOW CATALOGS` drives the enumeration and every schema name
         ;; carries its catalog. Each catalog has its own `information_schema`, so the system-database
         ;; check has to look at the database part rather than the composed name.
         :all-catalogs     (describe-db# all-db# all-sql#)
         :blank-catalog    (describe-db# blank-db# all-sql#)

         ;; `describe-table` builds its own SQL from the schema and was changed by the same commit;
         ;; nothing else in the suite reaches it.
         :describe-table
         (with-bindings
           {(resolve 'metabase.driver.sql-jdbc.execute/*sql-results*) all-sql#}
           (call# 'metabase.driver/describe-table
                  [:starrocks all-db# {:schema "hive_catalog.sales_hive" :name "orders"}]))

         ;; Metabase quotes a schema as ONE identifier. A `catalog.database` schema has to come back
         ;; apart in BOTH places it appears -- the FROM clause and every qualified column reference.
         :identifiers
         {:field-multi  (to-hsql# (ident# :field "hive_catalog.sales_hive" "orders" "order_id"))
          :table-multi  (to-hsql# (ident# :table "hive_catalog.sales_hive" "orders"))
          :table-single (to-hsql# (ident# :table "sales_kr" "mv_daily_totals"))
          :alias-left-alone (to-hsql# (ident# :field-alias "not.a.schema"))

          ;; A joined table or saved question contributes its display name as the alias, and that
          ;; name is free text a user can edit. Two components mean there is no schema here at all.
          :join-alias       (to-hsql# (ident# :field "Revenue v1.2" "amount"))

          ;; 0.63 may prepend a `:db` component to a table identifier, which pushes the schema off
          ;; index 0. Counting from the end is what survives that.
          :table-with-db    (to-hsql# (ident# :table "somedb" "hive_catalog.sales_hive" "orders"))

          ;; Only the schema position is eligible. A dot anywhere else is data, not structure.
          :dotted-tail      (to-hsql# (ident# :field "sales_hive" "orders" "weird.col"))
          :alias-multi      (to-hsql# (ident# :field-alias "a.b" "c"))
          :field-single     (to-hsql# (ident# :field "a.b"))

          ;; Metabase hangs a column's database type off the identifier as metadata, and the
          ;; rewrite builds a new vector. `prn` drops metadata, so it is read off in this JVM.
          :meta-kept        (some-> (to-hsql# (with-meta (ident# :field "hive_catalog.sales_hive"
                                                                 "orders" "order_id")
                                                {:database-type "bigint"}))
                                    :ok
                                    meta)}}))))

(defn- run-shape*
  "Load the driver in a fresh JVM against `shape`'s stub Metabase; return the probe result."
  [shape]
  (let [cp     (str (System/getProperty "java.class.path")
                    java.io.File/pathSeparator
                    (.getPath (io/file (tc/repo-root) "test" "stubs" shape)))
        {:keys [exit out err]} (shell/sh "java" "-cp" cp "clojure.main" "-e" (pr-str probe-form)
                                         :dir (tc/repo-root))]
    {:exit   exit
     :err    err
     :result (when-let [idx (str/index-of (str out) marker)]
               (edn/read-string (subs out (+ idx (count marker)))))}))

;; Each shape costs a JVM start, so run it once and share. Previously every deftest respawned
;; every shape (16 JVMs per suite run).
(def ^:private run-shape (memoize run-shape*))

(def ^:private shapes
  "Expected registrations per Metabase shape. `describe-fks` is present in all of them (0.49+).

   `:schema-filter?` says whether the shape's host has `metabase.driver.sync`, the namespace the
   Schemas filter is built on. Every released version the driver targets has it; `nosync` models
   a host that has moved or renamed it, and exists so the suite has ONE shape where a `:require`
   of that namespace would fail to load."
  {"v50"    {:desc           "Metabase 0.50 - 0.56"
             :schema-filter? true
             :registered     '#{metabase.driver/describe-table-fks
                                metabase.driver/describe-fks
                                metabase.driver/describe-database}}
   "v57"    {:desc           "Metabase 0.57 - 0.58"
             :schema-filter? true
             :registered     '#{metabase.driver/describe-table-fks
                                metabase.driver/describe-fks
                                metabase.driver/describe-database*}}
   "v59"    {:desc           "Metabase 0.59 - 0.62"
             :schema-filter? true
             :registered     '#{metabase.driver/describe-table-fks
                                metabase.driver/describe-fks
                                metabase.driver/describe-database*
                                metabase.driver.sql.query-processor/transform-literal-like-pattern-honeysql}}
   "v63"    {:desc           "Metabase 0.63+"
             :schema-filter? true
             :registered     '#{metabase.driver/describe-fks
                                metabase.driver/describe-database*
                                metabase.driver.sql.query-processor/transform-literal-like-pattern-honeysql}}
   "nosync" {:desc           "Metabase 0.63+ without metabase.driver.sync (moved or renamed)"
             :schema-filter? false
             :registered     '#{metabase.driver/describe-fks
                                metabase.driver/describe-database*
                                metabase.driver.sql.query-processor/transform-literal-like-pattern-honeysql}}})

(defn- probe!
  "Run a shape and assert it produced usable output, surfacing the child JVM's exit code and
   stderr on failure. Every deftest goes through this: a shape that fails to compile is the
   scenario this suite exists for, and reporting it as an opaque nil result hides the cause."
  [shape]
  (let [{:keys [exit err result]} (run-shape shape)]
    (is (zero? exit)
        (str "driver failed to compile against " shape ".\n"
             "This is the original bug class -- a compile-time reference to a var this "
             "Metabase version does not have.\n" err))
    (is (some? result)
        (str "no probe output from " shape " (exit " exit ").\n" err))
    result))

(deftest driver-namespace-loads-against-every-shape
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (probe! shape))))

(deftest registers-exactly-the-right-methods-per-shape
  (doseq [[shape {:keys [desc registered]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (let [result (probe! shape)]
        (is (= registered (:registered result))
            "registered set should match the compatibility matrix for this shape")
        (is (empty? (remove (set (:present result)) (:registered result)))
            "nothing should be registered on a multimethod the host does not have")))))

(deftest describe-database-split-is-mutually-exclusive
  (testing ":unless must register describe-database* OR describe-database, never both"
    (doseq [[shape {:keys [desc]}] (sort shapes)]
      (testing (str shape " (" desc ")")
        (let [result   (probe! shape)
              reg      (:registered result)
              new-way? (contains? reg 'metabase.driver/describe-database*)
              old-way? (contains? reg 'metabase.driver/describe-database)]
          (is (not (and new-way? old-way?))
              "registering both would shadow the host's do-with-resilient-connection wrapper")
          (is (or new-way? old-way?)
              "describe-database must be implemented one way or the other")
          (is (= new-way? (contains? (set (:present result)) 'metabase.driver/describe-database*))
              "prefer describe-database* exactly when the host has it"))))))

(deftest registered-methods-are-callable-with-real-arities
  (doseq [[shape {:keys [desc registered]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (let [calls (:calls (probe! shape))]
        (testing "describe-fks accepts 0.63's trailing options map"
          (is (= {:ok []} (:describe-fks-trailing-map calls))))
        (testing "describe-fks accepts trailing kwargs"
          (is (= {:ok []} (:describe-fks-kwargs calls))))
        (testing "describe-fks accepts no options at all"
          (is (= {:ok []} (:describe-fks-no-options calls))))
        (testing "describe-table-fks is 3-arity where the host still has it"
          ;; Read off the shape's own expectations rather than its name, so a new shape that
          ;; also lacks the multimethod does not need to be listed here by hand.
          (is (= (if (contains? registered 'metabase.driver/describe-table-fks) {:ok nil} :absent)
                 (:describe-table-fks calls))))))))

(deftest describe-database-returns-the-shape-sync-expects
  (testing "the extracted describe-database-impl still works, under whichever name it registered"
    (doseq [[shape {:keys [desc]}] (sort shapes)]
      (testing (str shape " (" desc ")")
        (is (= {:ok {:tables #{{:name "orders"    :schema "silver"}
                               {:name "customers" :schema "silver"}}}}
               (:describe-database (probe! shape)))
            "information_schema must be filtered out and tables returned as a set")))))

(deftest result-column-type-correction-survives-every-shape
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (is (= {:ok [{:name          "bool_col"
                    :database_type "BOOLEAN"
                    :base_type     :type/Boolean}
                   {:name          "tinyint_col"
                    :database_type "TINYINT"
                    :base_type     :type/Integer}]}
             (get-in (probe! shape) [:calls :column-metadata]))
          "precision-1 TINYINT should be Boolean without changing real TINYINT columns"))))

(def ^:private kept-only
  {:ok {:tables #{{:name "mv_daily_totals" :schema "sales_kr"}}}})

(def ^:private kept-and-scratch
  {:ok {:tables #{{:name "mv_daily_totals" :schema "sales_kr"}
                  {:name "temp_log"               :schema "scratch"}}}})

(defn- filter-cases
  "Probe key -> [what sync should return, the database that must never have been queried], for a
   shape whose host does (`schema-filter?`) or does not have the schema-filter functions.

   `information_schema` has no canned `SHOW TABLES` answer, and the driver swallows a failed one,
   so the wildcard case looks identical in the result whether `excluded-schemas` was honoured or
   not. Only the SQL log tells them apart.

   Without the host functions every case collapses to one answer: the operator's patterns are
   ignored, `scratch` is synced alongside `sales_kr`, and `information_schema` is still kept out
   because `excluded-schemas` is the driver's own and needs no host. That last part is what
   separates 'degraded to the old behaviour' from 'filter fell off entirely'."
  [schema-filter?]
  (if schema-filter?
    {:filter-inclusion [kept-only        "scratch"]
     :filter-exclusion [kept-only        "scratch"]
     :filter-wildcard  [kept-and-scratch "information_schema"]}
    {:filter-inclusion [kept-and-scratch "information_schema"]
     :filter-exclusion [kept-and-scratch "information_schema"]
     :filter-wildcard  [kept-and-scratch "information_schema"]}))

(deftest shapes-have-the-sync-namespace-they-declare
  (testing (str "the fixture varies what the suite says it varies -- a nosync pass has to mean the "
                "driver survived without the namespace, not that the shape quietly had it")
    (doseq [[shape {:keys [desc schema-filter?]}] (sort shapes)]
      (testing (str shape " (" desc ")")
        (is (= schema-filter? (:host-sync? (probe! shape))))))))

(deftest fallback-warns-exactly-when-the-host-lacks-the-helpers
  (testing "the WARN the README promises fires without the host functions, and never with them"
    (doseq [[shape {:keys [desc schema-filter?]}] (sort shapes)]
      (testing (str shape " (" desc ")")
        (let [result    (probe! shape)
              ;; Each probe carries the cumulative log at the moment it ran, and the probes run
              ;; in an order the child JVM chooses -- so judge by the union, and by the largest
              ;; snapshot for the once-only property.
              snapshots (for [probe-key (keys (filter-cases schema-filter?))]
                          (filter #(str/includes? % "Schemas") (:warns (probe-key result))))
              warned?   (boolean (seq (apply concat snapshots)))]
          (is (= (not schema-filter?) warned?)
              (if schema-filter?
                "a released Metabase has the helpers; warning on every sync would be a regression"
                "without the helpers this line is all the operator has to explain the unfiltered sync"))
          (when-not schema-filter?
            (is (= 1 (apply max 0 (map count snapshots)))
                "resolved once per JVM, so the WARN must not repeat on every sync")))))))

(deftest schema-filter-narrows-the-sync
  (doseq [[shape {:keys [desc schema-filter?]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (let [result (probe! shape)]
        (doseq [[probe-key [expected _]] (filter-cases schema-filter?)]
          (testing (name probe-key)
            (is (= expected (:call (probe-key result))))))))))

(def ^:private both-catalogs
  {:ok {:tables #{{:name "mv_daily_totals" :schema "default_catalog.sales_kr"}
                  {:name "orders"          :schema "hive_catalog.sales_hive"}}}})

(deftest spans-every-catalog-when-none-is-pinned
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (let [result             (probe! shape)
            {:keys [call sql]} (:all-catalogs result)]
        (testing "schemas are composed as catalog.database"
          (is (= both-catalogs call)))
        (testing "every catalog's own information_schema is dropped"
          (is (not (some #(re-find #"information_schema" %) sql))
              "excluded-schemas must match the database part, not the composed name"))
        (testing "a catalog that cannot be listed is survived, not fatal"
          (is (some #{"SHOW DATABASES FROM `unreachable_catalog`"} sql)
              "the fixture must actually attempt the catalog that has no canned answer"))
        (testing "a blank Catalog is the same as an absent one -- Metabase submits \"\" for a cleared field"
          (is (= both-catalogs (:call (:blank-catalog result)))))))))

(deftest describe-table-qualifies-the-catalog
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (is (= {:ok {:schema "hive_catalog.sales_hive"
                   :name   "orders"
                   :fields #{{:name              "order_id"
                              :database-type     "bigint"
                              :base-type         :type/BigInteger
                              :database-position 0}}}}
             (:describe-table (probe! shape)))
          "DESCRIBE has to split the schema too, or it asks for a database that does not exist"))))

(deftest identifiers-split-the-catalog-back-out
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (let [ids  (:identifiers (probe! shape))
            tag  :metabase.util.honey-sql-2/identifier]
        (testing "the rewrite preserves the tag and the identifier type"
          (is (= [tag :field] (take 2 (:ok (:field-multi ids)))))
          (is (= [tag :table] (take 2 (:ok (:table-multi ids))))))
        (testing "a qualified column reference, which is where fixing only the table falls short"
          (is (= ["hive_catalog" "sales_hive" "orders" "order_id"]
                 (last (:ok (:field-multi ids))))))
        (testing "the FROM clause"
          (is (= ["hive_catalog" "sales_hive" "orders"]
                 (last (:ok (:table-multi ids))))))
        (testing "a pinned connection is untouched"
          (is (= ["sales_kr" "mv_daily_totals"]
                 (last (:ok (:table-single ids))))))
        (testing "an alias is never split, whatever it contains"
          (is (= ["not.a.schema"] (last (:ok (:alias-left-alone ids))))))
        (testing "a two-component field is a join alias, not a schema -- splitting it breaks the query"
          (is (= ["Revenue v1.2" "amount"] (last (:ok (:join-alias ids))))))
        (testing "the schema is found from the end, so a leading :db component does not hide it"
          (is (= ["somedb" "hive_catalog" "sales_hive" "orders"]
                 (last (:ok (:table-with-db ids))))))
        (testing "a dot outside the schema position is data, not structure"
          (is (= ["sales_hive" "orders" "weird.col"] (last (:ok (:dotted-tail ids))))))
        (testing "the type guard, not the arity guard, is what protects a multi-part alias"
          (is (= ["a.b" "c"] (last (:ok (:alias-multi ids))))))
        (testing "a lone component carries no schema"
          (is (= ["a.b"] (last (:ok (:field-single ids))))))
        (testing "the rewrite keeps the identifier's metadata, which carries the column's database type"
          (is (= {:database-type "bigint"} (:meta-kept ids))))))))

(deftest schema-filter-runs-before-show-tables
  (testing "a filtered-out database is never queried, not merely dropped from the result"
    (doseq [[shape {:keys [desc schema-filter?]}] (sort shapes)]
      (testing (str shape " (" desc ")")
        (let [result (probe! shape)]
          (doseq [[probe-key [_ never-queried]] (filter-cases schema-filter?)]
            (testing (name probe-key)
              (let [sql (:sql (probe-key result))]
                (is (some #{"SHOW TABLES FROM `sales_kr`"} sql)
                    "the fixture must actually reach the database it keeps")
                (is (not (some #{(str "SHOW TABLES FROM `" never-queried "`")} sql))
                    "filtering after the round trip passes the result assertion but not this")))))))))
