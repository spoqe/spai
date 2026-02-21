(ns spai.analytics
  "Usage logging, stats, reflection.
   Self-awareness. What gets used? What's missing?"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def ^:private log-file
  "Append-only usage log. EDN records, one per line.
   Lives in XDG_DATA_HOME (typically ~/.local/share/spai/)."
  (str (or (System/getenv "XDG_DATA_HOME")
           (str (System/getProperty "user.home") "/.local/share"))
       "/spai/usage.log"))

(def ^:private claude-log-file
  "JSONL log of Claude tool calls, written by the PreToolUse hook.
   Same directory as usage.log."
  (str (or (System/getenv "XDG_DATA_HOME")
           (str (System/getProperty "user.home") "/.local/share"))
       "/spai/claude-tools.log"))

(def ^:private log-max-lines
  "Keep the most recent N entries. ~15MB at capacity. Revisit after a
   week of real use to see what we actually need."
  100000)

(defn log-usage!
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

(defn- read-claude-log
  "Read Claude tool call log (JSONL from PreToolUse hook).
   Normalizes to same shape as usage.log entries plus :source :claude."
  []
  (when (.exists (io/file claude-log-file))
    (->> (str/split-lines (slurp claude-log-file))
         (keep (fn [line]
                 (when (seq line)
                   (try
                     (let [m (json/parse-string line true)]
                       (cond-> {:ts      (:ts m)
                                :command (:tool m)
                                :args    (when (seq (:key m)) [(:key m)])
                                :source  :claude}
                         (seq (:sid m)) (assoc :session (:sid m))))
                     (catch Exception _ nil))))))))

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

;; -------------------------------------------------------------------
;; Plugin discovery
;; -------------------------------------------------------------------

(defn discover-plugins
  "Find plugins on PATH (project + user). Returns seq of {:name :path :scope}."
  []
  (let [path-dirs (str/split (or (System/getenv "PATH") "") #":")
        cwd       (System/getProperty "user.dir")
        is-plugin? (fn [^java.io.File f]
                     (and (str/starts-with? (.getName f) "spai-")
                          (.canExecute f)
                          (.isFile f)))]
    (->> path-dirs
         (map io/file)
         (filter #(.isDirectory ^java.io.File %))
         (mapcat #(.listFiles ^java.io.File %))
         (filter is-plugin?)
         (reduce (fn [acc ^java.io.File f]
                   (let [n (.getName f)]
                     (if (contains? (set (map :file-name acc)) n)
                       acc
                       (conj acc {:name      (str/replace n #"^spai-" "")
                                  :path      (.getAbsolutePath f)
                                  :file-name n
                                  :scope     (if (str/includes? (.getAbsolutePath f) ".spai/plugins")
                                               :project :user)}))))
                 [])
         (mapv #(dissoc % :file-name)))))

;; -------------------------------------------------------------------
;; Sequence detection
;; -------------------------------------------------------------------

(defn- extract-sessions
  "Group log entries into sessions. Uses :session ID when available (from
   claude-tools.log), falls back to time-gap heuristic (>10 min) for entries
   without session IDs."
  [entries]
  (when (seq entries)
    (let [gap-ms (* 10 60 1000)]
      (->> entries
           (reduce
             (fn [sessions entry]
               (let [sid (:session entry)
                     ts  (try (java.time.Instant/parse (:ts entry)) (catch Exception _ nil))
                     cur (peek sessions)
                     cur-sid (:session (meta cur))]
                 (if (or (empty? sessions)
                         ;; Session ID changed (both have IDs but different)
                         (and sid cur-sid (not= sid cur-sid))
                         ;; No session IDs — fall back to time gap
                         (and (nil? sid) (nil? cur-sid) ts
                              (let [last-ts (:last-ts (meta cur))]
                                (and last-ts
                                     (> (.toEpochMilli ^java.time.Instant ts)
                                        (+ (.toEpochMilli ^java.time.Instant last-ts) gap-ms))))))
                   ;; New session
                   (conj sessions (with-meta [entry] {:last-ts ts :session sid}))
                   ;; Same session
                   (let [updated (conj cur entry)]
                     (conj (pop sessions) (with-meta updated {:last-ts ts :session (or sid cur-sid)}))))))
             [])))))

(defn- command-signature
  "Abstract a log entry to its command shape (command + path-like first arg)."
  [entry]
  (let [cmd  (:command entry)
        arg1 (first (:args entry))]
    (if arg1
      (str cmd " " (last (str/split (str arg1) #"/")))
      cmd)))

(defn- detect-sequences
  "Find command sequences that repeat across sessions.
   Returns seq of {:sequence [...] :count N :sessions N}."
  [entries]
  (let [sessions (extract-sessions entries)]
    (when (> (count sessions) 1)
      (let [;; Extract 2-4 length subsequences from each session
            extract-ngrams (fn [session n]
                             (when (>= (count session) n)
                               (->> (partition n 1 session)
                                    (mapv (fn [gram] (mapv command-signature gram))))))
            ;; Collect all 2-3 grams across sessions
            all-grams (for [session sessions
                            n       [2 3]
                            gram    (or (extract-ngrams session n) [])]
                        {:gram gram :session-idx (.indexOf ^clojure.lang.PersistentVector (vec sessions) session)})
            ;; Group by gram, count unique sessions
            by-gram (->> all-grams
                         (group-by :gram)
                         (map (fn [[gram hits]]
                                {:sequence gram
                                 :count    (count hits)
                                 :sessions (count (distinct (map :session-idx hits)))}))
                         (filter #(>= (:sessions %) 2))
                         (sort-by :count >)
                         (take 5))]
        (when (seq by-gram)
          (vec by-gram))))))

;; -------------------------------------------------------------------
;; Project-aware path analysis
;; -------------------------------------------------------------------

(defn- project-paths
  "Filter entries to current project directory, extract explored paths."
  [entries]
  (let [cwd (System/getProperty "user.dir")]
    (->> entries
         (filter (fn [e]
                   (some (fn [arg]
                           (and (string? arg)
                                (or (str/starts-with? arg cwd)
                                    (not (str/starts-with? arg "/")))))
                         (:args e))))
         (mapv (fn [e]
                 {:command (:command e)
                  :path    (first (:args e))
                  :ts      (:ts e)})))))

;; -------------------------------------------------------------------
;; Reflect (rewritten)
;; -------------------------------------------------------------------

(defn reflect
  "Session-start briefing. Plugins, patterns, exploration history.
   Merges spai usage.log with Claude tool call log for full chain visibility."
  []
  (let [spai-entries   (read-log)
        claude-entries (read-claude-log)
        ;; Merge both logs sorted by timestamp for interleaved chain detection
        all-entries    (->> (concat
                             (map #(assoc % :source :spai) spai-entries)
                             claude-entries)
                           (sort-by :ts))
        plugins        (discover-plugins)
        ;; Sequence detection on the merged stream
        sequences      (when (seq all-entries) (detect-sequences all-entries))
        ;; Project paths from spai log (claude log paths handled separately)
        proj-paths     (when (seq spai-entries) (project-paths spai-entries))
        ;; spai command mix
        spai-cmds      (when (seq proj-paths)
                         (->> proj-paths
                              (map :command)
                              frequencies
                              (sort-by val >)
                              vec))
        ;; Claude tool mix
        claude-cmds    (when (seq claude-entries)
                         (->> claude-entries
                              (map :command)
                              frequencies
                              (sort-by val >)
                              vec))
        ;; Most-explored paths (spai)
        explored       (when (seq proj-paths)
                         (->> proj-paths
                              (map :path)
                              frequencies
                              (sort-by val >)
                              (take 10)
                              vec))]
    (cond-> {}
      ;; Always show plugins first — most actionable
      (seq plugins)
      (assoc :plugins plugins)

      (empty? plugins)
      (assoc :plugins "None. Run: spai new-plugin <name> [project|user]")

      ;; Repeated sequences from merged stream — cross-tool chains
      (seq sequences)
      (assoc :repeated-sequences sequences)

      ;; Project exploration map
      (seq explored)
      (assoc :explored-paths explored)

      ;; spai command mix
      (seq spai-cmds)
      (assoc :spai-commands spai-cmds)

      ;; Claude tool mix
      (seq claude-cmds)
      (assoc :claude-tools claude-cmds)

      ;; Total stats
      true
      (assoc :total-calls {:spai   (count spai-entries)
                           :claude (count claude-entries)}))))
