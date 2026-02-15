;; spai/git — changes, related, diff, narrative
;; Git history analysis commands.

(defn changes
  "Recent git changes for a path. Shows commits and files touched."
  [path n]
  (let [n   (or n 5)
        raw (sh "git" "log" (str "-" n)
                 "--pretty=format:%H|%an|%s|%ar"
                 "--name-only" "--" path)]
    (when raw
      (let [commits (->> (str/split raw #"\n\n")
                         (keep (fn [block]
                                 (let [lines (remove str/blank? (str/split-lines block))]
                                   (when (seq lines)
                                     (let [parts (str/split (first lines) #"\|" 4)]
                                       (when (>= (count parts) 4)
                                         {:hash    (subs (nth parts 0) 0 (min 8 (count (nth parts 0))))
                                          :author  (nth parts 1)
                                          :message (nth parts 2)
                                          :date    (nth parts 3)
                                          :files   (vec (rest lines))}))))))
                         vec)]
        {:path    path
         :commits commits}))))

(defn related
  "Co-change analysis: what files change alongside this one?
   Discovers implicit coupling that the module system doesn't show.
   The hidden graph — gears that mesh."
  [file & {:keys [n min-pct] :or {n 200 min-pct 10}}]
  (let [raw (sh "git" "log" (str "-" n) "--pretty=format:%H" "--" file)]
    (if-not raw
      {:file file :error "No git history found for this file"}
      (let [commits    (->> (str/split-lines raw)
                            (remove str/blank?)
                            vec)
            total      (count commits)
            co-changes (->> commits
                            (mapcat (fn [hash]
                                      (when-let [files-raw (sh "git" "diff-tree" "--no-commit-id"
                                                               "-r" "--name-only" hash)]
                                        (let [files (->> (str/split-lines files-raw)
                                                         (remove str/blank?)
                                                         (remove #{file}))]
                                          files))))
                            frequencies)
            ranked     (->> co-changes
                            (map (fn [[f count]]
                                   {:file    f
                                    :commits count
                                    :pct     (Math/round (* 100.0 (/ count total)))}))
                            (filter #(>= (:pct %) min-pct))
                            (sort-by :commits >)
                            (take 25)
                            vec)
            by-dir     (->> ranked
                            (group-by (fn [{:keys [file]}]
                                        (let [parts (str/split file #"/")]
                                          (if (> (count parts) 1)
                                            (str/join "/" (butlast parts))
                                            "."))))
                            (map (fn [[dir files]]
                                   {:dir   dir
                                    :files (mapv #(select-keys % [:file :pct]) files)
                                    :avg-pct (Math/round (double (/ (reduce + (map :pct files))
                                                                    (count files))))}))
                            (sort-by :avg-pct >)
                            vec)]
        {:file       file
         :commits    total
         :related    ranked
         :by-dir     by-dir
         :insight    (let [top (first ranked)]
                       (when (and top (>= (:pct top) 50))
                         (str (:file top) " co-changes " (:pct top)
                              "% of the time. Consider: are they coupled, or should they be merged?")))}))))

;; -------------------------------------------------------------------
;; diff — actual diff content for recent changes
;; -------------------------------------------------------------------

(defn diff
  "Actual diff content for recent changes to a file. What changed, not just that it changed."
  [file n]
  (let [n   (or n 3)
        raw (sh "git" "log" (str "-" n) "--pretty=format:%H|%an|%s|%ar" "--" file)]
    (when raw
      (let [commits (->> (str/split-lines raw)
                         (remove str/blank?)
                         (mapv (fn [line]
                                 (let [parts (str/split line #"\|" 4)]
                                   (when (>= (count parts) 4)
                                     (let [hash (nth parts 0)
                                           diff-raw (sh "git" "diff" (str hash "^") hash "--" file)]
                                       {:hash    (subs hash 0 (min 8 (count hash)))
                                        :author  (nth parts 1)
                                        :message (nth parts 2)
                                        :date    (nth parts 3)
                                        :diff    (or diff-raw "(initial commit or merge)")})))))
                         (remove nil?))]
        {:file    file
         :commits commits}))))

;; -------------------------------------------------------------------
;; narrative — biography of a file
;; -------------------------------------------------------------------

(defn narrative
  "The biography of a file. How it evolved — creation, growth, splits, stabilization.
   Compressed story, not raw git log."
  [file & {:keys [n] :or {n 500}}]
  (let [;; Get commit history with stats
        raw (sh "git" "log" (str "-" n)
                "--pretty=format:%H|%an|%s|%aI"
                "--numstat" "--" file)]
    (if-not raw
      {:file file :error "No git history found for this file"}
      (let [;; Parse commits with their +/- stats
            blocks  (->> (str/split raw #"\n\n")
                         (keep (fn [block]
                                 (let [lines (remove str/blank? (str/split-lines block))]
                                   (when (seq lines)
                                     (let [parts (str/split (first lines) #"\|" 4)]
                                       (when (>= (count parts) 4)
                                         (let [stat-lines (rest lines)
                                               ;; numstat format: added\tremoved\tfile
                                               stats (->> stat-lines
                                                          (keep (fn [l]
                                                                  (let [ps (str/split l #"\t")]
                                                                    (when (>= (count ps) 3)
                                                                      {:added   (parse-long (nth ps 0))
                                                                       :removed (parse-long (nth ps 1))}))))
                                                          first)]
                                           (merge
                                             {:hash    (subs (nth parts 0) 0 (min 8 (count (nth parts 0))))
                                              :author  (nth parts 1)
                                              :message (nth parts 2)
                                              :date    (nth parts 3)}
                                             (when stats
                                               {:added   (:added stats)
                                                :removed (:removed stats)
                                                :delta   (- (or (:added stats) 0) (or (:removed stats) 0))}))))))))))
            commits (vec (reverse blocks)) ;; oldest first
            total   (count commits)

            ;; Classify each commit into a phase
            classify (fn [{:keys [added removed delta message] :as c}]
                       (let [added   (or added 0)
                             removed (or removed 0)]
                         (cond
                           ;; First commit = creation
                           (= c (first commits))          :created
                           ;; Large removal with small addition = split/extract
                           (and (> removed 50)
                                (< added (/ removed 2)))  :split
                           ;; Large addition = growth
                           (> added 100)                  :growth
                           ;; Rename/move (all removed, all added, similar size)
                           (and (> removed 50) (> added 50)
                                (< (abs delta) (/ (max added removed) 4))) :restructure
                           ;; Bug fix signals
                           (re-find #"(?i)(fix|bug|patch|hotfix)" (or message "")) :fix
                           ;; Refactor signals
                           (re-find #"(?i)(refactor|clean|simplif|extract|split)" (or message "")) :refactor
                           ;; Small change
                           (< (+ added removed) 20)      :tweak
                           :else                          :evolve)))

            phases  (mapv #(assoc % :phase (classify %)) commits)

            ;; Compute era summary: group consecutive same-phase commits
            eras    (->> phases
                         (partition-by :phase)
                         (mapv (fn [group]
                                 {:phase    (:phase (first group))
                                  :commits  (count group)
                                  :span     [(:date (first group)) (:date (last group))]
                                  :total-delta (reduce + (map #(or (:delta %) 0) group))
                                  :messages (mapv :message group)})))

            ;; Current size
            current-lines (when (.exists (io/file file))
                            (count (str/split-lines (slurp file))))

            ;; Top authors
            authors (->> commits
                         (group-by :author)
                         (map (fn [[a cs]] {:author a :commits (count cs)}))
                         (sort-by :commits >)
                         vec)]
        {:file          file
         :total-commits total
         :current-lines current-lines
         :authors       authors
         :eras          eras
         :arc           (mapv :phase phases)
         ;; One-line summary
         :summary       (let [phase-counts (frequencies (map :phase phases))]
                          (str total " commits. "
                               (when-let [g (:growth phase-counts)] (str g " growth phases. "))
                               (when-let [f (:fix phase-counts)] (str f " fixes. "))
                               (when-let [r (:refactor phase-counts)] (str r " refactors. "))
                               (when-let [s (:split phase-counts)] (str s " splits. "))
                               (when current-lines (str "Currently " current-lines " lines."))))}))))
