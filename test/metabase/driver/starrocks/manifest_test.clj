(ns metabase.driver.starrocks.manifest-test
  "Guards the one coupling in this driver that fails silently.

   `schema-filter-fn` asks the host for the operator's filter by passing a literal property name.
   Let that string and the `name:` of the `type: schema-filters` property in the plugin manifest
   drift apart and `db-details->schema-filter-patterns` returns `[nil nil]`: the filter reverts to
   \"All\", nothing logs, nothing throws, and sync quietly goes back to enumerating the whole
   catalog -- the exact behaviour the property exists to prevent.

   A runtime test cannot catch it. Deriving the name from the manifest is what the 1-arity
   `db-details->schema-filter-patterns` does, and that reaches through `database->driver` into a
   live host. So this is textual, like `no-direct-refs-test`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [metabase.driver.starrocks.test-common :as tc]))

(defn- manifest-prop-name
  "The `name:` of the `type: schema-filters` connection property, read out of the manifest."
  []
  (second (re-find #"(?m)^\s*-\s+name:\s+(\S+)\s+type:\s+schema-filters\s*$"
                   (slurp (tc/repo-file "resources" "metabase-plugin.yaml")))))

(defn- driver-prop-name
  "The literal the driver passes to `db-details->schema-filter-patterns`."
  []
  (second (re-find #"db-details->schema-filter-patterns\s+\"([^\"]+)\""
                   (slurp (tc/repo-file "src" "metabase" "driver" "starrocks.clj")))))

(deftest manifest-and-driver-agree-on-the-schema-filter-property-name
  (testing "both sides were found, so two nils cannot pass this by matching each other"
    (is (some? (manifest-prop-name))
        "no `type: schema-filters` property found in resources/metabase-plugin.yaml")
    (is (some? (driver-prop-name))
        "no literal prop name found at the db-details->schema-filter-patterns call site"))
  (is (= (manifest-prop-name) (driver-prop-name))
      "a mismatch disables the Schemas filter with no error anywhere"))
