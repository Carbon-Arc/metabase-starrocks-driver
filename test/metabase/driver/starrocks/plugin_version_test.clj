(ns metabase.driver.starrocks.plugin-version-test
  "Guards the version Metabase shows in its plugin list.

   `info.version` in the manifest is what a running Metabase displays, and for eight releases
   nothing wrote it: the source value sat at `1.0.0` from the initial commit while the tags moved
   on, so every install reported 1.0.0. Nothing failed -- the JAR was correct, only its
   self-description was wrong -- which is exactly why it went unnoticed for eight releases and why
   it needs a test rather than a convention.

   The build is run for real and the assertion is made against the built JAR rather than
   `target/classes`, because the JAR is what gets installed. A test that read the staging
   directory would pass even if the manifest never made it into the artifact.

   Note this rebuilds `target/`, as `clojure -T:build` always does."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [metabase.driver.starrocks.test-common :as tc])
  (:import
   (java.util.zip ZipFile)))

(def ^:private pinned-version
  "Deliberately not a number this project could plausibly release, so a passing test cannot be a
   coincidence of the placeholder or a real tag happening to match."
  "9.9.9-test")

(defn- manifest-version
  "The `info.version` value in a manifest's text."
  [content]
  (second (re-find #"(?m)^\s*version:[ \t]*(\S+)[ \t]*$" content)))

(defn- build-placeholder
  "`placeholder-version` as build.clj defines it. Read out of the source so that renaming it there
   without updating the manifest fails here rather than at release time."
  []
  (second (re-find #"(?s)def placeholder-version.*?\"([^\"]+)\"\)"
                   (slurp (tc/repo-file "build.clj")))))

(deftest source-manifest-holds-the-placeholder
  (testing "both sides were found, so two nils cannot pass this by matching each other"
    (is (some? (build-placeholder))
        "no `placeholder-version` found in build.clj")
    (is (some? (manifest-version (slurp (tc/repo-file "resources" "metabase-plugin.yaml"))))
        "no `version:` line found in resources/metabase-plugin.yaml"))
  (is (= (build-placeholder)
         (manifest-version (slurp (tc/repo-file "resources" "metabase-plugin.yaml"))))
      (str "the checked-in manifest version must be the placeholder the build stamps over. A real "
           "release number here is the original bug: it ships as-is and contradicts the tag.")))

(deftest built-jar-reports-the-version-the-build-was-given
  (let [{:keys [exit out err]} (shell/sh "clojure" "-T:build" "uber"
                                         ":version" (pr-str pinned-version)
                                         :dir (tc/repo-root))]
    (is (zero? exit) (str "build failed.\n" out "\n" err))
    (with-open [z (ZipFile. (tc/repo-file "target" "starrocks.metabase-driver.jar"))]
      (let [entry (.getEntry z "metabase-plugin.yaml")]
        (is (some? entry) "metabase-plugin.yaml is missing from the built JAR")
        (let [content (slurp (.getInputStream z entry))]
          (is (= pinned-version (manifest-version content))
              "the built plugin must report the version the build was given")
          (is (not (str/includes? content (build-placeholder)))
              "the placeholder must not survive into the artifact"))))))
