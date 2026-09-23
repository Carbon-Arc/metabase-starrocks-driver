(ns metabase.driver.starrocks.compat
  "Version-portable multimethod registration, and the same fail-soft lookup for host functions
   the driver calls rather than extends (`host-fn`).

   Metabase adds and retires driver multimethods between releases, and this plugin ships Clojure
   *source* that the host compiles when it lazy-loads the driver. A literal `defmethod` -- or
   `prefer-method` -- resolves its multimethod symbol at COMPILE time, so a single reference to a
   var the running Metabase does not have aborts the entire namespace and the plugin never
   registers at all.

   That failure is symmetric. It bites on vars that were removed (`driver/describe-table-fks`,
   gone in 0.63) and equally on vars not yet added (`sql.qp/transform-literal-like-pattern-honeysql`,
   new in 0.59). Before this namespace existed the driver compiled only against Metabase
   0.59-0.62.

   `try`/`catch` cannot prevent that: the compile error happens while the `try` form itself is
   being compiled, so the handler never runs. (The `try` inside `register-all!` below is a
   different thing entirely -- by then everything is runtime, and it does work.)

   Resolving the var at runtime and attaching the method by hand avoids the compile-time
   reference entirely. `.addMethod` is precisely what `defmethod` expands to.

   Capability probing is used rather than comparing version numbers on purpose:

     - Metabase Enterprise reports `v1.x.y` where OSS reports `v0.x.y` -- the major version is
       the *second* component, so naive comparison misreads every EE install.
     - Dev builds report `vLOCAL_DEV`, with no number to parse.
     - Additions can land in patch releases.
     - The version namespace itself moved to `metabase.config.core` in 0.55, so reading the
       version is *itself* a version-sensitive dependency.

   Probing asks the only question that actually matters -- is this var here? -- and is immune to
   all four."
  (:require
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

(defn- find-var*
  "Resolve a fully-qualified symbol without depending on `*ns*`. Returns a tagged result so
   callers can tell the cases apart:

     [:ok v]         the var exists
     [:no-var]       the namespace is loaded but has no such var -- the normal, expected
                     outcome for a method this Metabase version does not have
     [:no-namespace] the namespace is not loaded at all. For `register-method!` almost always
                     a maintainer error (a matrix row naming a namespace the driver never
                     requires), not a version difference, so it must not be reported the same
                     way; for `host-fn` the designed outcome"
  [sym]
  (if-let [ns* (some-> (namespace sym) symbol find-ns)]
    (if-let [v (ns-resolve ns* (symbol (name sym)))]
      [:ok v]
      [:no-var])
    [:no-namespace]))

(defn- multifn
  "The `clojure.lang.MultiFn` named by `mm-sym`, or nil if it is missing or is not a multimethod.
   Deliberately silent: callers decide what a nil means in their context."
  [mm-sym]
  (let [[status v] (find-var* mm-sym)]
    (when (= status :ok)
      (let [value (var-get v)]
        (when (instance? clojure.lang.MultiFn value)
          value)))))

(defn host-fn
  "The function the running Metabase has at `sym`, or nil when the namespace or the var is
   absent, or the var does not hold something callable (`ifn?` rather than `fn?`, so a host
   function that has since become a multimethod still counts).

   For host functions the driver CALLS, where `register-method!` is for host multimethods the
   driver EXTENDS. The motive is the same. Naming the function directly -- its namespace in
   `:require`, a qualified symbol at the call site -- resolves when the driver namespace is
   compiled, so a host that has moved or renamed the namespace fails the whole load, and the
   plugin is gone rather than one feature. The `{:added ...}` stability of a function protects its
   signature, not its address: `metabase.config` became `metabase.config.core` in 0.55 with its
   vars intact, and `metabase.plugins.classloader` became `metabase.classloader.core` in the same
   period. The latter is why this uses `clojure.core/require` and not Metabase's own wrapper:
   reaching the wrapper means naming *its* namespace, which has itself moved.

   Unlike `register-method!`, a missing namespace is a legitimate outcome here and not a
   maintainer error -- the caller has deliberately kept it out of the `:require` list -- so it is
   not logged as one. The caller decides what to do without the function. Usually that is to keep
   the behaviour the driver had before it started using it, and to say so in the log.

   The namespace is `require`d (guarded) before the lookup rather than merely looked up, so the
   answer does not depend on whether something else in the host happened to load it first. That
   is safe: it is only the compile-time reference that `try` cannot protect, and this is runtime.
   A caller that needs the answer once should hold it in a `delay`, which also serializes that
   first `require`."
  [sym]
  (when-let [ns-sym (some-> (namespace sym) symbol)]
    (try
      (require ns-sym)
      (catch Throwable t
        (log/debugf "StarRocks: could not load %s (%s); falling back to whatever is already loaded"
                    ns-sym (.getMessage t)))))
  (let [[status v] (find-var* sym)]
    (when (= status :ok)
      (let [value (var-get v)]
        (when (ifn? value)
          value)))))

(defn register-method!
  "Attach `f` to the multimethod named by `mm-sym` for `dispatch-val`, if that multimethod exists
   in the running Metabase. Returns true when installed, false when skipped."
  [mm-sym dispatch-val f]
  (let [[status v] (find-var* mm-sym)]
    (cond
      (= status :no-namespace)
      (do
        (log/warnf "StarRocks: namespace for %s is not loaded, so the method cannot be registered. This is a driver bug -- add the namespace to the :require list -- not a Metabase version difference."
                   mm-sym)
        false)

      (= status :no-var)
      (do
        (log/debugf "StarRocks: %s absent from this Metabase version; skipping" mm-sym)
        false)

      (not (instance? clojure.lang.MultiFn (var-get v)))
      (do
        (log/warnf "StarRocks: %s exists but is not a multimethod; skipping" mm-sym)
        false)

      :else
      (do
        (.addMethod ^clojure.lang.MultiFn (var-get v) dispatch-val f)
        (log/debugf "StarRocks: installed %s for %s" mm-sym dispatch-val)
        true))))

(defn prefer-method!
  "Runtime equivalent of `prefer-method`, a no-op when the multimethod is absent. Returns true
   when the preference was recorded."
  [mm-sym dispatch-val-x dispatch-val-y]
  (if-let [mm (multifn mm-sym)]
    (do
      (.preferMethod ^clojure.lang.MultiFn mm dispatch-val-x dispatch-val-y)
      true)
    false))

(defn- register-row!
  "Process one matrix row. Never throws: a single bad row must not abort the namespace, which
   would recreate the very all-or-nothing failure this namespace exists to prevent."
  [dispatch-val {:keys [mm impl prefer unless] :as row}]
  (try
    (cond
      ;; `unless` is probed with exactly the predicate installation uses. Probing mere var
      ;; existence here would be wrong: if the preferred multimethod exists but is not usable,
      ;; its own row does not install, and skipping the legacy row too would leave the driver
      ;; with no implementation at all.
      (and unless (multifn unless))
      (assoc row ::outcome :superseded)

      (register-method! mm dispatch-val impl)
      (do
        (when prefer
          (prefer-method! mm dispatch-val prefer))
        (assoc row ::outcome :installed))

      :else
      (assoc row ::outcome :absent))
    (catch Throwable t
      (log/errorf t "StarRocks: failed to register %s; continuing with the remaining methods" mm)
      (assoc row ::outcome :error))))

(defn register-all!
  "Apply a registration matrix for `dispatch-val`. Each row is a map:

     :mm     fully-qualified symbol of the multimethod        (required)
     :impl   the implementation fn                            (required)
     :prefer dispatch value to prefer `dispatch-val` over     (optional)
     :unless skip this row if THIS multimethod is usable      (optional)
     :group  capability this row helps provide                (optional)

   `:unless` expresses mutually exclusive rows. When Metabase splits a multimethod into a newer
   preferred extension point, the legacy row must NOT also register -- a direct method on the
   legacy name would shadow the host's own wrapper and silently defeat the split.

   `:group` names the capability a row contributes to. Rows without a group are genuinely
   optional (the host version simply has no such hook). If every row in a group is skipped the
   driver has silently lost a capability, which is worth a WARN -- probe-based registration
   otherwise degrades quietly, and a plugin that loads but syncs nothing is far harder to
   diagnose than one that fails loudly.

   Returns the set of `:mm` symbols actually installed."
  [dispatch-val rows]
  (let [outcomes  (mapv #(register-row! dispatch-val %) rows)
        installed? #(= :installed (::outcome %))
        installed (into #{} (comp (filter installed?) (map :mm)) outcomes)]
    ;; Reporting is best-effort and must never be the thing that takes the plugin down -- the
    ;; per-row isolation above would be pointless if summarising the outcome could still throw.
    (try
      (doseq [[group group-rows] (group-by :group outcomes)
              :when (and group (not-any? installed? group-rows))]
        (log/warnf "StarRocks: no implementation registered for %s against this Metabase version (tried %s). The driver will fall back to the generic sql-jdbc behaviour, which is probably wrong for StarRocks."
                   group (mapv :mm group-rows)))
      ;; Logged at INFO so the outcome is visible in a default Metabase log. Probe-based
      ;; registration has no load-time failure to notice, so this line is the only signal that
      ;; the driver adapted the way it was supposed to.
      (log/infof "StarRocks: registered %d of %d version-sensitive methods %s%s"
                 (count installed)
                 (count rows)
                 (vec (sort installed))
                 (let [skipped (into [] (comp (remove installed?)
                                              (map #(str (:mm %) " (" (name (::outcome %)) ")")))
                                     outcomes)]
                   (if (seq skipped)
                     (str "; not registered: " skipped)
                     "")))
      (catch Throwable t
        (log/errorf t "StarRocks: failed to report registration outcome (registration itself was unaffected)")))
    installed))
