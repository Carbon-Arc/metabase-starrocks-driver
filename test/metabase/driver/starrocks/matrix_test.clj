(ns metabase.driver.starrocks.matrix-test
  "Compiles the REAL driver namespace against a stub Metabase of each supported shape.

   This is the guard that matters. A single compile-time reference to a var the host does not
   have aborts the whole namespace, so 'did it load at all' is the primary assertion -- and it
   catches references nobody thought to denylist. On unmodified `main` v50/v57 fail on
   `transform-literal-like-pattern-honeysql` and v63 fails on `describe-table-fks`.

   Each shape needs its own JVM: a single JVM can only load `metabase.driver` once. Each shape
   is therefore run exactly once and the result shared across the deftests below."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [metabase.driver.starrocks.test-common :as tc]))

(def ^:private tracked
  '[metabase.driver/describe-table-fks
    metabase.driver/describe-fks
    metabase.driver/describe-database*
    metabase.driver/describe-database
    metabase.driver.sql.query-processor/transform-literal-like-pattern-honeysql])

(def ^:private marker "#RESULT#")

;; Built as data and `pr-str`d into `-e`, so there is no string escaping to get wrong.
(def ^:private probe-form
  `(do
     (require 'metabase.driver.starrocks)
     (require 'metabase.driver.sql-jdbc.execute)
     (require 'stubs.fake-jdbc)
     (let [probe#       (fn [s#] (some-> (namespace s#) symbol find-ns (ns-resolve (symbol (name s#)))))
           registered#  (fn [s#] (when-let [v# (probe# s#)]
                                   (contains? (methods (var-get v#)) :starrocks)))
           call#        (fn [s# args#]
                          (if-let [v# (probe# s#)]
                            (try
                              {:ok (apply (var-get v#) args#)}
                              (catch Throwable t#
                                {:err (str (.getName (class t#)) ": " (.getMessage t#))}))
                            :absent))
           db#          {:id 1 :name "test"}
           ;; Same catalog and the same canned answers; only the Schemas filter differs.
           schemas-db#  (fn [type# patterns#]
                          {:id 2
                           :name "filtered"
                           :details {:schema-filters-type     type#
                                     :schema-filters-patterns patterns#}})
           ;; Returns both what sync got back AND every SQL the driver ran, because the point of
           ;; filtering in `get-schemas` is a query that is never issued -- invisible in the result.
           describe-db# (fn [dbx# sql-results#]
                          (with-bindings
                            {(resolve 'metabase.driver.sql-jdbc.execute/*sql-results*) sql-results#}
                            ((var-get (resolve 'stubs.fake-jdbc/reset-executed-sql!)))
                            (let [mmx# (if (probe# 'metabase.driver/describe-database*)
                                         'metabase.driver/describe-database*
                                         'metabase.driver/describe-database)]
                              {:call (call# mmx# [:starrocks dbx#])
                               :sql  @(var-get (resolve 'stubs.fake-jdbc/executed-sql))})))
           filter-sql#  {"SHOW DATABASES"              [["Database"] [{"Database" "sales_kr"}
                                                                      {"Database" "scratch"}
                                                                      {"Database" "information_schema"}]]
                         "SHOW TABLES FROM `sales_kr`" [["Tables"]   [{"Tables" "mv_daily_totals"}]]
                         "SHOW TABLES FROM `scratch`"  [["Tables"]   [{"Tables" "temp_log"}]]}]
       (println ~marker)
       (prn
        {:present    (into #{} (filter probe#) '~tracked)
         :registered (into #{} (filter registered#) '~tracked)
         :calls
         ;; Exercises the real call shapes. Nothing else in the suite reaches these: FK sync is
         ;; gated off by `:metadata/key-constraints false`, so a wrong arity would ship silently.
         {:describe-fks-trailing-map
          (call# 'metabase.driver/describe-fks
                 [:starrocks db# {:schema-names ["s"] :table-names ["t"]}])

          :describe-fks-kwargs
          (call# 'metabase.driver/describe-fks
                 [:starrocks db# :schema-names ["s"]])

          :describe-fks-no-options
          (call# 'metabase.driver/describe-fks [:starrocks db#])

          :describe-table-fks
          (call# 'metabase.driver/describe-table-fks
                 [:starrocks db# {:name "t" :schema "s"}])

          ;; Upstream v1.0.6 added a result-column metadata correction. Keep behavioural coverage
          ;; while compiling it against every Metabase shape: precision-1 TINYINT is StarRocks
          ;; BOOLEAN, while a real TINYINT (precision 4) must remain an integer.
          :column-metadata
          (call# 'metabase.driver.sql-jdbc.execute/column-metadata
                 [:starrocks
                  (reify java.sql.ResultSetMetaData
                    (getPrecision [_# i#]
                      (case i# 1 1 2 4)))])}

         ;; Behavioural coverage of `describe-database-impl`, which the matrix rewired from a
         ;; literal defmethod. The stub connection answers the driver's real SHOW statements, so
         ;; this asserts the extracted body still produces the shape sync expects -- not merely
         ;; that *something* is attached to the multimethod.
         ;; `with-bindings` (map of var->value, resolved at runtime) rather than `binding`,
         ;; which needs a literal symbol -- and a literal reference to a stub var would be
         ;; resolved when this form is compiled, before the `require` above has run.
         :describe-database
         (with-bindings
           {(resolve 'metabase.driver.sql-jdbc.execute/*sql-results*)
            {"SHOW DATABASES"            [["Database"] [{"Database" "silver"}
                                                        {"Database" "information_schema"}]]
             "SHOW TABLES FROM `silver`" [["Tables"]   [{"Tables" "orders"}
                                                        {"Tables" "customers"}]]}}
           (let [mm# (if (probe# 'metabase.driver/describe-database*)
                       'metabase.driver/describe-database*
                       'metabase.driver/describe-database)]
             (call# mm# [:starrocks db#])))

         ;; `scratch` is given a canned answer on purpose. Omitting it would prove nothing: the
         ;; driver swallows a failed `SHOW TABLES` and returns [], so the result would look
         ;; filtered either way.
         :filter-inclusion (describe-db# (schemas-db# "inclusion" "sales_*") filter-sql#)
         :filter-exclusion (describe-db# (schemas-db# "exclusion" "scratch") filter-sql#)

         ;; `*` matches `information_schema` too, which is the only case where the two gates in
         ;; `schema-filter-fn` are in tension: `excluded-schemas` has to win, as it does in
         ;; Metabase's own `filtered-syncable-schemas`. `scratch` is not a system database, so the
         ;; wildcard correctly keeps it.
         :filter-wildcard  (describe-db# (schemas-db# "inclusion" "*") filter-sql#)}))))

(defn- run-shape*
  "Load the driver in a fresh JVM against `shape`'s stub Metabase; return the probe result."
  [shape]
  (let [cp     (str (System/getProperty "java.class.path")
                    java.io.File/pathSeparator
                    (.getPath (io/file (tc/repo-root) "test" "stubs" shape)))
        {:keys [exit out err]} (shell/sh "java" "-cp" cp "clojure.main" "-e" (pr-str probe-form)
                                         :dir (tc/repo-root))]
    {:exit   exit
     :err    err
     :result (when-let [idx (str/index-of (str out) marker)]
               (edn/read-string (subs out (+ idx (count marker)))))}))

;; Each shape costs a JVM start, so run it once and share. Previously every deftest respawned
;; every shape (16 JVMs per suite run).
(def ^:private run-shape (memoize run-shape*))

(def ^:private shapes
  "Expected registrations per Metabase shape. `describe-fks` is present in all of them (0.49+)."
  {"v50" {:desc       "Metabase 0.50 - 0.56"
          :registered '#{metabase.driver/describe-table-fks
                         metabase.driver/describe-fks
                         metabase.driver/describe-database}}
   "v57" {:desc       "Metabase 0.57 - 0.58"
          :registered '#{metabase.driver/describe-table-fks
                         metabase.driver/describe-fks
                         metabase.driver/describe-database*}}
   "v59" {:desc       "Metabase 0.59 - 0.62"
          :registered '#{metabase.driver/describe-table-fks
                         metabase.driver/describe-fks
                         metabase.driver/describe-database*
                         metabase.driver.sql.query-processor/transform-literal-like-pattern-honeysql}}
   "v63" {:desc       "Metabase 0.63+"
          :registered '#{metabase.driver/describe-fks
                         metabase.driver/describe-database*
                         metabase.driver.sql.query-processor/transform-literal-like-pattern-honeysql}}})

(defn- probe!
  "Run a shape and assert it produced usable output, surfacing the child JVM's exit code and
   stderr on failure. Every deftest goes through this: a shape that fails to compile is the
   scenario this suite exists for, and reporting it as an opaque nil result hides the cause."
  [shape]
  (let [{:keys [exit err result]} (run-shape shape)]
    (is (zero? exit)
        (str "driver failed to compile against " shape ".\n"
             "This is the original bug class -- a compile-time reference to a var this "
             "Metabase version does not have.\n" err))
    (is (some? result)
        (str "no probe output from " shape " (exit " exit ").\n" err))
    result))

(deftest driver-namespace-loads-against-every-shape
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (probe! shape))))

(deftest registers-exactly-the-right-methods-per-shape
  (doseq [[shape {:keys [desc registered]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (let [result (probe! shape)]
        (is (= registered (:registered result))
            "registered set should match the compatibility matrix for this shape")
        (is (empty? (remove (set (:present result)) (:registered result)))
            "nothing should be registered on a multimethod the host does not have")))))

(deftest describe-database-split-is-mutually-exclusive
  (testing ":unless must register describe-database* OR describe-database, never both"
    (doseq [[shape {:keys [desc]}] (sort shapes)]
      (testing (str shape " (" desc ")")
        (let [result   (probe! shape)
              reg      (:registered result)
              new-way? (contains? reg 'metabase.driver/describe-database*)
              old-way? (contains? reg 'metabase.driver/describe-database)]
          (is (not (and new-way? old-way?))
              "registering both would shadow the host's do-with-resilient-connection wrapper")
          (is (or new-way? old-way?)
              "describe-database must be implemented one way or the other")
          (is (= new-way? (contains? (set (:present result)) 'metabase.driver/describe-database*))
              "prefer describe-database* exactly when the host has it"))))))

(deftest registered-methods-are-callable-with-real-arities
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (let [calls (:calls (probe! shape))]
        (testing "describe-fks accepts 0.63's trailing options map"
          (is (= {:ok []} (:describe-fks-trailing-map calls))))
        (testing "describe-fks accepts trailing kwargs"
          (is (= {:ok []} (:describe-fks-kwargs calls))))
        (testing "describe-fks accepts no options at all"
          (is (= {:ok []} (:describe-fks-no-options calls))))
        (testing "describe-table-fks is 3-arity where the host still has it"
          (is (= (if (= shape "v63") :absent {:ok nil})
                 (:describe-table-fks calls))))))))

(deftest describe-database-returns-the-shape-sync-expects
  (testing "the extracted describe-database-impl still works, under whichever name it registered"
    (doseq [[shape {:keys [desc]}] (sort shapes)]
      (testing (str shape " (" desc ")")
        (is (= {:ok {:tables #{{:name "orders"    :schema "silver"}
                               {:name "customers" :schema "silver"}}}}
               (:describe-database (probe! shape)))
            "information_schema must be filtered out and tables returned as a set")))))

(deftest result-column-type-correction-survives-every-shape
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (is (= {:ok [{:name          "bool_col"
                    :database_type "BOOLEAN"
                    :base_type     :type/Boolean}
                   {:name          "tinyint_col"
                    :database_type "TINYINT"
                    :base_type     :type/Integer}]}
             (get-in (probe! shape) [:calls :column-metadata]))
          "precision-1 TINYINT should be Boolean without changing real TINYINT columns"))))

(def ^:private kept-only
  {:ok {:tables #{{:name "mv_daily_totals" :schema "sales_kr"}}}})

(def ^:private kept-and-scratch
  {:ok {:tables #{{:name "mv_daily_totals" :schema "sales_kr"}
                  {:name "temp_log"               :schema "scratch"}}}})

(def ^:private filter-cases
  "Probe key -> [what sync should return, the database that must never have been queried].

   `information_schema` has no canned `SHOW TABLES` answer, and the driver swallows a failed one,
   so the wildcard case looks identical in the result whether `excluded-schemas` was honoured or
   not. Only the SQL log tells them apart."
  {:filter-inclusion [kept-only      "scratch"]
   :filter-exclusion [kept-only      "scratch"]
   :filter-wildcard  [kept-and-scratch "information_schema"]})

(deftest schema-filter-narrows-the-sync
  (doseq [[shape {:keys [desc]}] (sort shapes)]
    (testing (str shape " (" desc ")")
      (let [result (probe! shape)]
        (doseq [[probe-key [expected _]] filter-cases]
          (testing (name probe-key)
            (is (= expected (:call (probe-key result))))))))))

(deftest schema-filter-runs-before-show-tables
  (testing "a filtered-out database is never queried, not merely dropped from the result"
    (doseq [[shape {:keys [desc]}] (sort shapes)]
      (testing (str shape " (" desc ")")
        (let [result (probe! shape)]
          (doseq [[probe-key [_ never-queried]] filter-cases]
            (testing (name probe-key)
              (let [sql (:sql (probe-key result))]
                (is (some #{"SHOW TABLES FROM `sales_kr`"} sql)
                    "the fixture must actually reach the database it keeps")
                (is (not (some #{(str "SHOW TABLES FROM `" never-queried "`")} sql))
                    "filtering after the round trip passes the result assertion but not this")))))))))
