(ns stubs.lazy-host
  "Loaded by nothing but `compat-test`, which asserts as much. Exists so `host-fn`'s own
   `require` can be shown to do the loading, rather than the lookup succeeding only because
   something else happened to load the namespace first.")

(defn marker [] :loaded)
