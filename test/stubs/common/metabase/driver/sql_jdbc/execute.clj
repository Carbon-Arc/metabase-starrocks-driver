(ns metabase.driver.sql-jdbc.execute
  "Stub. Identical across every Metabase version the driver targets."
  (:require
   [stubs.fake-jdbc :as fake-jdbc]))

(def ^:dynamic *sql-results*
  "Canned results for the fake connection: exact SQL string -> [columns rows]. Tests bind this
   before calling a driver method that opens a connection."
  {})

;; Present with this arity since Metabase 0.35. Upstream driver v1.0.6 started extending it, so the
;; compatibility shapes need the real extension point rather than merely enough of this namespace
;; for connection handling.
(defmulti column-metadata
  (fn [driver _rsmeta] driver))

(defmethod column-metadata :sql-jdbc
  [_driver _rsmeta]
  [{:name "bool_col"    :database_type "TINYINT" :base_type :type/Integer}
   {:name "tinyint_col" :database_type "TINYINT" :base_type :type/Integer}])

(defn do-with-connection-with-options
  "Stub that genuinely invokes `f`, so the driver's describe-* bodies are actually executed.
   An earlier version returned nil without calling `f`, which left `describe-database-impl`
   with no behavioural coverage at all."
  [_driver _database _options f]
  (f (fake-jdbc/connection *sql-results*)))
