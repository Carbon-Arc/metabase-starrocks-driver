(ns metabase.driver.starrocks.test-common
  "Shared test helpers.

   Locating the repo root explicitly rather than trusting the working directory: the tests shell
   out with relative classpath entries and slurp source files, so a runner that sets cwd to
   anything other than the repo root (an IDE, a CI step with a `working-directory`, running from
   a subdirectory) would otherwise report a `FileNotFoundException` or a bogus 'failed to compile
   against v50' -- blaming the driver for what is really a cwd problem."
  (:require
   [clojure.java.io :as io]))

(def ^{:doc "Absolute path of the repository root, found by walking up from the JVM's working
             directory looking for deps.edn."}
  repo-root
  (memoize
   (fn []
     (loop [dir (.getAbsoluteFile (io/file (System/getProperty "user.dir")))]
       (cond
         (nil? dir)
         (throw (ex-info "could not locate repo root (no deps.edn found walking up from user.dir)"
                         {:user-dir (System/getProperty "user.dir")}))

         (.isFile (io/file dir "deps.edn"))
         (.getPath dir)

         :else
         (recur (.getParentFile dir)))))))

(defn repo-file
  "An absolute java.io.File for a repo-relative path."
  ^java.io.File [& path-segments]
  (apply io/file (repo-root) path-segments))
