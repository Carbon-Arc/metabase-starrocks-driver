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
  (second (re-find #"\(db-details->schema-filter-patterns\s+\"([^\"]+)\""
                   (slurp (tc/repo-file "src" "metabase" "driver" "starrocks.clj")))))

(defn- manifest-catalog-block
  "The `catalog` connection property's block in the manifest, from its `- name: catalog` line to
   the next line at the same indent."
  []
  ;; Group 2 is the block. Group 1 is the indent, captured only so the block's lines can be
   ;; matched against it -- taking `second` here would silently return the whitespace and make
   ;; every assertion below pass against nothing.
  (nth (re-find #"(?m)^(\s+)- name: catalog\s*$\n((?:\1  .*\n)+)"
                (slurp (tc/repo-file "resources" "metabase-plugin.yaml")))
       2 nil))

(deftest catalog-property-stays-optional
  (testing "the multi-catalog path exists only because an operator can submit an empty Catalog"
    (let [block (manifest-catalog-block)]
      (is (some? block) "no `- name: catalog` property found in the manifest")
      (is (not (re-find #"required:\s*true" (str block)))
          "making Catalog required again removes the only way to reach the multi-catalog path, and
           no runtime test can see it -- every fixture builds :details by hand"))))

(deftest manifest-and-driver-agree-on-the-catalog-property-name
  (testing "`pinned-catalog` reads `[:details :catalog]` literally"
    (is (re-find #"(?m)^\s+- name: catalog\s*$"
                 (slurp (tc/repo-file "resources" "metabase-plugin.yaml")))
        "renaming the manifest property silently unpins every connection")
    (is (re-find #"\[:details :catalog\]"
                 (slurp (tc/repo-file "src" "metabase" "driver" "starrocks.clj")))
        "the driver must still read the key the manifest declares")))

(deftest manifest-and-driver-agree-on-the-schema-filter-property-name
  (testing "both sides were found, so two nils cannot pass this by matching each other"
    (is (some? (manifest-prop-name))
        "no `type: schema-filters` property found in resources/metabase-plugin.yaml")
    (is (some? (driver-prop-name))
        "no literal prop name found at the db-details->schema-filter-patterns call site"))
  (is (= (manifest-prop-name) (driver-prop-name))
      "a mismatch disables the Schemas filter with no error anywhere"))
