(ns metabase.driver.sql-jdbc.connection
  "Stub. Identical across every Metabase version the driver targets.")

(defmulti connection-details->spec
  (fn [driver _details] driver))

(defmacro with-connection-spec-for-testing-connection
  "Stub: binds the spec symbol to nil and runs the body. Enough to compile `can-connect?`."
  [[spec-sym _driver+details] & body]
  `(let [~spec-sym nil]
     ~@body))
