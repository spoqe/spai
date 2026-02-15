;; spai/config — project config (.spai/config.edn) and antipatterns
;; Convention-based project scanning.

(defn- try-read-config
  "Try to read and parse an EDN config file. Returns parsed value or nil."
  [^java.io.File f]
  (when (.exists f)
    (try (read-string (slurp f))
         (catch Exception e
           (binding [*out* *err*]
             (println (str "Warning: failed to parse " (.getPath f) ": " (.getMessage e))))
           nil))))

(defn- find-config
  "Walk up from path looking for .spai/config.edn (preferred) or .spai.edn (legacy).
   Returns parsed config or nil."
  [start-path]
  (loop [dir (io/file (or start-path "."))]
    (when dir
      (or (try-read-config (io/file dir ".spai" "config.edn"))
          (try-read-config (io/file dir ".spai.edn"))
          (recur (.getParentFile dir))))))

(def ^:private project-config
  "Lazily loaded project config. Found once, cached."
  (delay (find-config ".")))

(defn antipatterns
  "Scan for known antipatterns defined in .spai.edn.
   If name is given, run only that antipattern. Otherwise run all."
  [name path]
  (let [config @project-config
        pats   (:antipatterns config)]
    (if (empty? pats)
      {:error "No antipatterns defined. Add :antipatterns to .spai/config.edn"
       :hint  "See spai/README.md for config format."}
      (let [selected (if (and name (seq name))
                       (if-let [p (get pats (keyword name))]
                         {(keyword name) p}
                         {:error (str "Unknown antipattern: " name
                                      ". Available: " (str/join ", " (map clojure.core/name (keys pats))))})
                       pats)]
        (if (:error selected)
          selected
          (let [path    (or path ".")
                results (into {}
                          (map (fn [[k v]]
                                 (let [patterns (:patterns v)
                                       exclude  (set (:exclude v))
                                       raw      (->> patterns
                                                      (mapcat #(grepf % path "-F"))
                                                      (remove (fn [h]
                                                                (some #(str/includes? (:file h) %) exclude))))
                                       ;; Dedup: same file+line matched by multiple patterns
                                       seen     (volatile! #{})
                                       hits     (->> raw
                                                      (filter (fn [h]
                                                                (let [k [(:file h) (:line h)]]
                                                                  (when-not (@seen k)
                                                                    (vswap! seen conj k)
                                                                    true))))
                                                      (sort-by (juxt :file :line))
                                                      vec)]
                                   [k {:description (:description v)
                                       :severity    (:severity v)
                                       :count       (count hits)
                                       :hits        hits}])))
                          selected)]
            {:path         path
             :antipatterns results
             :total        (reduce + (map (comp :count val) results))}))))))
