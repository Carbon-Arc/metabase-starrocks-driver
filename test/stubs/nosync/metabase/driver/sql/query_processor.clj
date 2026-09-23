(ns metabase.driver.sql.query-processor)

(load "/stubs/qp_common")

;;; Shape: same as v63 -- see ../../driver.clj for what this shape varies.
;;;   transform-literal-like-pattern-honeysql  present (added in 0.59)

(defmulti transform-literal-like-pattern-honeysql
  (fn [driver _like-rhs-honeysql] driver))
