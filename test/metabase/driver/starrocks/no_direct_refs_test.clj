(ns metabase.driver.starrocks.no-direct-refs-test
  "Fast guard: no literal `defmethod` / `prefer-method` against a version-sensitive multimethod.

   Strictly weaker than `matrix-test`, which compiles the driver against each stub host and so
   catches references nobody anticipated. This exists because it fails in milliseconds with an
   error that points straight at the matrix, whereas the compile test reports a raw `No such var`.

   The denylist is *derived from the matrix itself* rather than hand-maintained: a duplicated
   list would silently fall out of step the first time someone adds a row and forgets to update
   it here, and the guard would then pass on exactly the code it exists to reject."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [metabase.driver.starrocks.test-common :as tc]))

(def ^:private matrix-source-path ["src" "metabase" "driver" "starrocks.clj"])

(defn- source-files
  "Every .clj under src/ -- not just the driver namespace, so a version-sensitive `defmethod`
   added in a helper namespace is caught too."
  []
  (->> (file-seq (tc/repo-file "src"))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".clj")))))

(defn- version-sensitive-names
  "Simple names of the multimethods listed in the `version-sensitive-methods` matrix. Read out of
   the source so there is a single source of truth. Matched on the name alone, so both
   `driver/foo` and `metabase.driver/foo` are caught."
  []
  (let [source (slurp (apply tc/repo-file matrix-source-path))]
    (into #{}
          (comp (map second)
                (map #(or (second (str/split % #"/" 2)) %)))
          (re-seq #":mm\s+'([^\s\}]+)" source))))

(defn- extension-forms
  "Every (defmethod SYM ...) and (prefer-method SYM ...) in `source`, as [form-type sym-str]."
  [source]
  (for [[_ form-type sym-str] (re-seq #"\((defmethod|prefer-method)\s+([^\s\)]+)" source)]
    [form-type sym-str]))

(deftest denylist-is-actually-derived
  (testing "the matrix parse found the rows, so an empty denylist means broken rather than clean"
    (let [names (version-sensitive-names)]
      (is (>= (count names) 5)
          "expected to parse at least the five known version-sensitive methods out of the matrix")
      (is (contains? names "describe-table-fks"))
      (is (contains? names "transform-literal-like-pattern-honeysql")))))

(deftest no-literal-defmethod-on-version-sensitive-multimethods
  (let [denylist (version-sensitive-names)
        bad      (for [file (source-files)
                       [form-type sym-str] (extension-forms (slurp file))
                       :let [simple-name (or (second (str/split sym-str #"/" 2)) sym-str)]
                       :when (contains? denylist simple-name)]
                   (str (.getName ^java.io.File file) ": (" form-type " " sym-str " ...)"))]
    (is (empty? bad)
        (str "Found a literal extension of a version-sensitive multimethod:\n  "
             (str/join "\n  " bad)
             "\n\nThese resolve at COMPILE time, so on a Metabase version that lacks the var the"
             "\nwhole namespace fails to load and the plugin never registers."
             "\nRegister it from `version-sensitive-methods` (the compatibility matrix) instead."))))

(deftest guard-actually-matches-something
  (testing "the regex finds real defmethod forms, so an empty result means clean rather than broken"
    (let [forms (mapcat #(extension-forms (slurp %)) (source-files))]
      (is (seq forms))
      (is (some (fn [[_ sym-str]] (str/ends-with? sym-str "describe-table")) forms)
          "expected to still see the non-version-sensitive describe-table defmethod"))))
