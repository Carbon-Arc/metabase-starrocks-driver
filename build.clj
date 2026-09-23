(ns build
  "Plugin build.

   The version Metabase displays in its plugin list comes from `info.version` in
   `metabase-plugin.yaml`, and nothing in the release path used to write it: the source value sat
   at `1.0.0` from the initial commit while eight tags shipped, so every installed release
   self-reported as 1.0.0. The JAR filename carried the real version and the manifest contradicted
   it.

   So the manifest holds a placeholder and the build stamps the real version over it. The source
   value is deliberately not a plausible release number -- if stamping ever silently stops
   happening, `0.0.0-DEV` in the plugin list says so, where `1.0.0` looked like an answer."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]))

(def lib 'com.starrocks/metabase-driver)
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file "target/starrocks.metabase-driver.jar")

(def placeholder-version
  "The `info.version` value checked into `resources/metabase-plugin.yaml`, and the anchor the
   stamp matches on. Read by the test suite, so the two cannot drift apart silently."
  "0.0.0-DEV")

(defn- git-describe
  "A version for a build that was not given one -- `1.1.0-3-gabc1234`, or `-dirty` on an unclean
   tree. Informative precisely because it is not a bare release number: a local build should not
   be mistakable for a released one in the plugin list."
  []
  (try
    (let [{:keys [exit out]} (shell/sh "git" "describe" "--tags" "--always" "--dirty")]
      (when (zero? exit)
        (not-empty (str/trim out))))
    (catch Throwable _ nil)))

(defn- normalize
  "Strips a leading `v`, since tags are `v1.1.0` and `info.version` wants `1.1.0`."
  [v]
  (some-> v str/trim not-empty (str/replace #"^v" "")))

(defn- resolve-version
  "Build param, then `VERSION` from the environment (what the release workflow exports from the
   tag), then `git describe`. The param wins so a test can pin one."
  [params]
  (or (normalize (:version params))
      (normalize (System/getenv "VERSION"))
      (normalize (git-describe))
      placeholder-version))

(defn- stamp-version!
  "Replaces the placeholder in the COPIED manifest under `class-dir`. The source file is left
   alone on purpose -- a build that rewrote it in place would leave the tree dirty and the next
   build would find no placeholder to match.

   Throws when the placeholder is absent, because the whole point is that a silent miss is
   indistinguishable from a correct build until someone reads the plugin list in a running
   Metabase."
  [version]
  (let [f       (io/file class-dir "metabase-plugin.yaml")
        content (slurp f)
        pattern (re-pattern (str "(?m)^(\\s*version:[ \\t]*)"
                                 (java.util.regex.Pattern/quote placeholder-version)
                                 "[ \\t]*$"))]
    (when-not (re-find pattern content)
      (throw (ex-info (str "metabase-plugin.yaml has no `version: " placeholder-version "` line to "
                           "stamp, so the built plugin would report the wrong version in Metabase's "
                           "plugin list. Restore the placeholder, or update `placeholder-version` "
                           "in build.clj if it was renamed on purpose.")
                      {:file (.getPath f) :expected placeholder-version})))
    ;; Function replacement rather than "$1": a version containing `$` or `\` would otherwise be
    ;; read as a group reference.
    (spit f (str/replace content pattern #(str (second %) version)))
    version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn- copy-sources! []
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir}))

(defn jar [params]
  (clean nil)
  ;; Copy source and resources
  (copy-sources!)
  (println "Building plugin version" (stamp-version! (resolve-version params)))
  ;; Create the JAR (source-only, Metabase will compile at runtime)
  (b/jar {:class-dir class-dir
          :jar-file uber-file}))

(defn uber [params]
  (clean nil)
  ;; Copy source and resources
  (copy-sources!)
  (println "Building plugin version" (stamp-version! (resolve-version params)))
  ;; Create uber JAR with all dependencies
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis}))
