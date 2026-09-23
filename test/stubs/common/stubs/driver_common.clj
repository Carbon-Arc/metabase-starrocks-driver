;;; Shared surface of `metabase.driver`, loaded into that namespace by each shape stub with
;;; `(load "/stubs/driver_common")`. Deliberately has no `ns` form: it is evaluated in whatever
;;; namespace loads it.
;;;
;;; Everything here exists in EVERY Metabase version the driver targets. Vars that come and go
;;; live in the per-shape files instead -- that is the whole point of the split, so the shapes
;;; cannot accidentally drift on anything except what they exist to vary.

(defn register!
  "Stub: the real one wires up the driver hierarchy. Nothing in these tests needs that."
  [& _])

(defmulti database-supports?
  (fn [driver feature _database] [driver feature]))

(defmulti describe-database
  (fn [driver _database] driver))

(defmulti describe-table
  (fn [driver _database _table] driver))

;; Added 0.49, still present in 0.63+.
(defmulti describe-fks
  (fn [driver & _] driver))

(defmulti prettify-native-form
  (fn [driver _native-form] driver))

(defmulti display-name
  (fn [driver] driver))

(defmulti db-start-of-week
  (fn [driver] driver))

(defmulti db-default-timezone
  (fn [driver _database] driver))

(defmulti can-connect?
  (fn [driver _details] driver))

(defmulti humanize-connection-error-message
  (fn [driver _message] driver))
