(ns metabase.driver.sql.query-processor)

(load "/stubs/qp_common")

;;; Shape: Metabase 0.50 - 0.58
;;;   transform-literal-like-pattern-honeysql  ABSENT (added in 0.59)
;;;
;;; Metabase does not append ESCAPE '\' to literal LIKE patterns before 0.59, so there is
;;; nothing for the driver to override here. Skipping the override on this shape is correct
;;; behaviour, not a degradation.
