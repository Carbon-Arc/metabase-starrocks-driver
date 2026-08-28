(ns metabase.driver)

(load "/stubs/driver_common")

;;; Shape: Metabase 0.59 - 0.62
;;;   describe-table-fks  present (removed in 0.63)
;;;   describe-database*  present (first ships in release-x.57.x)
;;; Differs from v57 only in the query-processor namespace.

(defmulti describe-table-fks
  (fn [driver _database _table] driver))

(defmulti describe-database*
  (fn [driver _database] driver))
