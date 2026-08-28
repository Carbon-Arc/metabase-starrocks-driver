(ns metabase.driver)

(load "/stubs/driver_common")

;;; Shape: Metabase 0.50 - 0.56
;;;   describe-table-fks  present (removed in 0.63)
;;;   describe-database*  ABSENT  (first ships in release-x.57.x)

(defmulti describe-table-fks
  (fn [driver _database _table] driver))
