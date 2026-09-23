(ns metabase.driver)

(load "/stubs/driver_common")

;;; Shape: a Metabase 0.63+ host whose `metabase.driver.sync` has moved or been renamed.
;;;   Hypothetical, but the class of change is not: `metabase.config` became `metabase.config.core`
;;;   in 0.55. The multimethod surface is identical to v63; what this shape varies is the ABSENCE
;;;   of metabase/driver/sync.clj from this directory. A `:require` of that namespace fails to
;;;   load here, so this is the shape that proves the Schemas filter degrades instead of taking
;;;   the driver down with it.
;;;   describe-table-fks  absent (removed in 0.63)
;;;   describe-database*  present

(defmulti describe-database*
  (fn [driver _database] driver))
