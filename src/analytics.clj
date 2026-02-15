;; spai/analytics — usage logging, stats, reflection
;; Self-awareness. What gets used? What's missing?

(def ^:private log-file
  "Append-only usage log. EDN records, one per line."
  (str (System/getProperty "user.dir") "/.spai/usage.log"))

(def ^:private log-max-lines
  "Keep the most recent N entries. ~15MB at capacity. Revisit after a
   week of real use to see what we actually need."
  100000)

(defn- log-usage!
  "Append a usage record to the log. Truncates to most recent entries when over limit."
  [command args result-summary]
  (try
    (let [f (io/file log-file)]
      (.mkdirs (.getParentFile f))
      (spit log-file
            (str (pr-str {:ts      (str (java.time.Instant/now))
                          :command command
                          :args    (vec args)
                          :result  result-summary})
                 "\n")
            :append true)
      ;; Truncate when 2x over limit (amortised — don't truncate every write)
      (when (.exists f)
        (let [lines (str/split-lines (slurp f))]
          (when (> (count lines) (* 2 log-max-lines))
            (spit f (str (str/join "\n" (take-last log-max-lines lines)) "\n"))))))
    (catch Exception _ nil)))

(defn- read-log
  "Read all usage records from the log."
  []
  (when (.exists (io/file log-file))
    (->> (str/split-lines (slurp log-file))
         (keep (fn [line]
                 (when (seq line)
                   (try (read-string line) (catch Exception _ nil))))))))

(defn stats
  "Usage statistics: what commands get used, how often, on what paths."
  []
  (let [entries (read-log)]
    (if (empty? entries)
      {:message "No usage data yet. Use explore and check back."}
      (let [by-cmd    (->> entries (group-by :command) (map (fn [[k v]] [k (count v)])) (into (sorted-map)))
            by-path   (->> entries (map #(first (:args %))) (remove nil?) frequencies
                           (sort-by val >) (take 10))
            recent    (->> entries (take-last 10) reverse vec)]
        {:total     (count entries)
         :by-command by-cmd
         :top-paths  (vec by-path)
         :recent     recent}))))

(defn- observe
  "Generate observations from usage data."
  [entries]
  (let [cmds         (map :command entries)
        n            (count entries)
        usage-count  (frequencies cmds)
        unique-paths (->> entries (map #(first (:args %))) (remove nil?) distinct count)]
    (vec (remove nil?
      [(when (> (get usage-count "usages" 0) (* 2 (get usage-count "shape" 0)))
         "You use 'usages' much more than 'shape'. Shape might need improvement.")
       (when (< n 5)
         "Too few data points. Keep using the tool and check back.")
       (when (> n 20)
         "Good usage data. Review :top-paths - are there modules you explore repeatedly?")
       (when (and (> n 10) (< unique-paths 3))
         (str "You've explored " unique-paths " unique paths across " n " calls. Narrow focus or missing breadth?"))]))))

(defn reflect
  "Review usage patterns. What's working? What's missing?"
  []
  (let [entries (read-log)
        s       (stats)]
    (if (empty? entries)
      s
      (assoc s :observations (observe entries)))))
