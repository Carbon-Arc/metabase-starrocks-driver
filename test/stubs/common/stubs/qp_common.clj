;;; Shared surface of `metabase.driver.sql.query-processor`, loaded into that namespace by each
;;; shape stub with `(load "/stubs/qp_common")`. No `ns` form on purpose -- see driver_common.clj.
;;;
;;; Everything here exists in every Metabase version the driver targets.

(defmulti quote-style
  (fn [driver] driver))

(defmulti unix-timestamp->honeysql
  (fn [driver unit _expr] [driver unit]))

(defmulti current-datetime-honeysql-form
  (fn [driver] driver))

(defmulti date
  (fn [driver unit _expr] [driver unit]))

(defmulti add-interval-honeysql-form
  (fn [driver _hsql-form _amount _unit] driver))

(defmulti datetime-diff
  (fn [driver unit _x _y] [driver unit]))

(defmulti cast-temporal-string
  (fn [driver coercion _expr] [driver coercion]))

(defmulti cast-temporal-byte
  (fn [driver coercion _expr] [driver coercion]))
