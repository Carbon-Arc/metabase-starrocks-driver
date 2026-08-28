(ns metabase.driver)

(load "/stubs/driver_common")

;;; Shape: Metabase 0.63+
;;;   describe-table-fks  REMOVED in 0.63 -- deliberately absent. This is the shape that
;;;                       reproduces the original production failure.
;;;   describe-database*  present (first ships in release-x.57.x)

(defmulti describe-database*
  (fn [driver _database] driver))
