(ns metabase.lib.metadata
  "Stub of `metabase.lib.metadata`. The only function the driver calls is `database`, which the
   host implements by reading the metadata provider; here the provider is a map holding the result.")

(defn database
  [metadata-providerable]
  (:database metadata-providerable))
