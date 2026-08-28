(ns metabase.driver.sql.query-processor)

(load "/stubs/qp_common")

;;; Shape: Metabase 0.63+
;;;   transform-literal-like-pattern-honeysql  present (added in 0.59)

(defmulti transform-literal-like-pattern-honeysql
  (fn [driver _like-rhs-honeysql] driver))
