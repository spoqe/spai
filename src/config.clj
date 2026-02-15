;; spai/config — project config (.spai.edn) and antipatterns
;; Convention-based project scanning.

(defn- find-config
  "Walk up from path looking for .spai.edn. Returns parsed config or nil."
  [start-path]
  (loop [dir (io/file (or start-path "."))]
    (when dir
      (let [cfg (io/file dir ".spai.edn")]
        (if (.exists cfg)
          (try (read-string (slurp cfg))
               (catch Exception e
                 (binding [*out* *err*]
                   (println (str "Warning: failed to parse " (.getPath cfg) ": " (.getMessage e))))
                 nil))
          (recur (.getParentFile dir)))))))

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
      {:error "No antipatterns defined. Add :antipatterns to .spai.edn"
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
