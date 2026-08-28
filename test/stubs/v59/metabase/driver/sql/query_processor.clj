(ns metabase.driver.sql.query-processor)

(load "/stubs/qp_common")

;;; Shape: Metabase 0.59 - 0.62
;;;   transform-literal-like-pattern-honeysql  present (added in 0.59)
;;;
;;; From 0.59 Metabase appends ESCAPE '\' to literal LIKE patterns, which StarRocks will not
;;; parse -- so from here on the driver must override this.

(defmulti transform-literal-like-pattern-honeysql
  (fn [driver _like-rhs-honeysql] driver))
