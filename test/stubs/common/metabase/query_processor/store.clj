(ns metabase.query-processor.store
  "Stub of the QP store, which the driver reaches through `compat/host-fn` to learn which database
   the query being compiled is for. Present with these names and arities from 0.50 through 0.63.

   The real store is bound by the query processor for the duration of one query. Here a test binds
   `*metadata-provider*` to whatever `metabase.lib.metadata/database` should read from; left
   unbound, it models `->honeysql` running outside a query, where `metadata-provider` throws.")

(def ^:dynamic *metadata-provider* nil)

(defn initialized?
  []
  (some? *metadata-provider*))

(defn metadata-provider
  []
  (or *metadata-provider*
      (throw (ex-info "QP store is not initialized" {}))))
