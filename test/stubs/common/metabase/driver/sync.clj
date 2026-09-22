(ns metabase.driver.sync
  "Stub for the host's schema-filter helpers, so the driver compiles outside a real Metabase.

   `db-details->schema-filter-patterns` and `include-schema?` keep the same names and arities in
   every version the driver targets, verified against `release-x.50.x` through `release-x.63.x`,
   which is why they need no `version-sensitive-methods` row. Their bodies did change -- 0.59
   reads `driver.conn/effective-details` rather than `:details`, and 0.63 adds a workspace-schema
   gate -- so this namespace models the call contract, not the host.

   Scope of the imitation: exact-literal names and `*` as a wildcard, comma-separated. It does NOT
   reproduce Metabase's real pattern semantics, which leak regex metacharacters through
   unquoted, treat a blank pattern as absent, and never match a nil schema name. The driver only
   passes patterns through, so the difference cannot hide a driver bug -- but do not add an
   assertion about pattern semantics here, it would pin this double's behaviour rather than
   Metabase's."
  (:require
   [clojure.string :as str])
  (:import
   (java.util.regex Pattern)))

(defn db-details->schema-filter-patterns
  "Returns [inclusion-patterns exclusion-patterns] from the connection's details."
  [prop-nm database]
  (when prop-nm
    (let [details (:details database)]
      (case (get details (keyword (str prop-nm "-type")))
        "exclusion" [nil (get details (keyword (str prop-nm "-patterns")))]
        "inclusion" [(get details (keyword (str prop-nm "-patterns"))) nil]
        [nil nil]))))

(defn- pattern->re
  [pattern]
  (->> (str/split pattern #"\*" -1)
       (map #(Pattern/quote %))
       (str/join ".*")
       re-pattern))

(defn- matches?
  [patterns schema-name]
  (boolean
   (some #(re-matches (pattern->re (str/trim %)) (str schema-name))
         (str/split (str patterns) #","))))

(defn include-schema?
  "True when `schema-name` survives the inclusion/exclusion patterns."
  [inclusion-patterns exclusion-patterns schema-name]
  (cond
    (seq inclusion-patterns) (matches? inclusion-patterns schema-name)
    (seq exclusion-patterns) (not (matches? exclusion-patterns schema-name))
    :else                    true))
