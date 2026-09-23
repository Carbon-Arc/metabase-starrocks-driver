(ns metabase.driver.starrocks.compat-test
  "Unit tests for the capability-probe helper itself. The end-to-end behaviour -- compiling the
   real driver against each Metabase shape -- lives in `matrix-test`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [metabase.driver.starrocks.compat :as compat]))

(defmulti sample-mm
  (fn [driver] driver))

(defmulti sample-prefer-mm
  (fn [driver] driver))

(def not-a-multifn 42)

(defn sample-fn [x] (inc x))

(def ^:private this-ns "metabase.driver.starrocks.compat-test")

(defn- sym [n] (symbol this-ns n))

(deftest register-method!-installs-when-present
  (is (true? (compat/register-method! (sym "sample-mm") ::a (constantly :installed))))
  (is (= :installed (sample-mm ::a))))

(deftest register-method!-skips-when-absent
  (testing "a var that does not exist is skipped, not thrown"
    (is (false? (compat/register-method! (sym "no-such-multimethod") ::b (constantly :nope)))))
  (testing "a namespace that does not exist is skipped too"
    (is (false? (compat/register-method! 'totally.absent.namespace/whatever ::b (constantly :nope))))))

(deftest register-method!-skips-non-multifn
  (testing "an existing var that is not a multimethod is skipped rather than blowing up"
    (is (false? (compat/register-method! (sym "not-a-multifn") ::c (constantly :nope))))))

(deftest prefer-method!-behaviour
  (testing "records a preference when the multimethod exists"
    (is (true? (compat/prefer-method! (sym "sample-prefer-mm") ::x ::y))))
  (testing "no-op when the multimethod is absent"
    (is (false? (compat/prefer-method! (sym "no-such-multimethod") ::x ::y)))))

(deftest register-all!-returns-installed-set
  (let [installed (compat/register-all!
                   ::d
                   [{:mm (sym "sample-mm")             :impl (constantly :one)}
                    {:mm (sym "no-such-multimethod")   :impl (constantly :two)}])]
    (is (= #{(sym "sample-mm")} installed)
        "only rows whose multimethod exists are reported as installed")
    (is (= :one (sample-mm ::d)))))

(deftest register-all!-unless-uses-the-same-predicate-as-installation
  (testing "a legacy row must NOT be skipped when the preferred var exists but is unusable"
    ;; `:unless` probing mere var existence would skip the legacy row here even though the
    ;; preferred row cannot install, leaving the dispatch value with no implementation at all.
    (let [installed (compat/register-all!
                     ::g
                     [{:mm (sym "not-a-multifn") :impl (constantly :preferred)}
                      {:mm     (sym "sample-mm")
                       :impl   (constantly :legacy)
                       :unless (sym "not-a-multifn")}])]
      (is (= #{(sym "sample-mm")} installed)
          "the legacy row must take over when the preferred multimethod is not usable")
      (is (= :legacy (sample-mm ::g))))))

(deftest register-all!-isolates-a-throwing-row
  (testing "one bad row must not abort the whole matrix"
    ;; A malformed row: `:mm` must be a symbol, and `namespace` on a String throws. Stands in for
    ;; any row that blows up mid-registration.
    (let [installed (compat/register-all!
                     ::h
                     [{:mm (sym "sample-mm") :impl (constantly :before)}
                      {:mm "not-a-symbol" :impl (constantly :boom)}
                      {:mm (sym "sample-prefer-mm") :impl (constantly :after)}])]
      (is (contains? installed (sym "sample-mm"))
          "rows before the throwing one stay installed")
      (is (contains? installed (sym "sample-prefer-mm"))
          "rows after the throwing one are still processed")
      (is (= :after (sample-prefer-mm ::h))))))

(deftest register-all!-honours-unless
  (testing ":unless skips the row when the named symbol resolves"
    (let [installed (compat/register-all!
                     ::e
                     [{:mm     (sym "sample-mm")
                       :impl   (constantly :legacy)
                       :unless (sym "sample-prefer-mm")}])]
      (is (= #{} installed))))
  (testing ":unless does not skip when the named symbol is absent"
    (let [installed (compat/register-all!
                     ::f
                     [{:mm     (sym "sample-mm")
                       :impl   (constantly :legacy)
                       :unless (sym "no-such-multimethod")}])]
      (is (= #{(sym "sample-mm")} installed))
      (is (= :legacy (sample-mm ::f))))))

(deftest host-fn-returns-the-function-when-present
  (is (= 2 ((compat/host-fn (sym "sample-fn")) 1)))
  (testing "loads the namespace itself rather than relying on something else having done so"
    ;; Reset first so this also holds on a second run in the same JVM (a REPL): `require` leaves
    ;; the lib in `*loaded-libs*`, and `remove-ns` alone would not make it load again.
    (remove-ns 'stubs.lazy-host)
    (dosync (alter @#'clojure.core/*loaded-libs* disj 'stubs.lazy-host))
    (is (nil? (find-ns 'stubs.lazy-host))
        "precondition: stubs.lazy-host must not be loaded before host-fn is asked for it")
    (is (= :loaded ((compat/host-fn 'stubs.lazy-host/marker))))))

(deftest host-fn-is-nil-when-absent
  (testing "missing var"
    (is (nil? (compat/host-fn (sym "no-such-fn")))))
  (testing "missing namespace"
    (is (nil? (compat/host-fn 'totally.absent.namespace/whatever))))
  (testing "a var that exists but is not callable"
    (is (nil? (compat/host-fn (sym "not-a-multifn"))))))
