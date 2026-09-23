(ns metabase.util.log
  "Stub of Metabase's logging namespace. Present in every Metabase version the driver targets;
   stubbed here as no-op fns so the driver namespace compiles without a Metabase on the classpath.

   `warnf` is the exception: it records what it was given, because the driver's degrade path
   promises a WARN and a promise nobody asserts is one that quietly breaks -- in either direction.
   The regression to fear is not the WARN going missing on a host without the helpers but the
   WARN firing on every sync of a host WITH them.")

(def warnings
  "Every `warnf` call so far, formatted. Cumulative on purpose: the matrix probes run in an order
   the child JVM chooses, so each reads the whole log rather than a per-call slice."
  (atom []))

(defn debugf [& _])
(defn infof  [& _])
(defn warnf  [fmt & args]
  (swap! warnings conj (try (apply format (str fmt) args)
                            (catch Throwable _ (str fmt)))))
(defn errorf [& _])
