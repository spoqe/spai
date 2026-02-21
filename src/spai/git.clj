(ns spai.git
  "Git history analysis: changes, related, diff, diff-shape, narrative, drift."
  (:require [spai.core :as core]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]))

(defn changes
  "Recent git changes for a path. Shows commits and files touched."
  [path n]
  (let [n   (or n 5)
        raw (core/sh "git" "log" (str "-" n)
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
  (let [;; Normalize: if absolute path, also compute the relative form for matching
        file-basename (.getName (io/file file))
        file-suffix   (when (str/starts-with? file "/")
                        ;; git diff-tree returns repo-relative paths, so strip to match
                        (let [git-root (str/trim (or (core/sh "git" "rev-parse" "--show-toplevel") ""))]
                          (when (and (seq git-root) (str/starts-with? file git-root))
                            (subs file (inc (count git-root))))))
        self?         (fn [f] (or (= f file) (= f file-suffix)
                                  (and file-suffix (str/ends-with? f file-suffix))))
        raw           (core/sh "git" "log" (str "-" n) "--pretty=format:%H" "--" file)]
    (if-not raw
      {:file file :error "No git history found for this file"}
      (let [commits    (->> (str/split-lines raw)
                            (remove str/blank?)
                            vec)
            total      (count commits)
            co-changes (->> commits
                            (mapcat (fn [hash]
                                      (when-let [files-raw (core/sh "git" "diff-tree" "--no-commit-id"
                                                               "-r" "--name-only" hash)]
                                        (let [files (->> (str/split-lines files-raw)
                                                         (remove str/blank?)
                                                         (remove self?))]
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
        raw (core/sh "git" "log" (str "-" n) "--pretty=format:%H|%an|%s|%ar" "--" file)]
    (when raw
      (let [commits (->> (str/split-lines raw)
                         (remove str/blank?)
                         (mapv (fn [line]
                                 (let [parts (str/split line #"\|" 4)]
                                   (when (>= (count parts) 4)
                                     (let [hash (nth parts 0)
                                           diff-raw (core/sh "git" "diff" (str hash "^") hash "--" file)]
                                       {:hash    (subs hash 0 (min 8 (count hash)))
                                        :author  (nth parts 1)
                                        :message (nth parts 2)
                                        :date    (nth parts 3)
                                        :diff    (or diff-raw "(initial commit or merge)")})))))
                         (remove nil?))]
        {:file    file
         :commits commits}))))

;; -------------------------------------------------------------------
;; diff-shape — structural diff between git refs
;; -------------------------------------------------------------------

(defn shape-from-string
  "Extract function/type definitions from a content string.
   Returns {:functions [{:name :text}] :types [{:name :kind :text}]}
   Uses the same patterns as shape-raw but works on strings, not files."
  [content lang]
  (let [pats (get @core/lang-patterns lang)]
    (when pats
      (let [lines (str/split-lines content)
            match-lines (fn [pat-key extract-fn]
                          (when-let [pat-str (get pats pat-key)]
                            (let [pat (re-pattern pat-str)]
                              (->> lines
                                   (keep-indexed
                                     (fn [idx line]
                                       (when (re-find pat line)
                                         (merge {:line (inc idx) :text (str/trim line)}
                                                (extract-fn (str/trim line))))))
                                   vec))))]
        {:functions (or (match-lines :functions
                          (fn [text] {:name (core/extract-fn-name text lang)}))
                        [])
         :types     (or (match-lines :types
                          (fn [text] {:name (core/extract-type-name text)
                                      :kind (core/extract-type-kind text)}))
                        [])}))))

(defn diff-shape
  "Structural diff: which functions/types were added, removed, or changed signature.
   Compares working tree against a git ref (default HEAD~1).
   Single file or directory."
  [path ref]
  (let [ref  (or ref "HEAD~1")
        path (or path ".")
        ;; Compare working tree against ref
        changed-raw (core/sh "git" "diff" "--name-only" ref "--" path)
        changed-files (when changed-raw
                        (->> (str/split-lines changed-raw)
                             (remove str/blank?)
                             (filter #(re-find core/source-exts %))
                             vec))]
    (if (empty? changed-files)
      {:path path :ref ref :changes [] :summary "No structural changes"}
      (let [file-diffs
            (mapv
              (fn [file]
                (let [lang (core/detect-lang file)
                      ;; Content at ref (nil if file didn't exist)
                      old-content (core/sh "git" "show" (str ref ":" file))
                      ;; Content on disk (nil if deleted)
                      new-content (when (.exists (io/file file))
                                    (slurp file))
                      old-shape (when old-content (shape-from-string old-content lang))
                      new-shape (when new-content (shape-from-string new-content lang))

                      ;; --- Functions ---
                      old-fns (set (keep :name (:functions old-shape)))
                      new-fns (set (keep :name (:functions new-shape)))
                      added-fns   (vec (sort (set/difference new-fns old-fns)))
                      removed-fns (vec (sort (set/difference old-fns new-fns)))

                      ;; Signature changes: same name, different text
                      old-fn-sigs (into {} (keep (fn [{:keys [name text]}]
                                                   (when name [name text]))
                                                 (:functions old-shape)))
                      new-fn-sigs (into {} (keep (fn [{:keys [name text]}]
                                                   (when name [name text]))
                                                 (:functions new-shape)))
                      changed-fns (->> (set/intersection old-fns new-fns)
                                       (keep (fn [n]
                                               (let [old-sig (get old-fn-sigs n)
                                                     new-sig (get new-fn-sigs n)]
                                                 (when (not= old-sig new-sig)
                                                   {:name n :old old-sig :new new-sig}))))
                                       (sort-by :name)
                                       vec)

                      ;; --- Types ---
                      old-types (set (keep :name (:types old-shape)))
                      new-types (set (keep :name (:types new-shape)))
                      added-types   (vec (sort (set/difference new-types old-types)))
                      removed-types (vec (sort (set/difference old-types new-types)))

                      has-changes? (or (seq added-fns) (seq removed-fns) (seq changed-fns)
                                       (seq added-types) (seq removed-types))]
                  (when has-changes?
                    (cond-> {:file file}
                      (seq added-fns)   (assoc :added-fns added-fns)
                      (seq removed-fns) (assoc :removed-fns removed-fns)
                      (seq changed-fns) (assoc :changed-fns changed-fns)
                      (seq added-types)   (assoc :added-types added-types)
                      (seq removed-types) (assoc :removed-types removed-types)
                      ;; New file or deleted file marker
                      (nil? old-content)  (assoc :status :new)
                      (nil? new-content)  (assoc :status :deleted)))))
              changed-files)

            changes (vec (remove nil? file-diffs))

            ;; Aggregate summary
            total-added   (reduce + (map #(count (:added-fns % [])) changes))
            total-removed (reduce + (map #(count (:removed-fns % [])) changes))
            total-changed (reduce + (map #(count (:changed-fns % [])) changes))
            total-types+  (reduce + (map #(count (:added-types % [])) changes))
            total-types-  (reduce + (map #(count (:removed-types % [])) changes))]
        {:path     path
         :ref      ref
         :files    (count changed-files)
         :changes  changes
         :summary  (str total-added " added, "
                        total-removed " removed, "
                        total-changed " signature changes"
                        (when (pos? (+ total-types+ total-types-))
                          (str ", " total-types+ " types added, " total-types- " types removed")))}))))

;; -------------------------------------------------------------------
;; narrative — biography of a file
;; -------------------------------------------------------------------

(defn narrative
  "The biography of a file. How it evolved — creation, growth, splits, stabilization.
   Compressed story, not raw git log."
  [file & {:keys [n] :or {n 500}}]
  (let [;; Get commit history with stats
        raw (core/sh "git" "log" (str "-" n)
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

;; -------------------------------------------------------------------
;; drift — implicit vs explicit architecture
;; -------------------------------------------------------------------

(defn- file-imports
  "Extract what modules/files this file imports. Returns set of base names."
  [file]
  (try
    (let [content (slurp file)
          lines   (str/split-lines content)]
      (->> lines
           (keep (fn [line]
                   (or
                     ;; Rust: use crate::foo::bar → bar
                     (when-let [[_ mod] (re-find #"use\s+(?:crate::)?(?:\w+::)*(\w+)" line)]
                       mod)
                     ;; TypeScript: import ... from './foo' → foo
                     (when-let [[_ path] (re-find #"from\s+['\"]\.?\.?/?([^'\"]+)['\"]" line)]
                       (let [parts (str/split path #"/")]
                         (last parts)))
                     ;; Rust: mod foo → foo
                     (when-let [[_ mod] (re-find #"^\s*(?:pub\s+)?mod\s+(\w+)" line)]
                       mod))))
           (map #(str/replace % #"\.\w+$" "")) ;; strip extensions
           set))
    (catch Exception _ #{})))

(defn drift
  "Compare implicit architecture (co-change) with explicit architecture (imports).
   The gap between what the module system claims and what actually co-varies IS drift.

   For each source file in path: what co-changes but isn't imported (hidden coupling)?
   What's imported but never co-changes (dead coupling)?"
  [path & {:keys [n min-pct] :or {n 100 min-pct 15}}]
  (let [root   (io/file (or path "."))
        ;; Find source files
        files  (->> (file-seq root)
                    (filter #(.isFile %))
                    (remove #(some core/skip-dirs (str/split (.getPath %) #"/")))
                    (filter #(re-find core/source-exts (.getName %)))
                    (mapv #(.getPath %)))

        ;; For each file: get co-change partners and import partners
        analyze (fn [file]
                  (let [base      (let [n (.getName (io/file file))]
                                    (if-let [dot (str/last-index-of n ".")]
                                      (subs n 0 dot) n))
                        imports   (file-imports file)
                        ;; Get co-change data (reuse related logic)
                        rel-data  (related file :n n :min-pct min-pct)
                        co-files  (when-not (:error rel-data)
                                    (->> (:related rel-data)
                                         (map (fn [{:keys [file pct]}]
                                                (let [n (.getName (io/file file))]
                                                  {:file file
                                                   :base (if-let [d (str/last-index-of n ".")]
                                                           (subs n 0 d) n)
                                                   :pct  pct})))
                                         vec))
                        co-bases  (set (map :base (or co-files [])))
                        ;; Hidden coupling: co-changes but not imported
                        hidden    (->> (or co-files [])
                                       (remove #(contains? imports (:base %)))
                                       (remove #(= (:base %) base))
                                       vec)
                        ;; Dead coupling: imported but never co-changes
                        dead      (->> imports
                                       (remove #(contains? co-bases %))
                                       (remove #{base})
                                       sort vec)]
                    (when (or (seq hidden) (seq dead))
                      {:file    file
                       :hidden  hidden  ;; co-change without import = implicit coupling
                       :dead    dead    ;; import without co-change = possibly stale
                       :imports (count imports)
                       :co-changes (count (or co-files []))})))

        ;; Only analyze files with git history (skip tiny/new files)
        results (->> files
                     (keep analyze)
                     ;; Sort by most hidden coupling first
                     (sort-by #(- (count (:hidden %))))
                     (take 20)
                     vec)

        ;; Aggregate stats
        total-hidden (->> results (mapcat :hidden) count)
        total-dead   (->> results (map #(count (:dead %))) (reduce +))]

    {:path         (or path ".")
     :files-analyzed (count files)
     :files-with-drift (count results)
     :total-hidden-coupling total-hidden
     :total-dead-coupling total-dead
     :drift        results
     :insight      (when (> total-hidden 0)
                     (let [worst (first results)]
                       (str (:file worst) " has " (count (:hidden worst))
                            " hidden dependencies. The module boundary is lying.")))}))
