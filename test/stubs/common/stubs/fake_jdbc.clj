(ns stubs.fake-jdbc
  "Just enough of java.sql for the stub `do-with-connection-with-options` to actually invoke its
   callback. Without this the stub returned nil without calling `f`, so the driver's
   `describe-database-impl` / `describe-table` bodies were never executed by any test.

   Only the handful of methods the driver actually calls are implemented; anything else throws,
   which is the desired behaviour for a fixture."
  (:import
   (java.sql Connection ResultSet Statement)))

(defn result-set
  "A ResultSet over `rows` (a vector of maps). Columns are addressable by 1-based index -- in
   which case `columns` gives the positional order -- or by label."
  ^ResultSet [columns rows]
  (let [cursor (atom -1)
        current #(nth rows @cursor)]
    (reify ResultSet
      (next [_] (< (swap! cursor inc) (count rows)))
      (^String getString [_ ^int i] (get (current) (nth columns (dec i))))
      (^String getString [_ ^String label] (get (current) label))
      (close [_] nil))))

(def executed-sql
  "Every SQL string `connection` has been asked to run, in order.

   Needed because asserting on what a driver method RETURNS cannot distinguish \"never asked\"
   from \"asked and discarded\", and for anything that filters to save a round trip, that
   distinction is the whole claim. `get-tables-in-schema` also swallows a failed `SHOW TABLES`
   and returns `[]`, so omitting a canned answer does not surface the query either."
  (atom []))

(defn reset-executed-sql!
  "Empties the log. Call it immediately before the driver method under test."
  []
  (reset! executed-sql []))

(defn connection
  "A Connection whose statements answer from `sql->result`, a map of exact SQL string to
   `[columns rows]`. Unknown SQL throws, so a fixture drifting out of step with the driver's
   queries fails loudly instead of silently returning nothing."
  ^Connection [sql->result]
  (reify Connection
    (createStatement [_]
      (reify Statement
        (executeQuery [_ sql]
          (swap! executed-sql conj sql)
          (if-let [[columns rows] (get sql->result sql)]
            (result-set columns rows)
            (throw (ex-info (str "fake-jdbc: no canned result for SQL: " sql)
                            {:sql sql :known (vec (sort (keys sql->result)))}))))
        (close [_] nil)))
    (close [_] nil)))
