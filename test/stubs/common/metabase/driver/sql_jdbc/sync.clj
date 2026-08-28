(ns metabase.driver.sql-jdbc.sync
  "Stub. Identical across every Metabase version the driver targets.")

(defn pattern-based-database-type->base-type
  "Stub with real-enough behaviour: the driver calls this at *load* time to build
   `starrocks-type->base-type`, so it has to return a working fn, not nil."
  [pattern-pairs]
  (fn [database-type]
    (some (fn [[pattern base-type]]
            (when (re-find pattern (str database-type))
              base-type))
          pattern-pairs)))

(defmulti database-type->base-type
  (fn [driver _field-type] driver))

(defmulti current-user-table-privileges
  (fn [driver & _] driver))
