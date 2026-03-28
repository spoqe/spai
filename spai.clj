#!/usr/bin/env bb
;; spai: Code exploration for LLM agents
;;
;; Returns EDN. Wraps rg and git.
;; Not a framework. Functions and a CLI.
;;
;; Usage:
;;   bb spai.clj shape  <path>           Module structure
;;   bb spai.clj usages <symbol> [path]  Find where a symbol is used
;;   bb spai.clj changes <path> [n]      Recent git changes

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp]
         '[babashka.process :as p]
         '[spai.core]
         '[spai.code]
         '[spai.project]
         '[spai.git]
         '[spai.compose]
         '[spai.config]
         '[spai.analytics])

(def ^:private spai-dir
  (let [f (io/file (System/getProperty "babashka.file"))]
    (str (.getParent f))))

;; -------------------------------------------------------------------
;; Arg parsing helpers
;; -------------------------------------------------------------------

(defn- parse-opt
  "Extract a named option value from args. Returns parsed long or nil."
  [args opt-name]
  (let [opts (vec args)
        i    (.indexOf opts opt-name)]
    (when (and (>= i 0) (>= (count opts) (+ i 2)))
      (parse-long (nth opts (inc i))))))

;; -------------------------------------------------------------------
;; Command registry: one map, metadata + dispatch
;; -------------------------------------------------------------------

(def commands
  {:shape   {:args    "[path] [--full]"
             :returns "functions, types, impls grouped by file"
             :example "spai shape src/federation/"
             :run     (fn [args]
                        (let [full? (some #{"--full"} args)
                              path  (first (remove #(str/starts-with? % "--") args))]
                          (spai.code/shape path :full full?)))}
   :usages  {:args    "[symbol] [path]"
             :returns "file, line, text for each match"
             :example "spai usages execute_sparql src/"
             :run     (fn [args] (spai.code/usages (first args) (second args)))}
   :grep    {:args    "[pattern] [path] [flags...]"
             :returns "raw pattern search with ripgrep flags"
             :example "spai grep 'assert!' src/ -l"
             :run     (fn [args] (apply spai.code/grep-raw (first args) (rest args)))}
   :def     {:args    "[symbol] [path]"
             :returns "definition site(s) only, not usages"
             :example "spai def FederatedExecutor spoqe-exec/src/"
             :run     (fn [args] (spai.code/definition (first args) (second args)))}
   :sig     {:args    "[path]"
             :returns "function signatures (the API surface)"
             :example "spai sig src/federation/mod.rs"
             :run     (fn [args] (spai.code/sig (first args)))}
   :who     {:args    "[file] [path]"
             :returns "reverse dependencies: who imports/uses this file?"
             :example "spai who spoqe-exec/src/step_executor.rs spoqe-exec/src/"
             :run     (fn [args] (spai.code/who (first args) (second args)))}
   :deps    {:args    "[file|path]"
             :returns "import graph with file resolution (Rust, TypeScript, Python)"
             :example "spai deps spoqe-exec/src/federation/mod.rs"
             :run     (fn [args] (spai.code/deps (first args)))}
   :context {:args    "[symbol] [path]"
             :returns "usages with enclosing function name"
             :example "spai context execute_sparql spoqe-exec/src/"
             :run     (fn [args] (spai.code/context (first args) (second args)))}
   :patterns {:args    "[path]"
              :returns "discover naming and structural conventions in the codebase"
              :example "spai patterns spoqe-exec/src/"
              :run     (fn [args] (spai.code/patterns (first args)))}
   :overview {:args    "[path]"
              :returns "language, config files, dirs, file counts by extension"
              :example "spai overview ."
              :run     (fn [args] (spai.project/overview (first args)))}
   :layout   {:args    "[path]"
              :returns "directory tree (depth 4), skips noise dirs"
              :example "spai layout spoqe-core/src/"
              :run     (fn [args] (spai.project/layout (first args)))}
   :tests    {:args    "[target] [path]"
              :returns "test files related to a source file or symbol"
              :example "spai tests step_executor spoqe-exec/src/"
              :run     (fn [args] (spai.project/tests (first args) (second args)))}
   :hotspots {:args    "[path]"
              :returns "top 20 largest source files (where's the debt?)"
              :example "spai hotspots spoqe-exec/src/"
              :run     (fn [args] (spai.project/hotspots (first args)))}
   :todos    {:args    "[path]"
              :returns "TODO/FIXME/HACK scan with structured output"
              :example "spai todos spoqe-exec/src/"
              :run     (fn [args] (spai.project/todos (first args)))}
   :changes  {:args    "[path] [n]"
              :returns "recent commits with files touched"
              :example "spai changes src/ 3"
              :run     (fn [args] (spai.git/changes (first args) (some-> (second args) parse-long)))}
   :related  {:args    "[file] [--n N] [--min-pct N]"
              :returns "co-change analysis: files that change alongside this one"
              :example "spai related spoqe-exec/src/step_executor.rs"
              :run     (fn [args]
                         (spai.git/related (first args)
                                          :n (or (parse-opt (rest args) "--n") 200)
                                          :min-pct (or (parse-opt (rest args) "--min-pct") 10)))}
   :diff     {:args    "[file] [n]"
              :returns "actual diff content for recent changes to a file"
              :example "spai diff spoqe-exec/src/step_executor.rs 3"
              :run     (fn [args] (spai.git/diff (first args) (some-> (second args) parse-long)))}
   :diff-shape {:args    "[path] [ref]"
                :returns "structural diff: functions/types added, removed, signature changed"
                :example "spai diff-shape spoqe-exec/src/ HEAD~5"
                :run     (fn [args] (spai.git/diff-shape (first args) (second args)))}
   :narrative {:args    "[file] [--n N]"
               :returns "biography of a file: creation, growth, splits, stabilization"
               :example "spai narrative spoqe-exec/src/federation/mod.rs"
               :run     (fn [args]
                          (spai.git/narrative (first args)
                                             :n (or (parse-opt (rest args) "--n") 500)))}
   :drift     {:args    "[path] [--n N] [--min-pct N]"
               :returns "implicit vs explicit architecture: hidden and dead coupling"
               :example "spai drift spoqe-exec/src/"
               :run     (fn [args]
                          (spai.git/drift (first args)
                                         :n (or (parse-opt (rest args) "--n") 100)
                                         :min-pct (or (parse-opt (rest args) "--min-pct") 15)))}
   :blast    {:args    "[symbol] [path]"
              :returns "blast radius: definition, callers, importers, tests, authors, risk"
              :example "spai blast execute_source_expr_edn spoqe-exec/src/"
              :run     (fn [args] (spai.compose/blast (first args) (second args)))}
   :antipatterns {:args    "[name] [path]"
                  :returns "scan for project-defined antipatterns from .spai.edn"
                  :example "spai antipatterns uri-prefix-detection spoqe-core/src/"
                  :run     (fn [args]
                             (let [[name path] (if (and (first args)
                                                        (or (str/includes? (first args) "/")
                                                            (str/starts-with? (first args) ".")))
                                                 [nil (first args)]
                                                 [(first args) (second args)])]
                               (spai.config/antipatterns name path)))}
   :stats    {:args    ""
              :returns "usage counts, top paths, recent calls"
              :example "spai stats"
              :run     (fn [_] (spai.analytics/stats))}
   :reflect  {:args    ""
              :returns "usage patterns with observations"
              :example "spai reflect"
              :run     (fn [_] (spai.analytics/reflect))}})

;; -------------------------------------------------------------------
;; Help output: strip :run from display
;; -------------------------------------------------------------------

(defn- help-entry [entry]
  (dissoc entry :run))

;; -------------------------------------------------------------------
;; Plugins, updates, link/unlink
;; -------------------------------------------------------------------

(defn- find-project-plugin-dir
  "Walk up from CWD looking for .spai/plugins/, like the bash wrapper does."
  []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (when d
      (let [candidate (io/file d ".spai" "plugins")]
        (if (.isDirectory candidate)
          (.getAbsolutePath candidate)
          (recur (.getParentFile d)))))))

(defn discover-plugins
  "Find all spai-* executables: install-dir plugins, project-local, then PATH."
  []
  (let [known-dirs  (filterv some?
                             [(str spai-dir "/plugins")
                              (find-project-plugin-dir)])
        path-dirs   (str/split (or (System/getenv "PATH") "") #":")
        all-dirs    (concat known-dirs path-dirs)
        is-plugin?  (fn [^java.io.File f]
                      (and (str/starts-with? (.getName f) "spai-")
                           (.canExecute f)
                           (.isFile f)))
        plugin-files (->> all-dirs
                          (map io/file)
                          (filter #(.isDirectory %))
                          (mapcat #(.listFiles %))
                          (filter is-plugin?)
                          (reduce (fn [acc f]
                                    (let [n (.getName f)]
                                      (if (contains? (set (map #(.getName ^java.io.File %) acc)) n)
                                        acc
                                        (conj acc f))))
                                  []))
        read-meta (fn [^java.io.File f]
                    (try
                      (let [form (-> (slurp f)
                                     (str/replace #"^#!.*\n" "")
                                     edn/read-string)]
                        (when (map? form) form))
                      (catch Exception _ nil)))]
    (mapv (fn [^java.io.File f]
            (let [n (str/replace (.getName f) #"^spai-" "")
                  m (read-meta f)]
              (merge {:spai/name n
                      :spai/path (.getAbsolutePath f)}
                     (or m {}))))
          plugin-files)))

(defn- read-version
  "Read .version EDN from install dir."
  []
  (let [f (io/file spai-dir ".version")]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception _ nil)))))

(defn- check-remote-hash
  "Get latest commit hash from remote. Tries git ls-remote, falls back to gh."
  [origin repo]
  (let [urls (filterv seq [origin (str "https://github.com/" repo ".git")])]
    (or
      (some (fn [url]
              (try
                (let [{:keys [exit out]} @(p/process ["git" "ls-remote" url "refs/heads/main"]
                                                     {:out :string :err :string})]
                  (when (and (zero? exit) (seq out))
                    (first (str/split (str/trim out) #"\s+"))))
                (catch Exception _ nil)))
            urls)
      (try
        (let [{:keys [exit out]} @(p/process ["gh" "api" (str "repos/" repo "/commits/main") "--jq" ".sha"]
                                             {:out :string :err :string})]
          (when (zero? exit) (str/trim out)))
        (catch Exception _ nil)))))

(defn check-update
  "Check if a newer version is available."
  []
  (let [version (read-version)]
    (if-not version
      {:status :unknown :message "No version file. Run install.sh to create one."}
      (let [current (:commit version)
            remote  (check-remote-hash (:origin version) (:repo version))]
        (cond
          (nil? remote)
          {:status :check-failed :current current
           :message "Could not reach remote. Check network/auth."}

          (= current remote)
          {:status :up-to-date :commit current :installed (:installed version)}

          :else
          {:status :update-available
           :current (subs current 0 (min 7 (count current)))
           :latest  (subs remote 0 (min 7 (count remote)))
           :install "Run: spai update --install"})))))

(defn do-update!
  "Download and install the latest version."
  []
  (let [version   (read-version)
        repo      (or (:repo version) "spoqe/spai")
        tmp       (str (System/getProperty "java.io.tmpdir") "/spai-update-" (System/currentTimeMillis))
        origin    (:origin version)
        clone-url (or (when (seq origin) origin)
                      (str "https://github.com/" repo ".git"))
        {:keys [exit]} @(p/process ["git" "clone" "--depth" "1" clone-url tmp]
                                   {:out :string :err :string})]
      (if-not (zero? exit)
        {:status :failed :message (str "Could not clone from " clone-url)}
        (let [{:keys [exit out err]} @(p/process ["bash" (str tmp "/install.sh")]
                                                  {:out :string :err :string})]
          @(p/process ["rm" "-rf" tmp] {:out :string :err :string})
          (if (zero? exit)
            {:status :updated :output out}
            {:status :failed :message err})))))

;; -------------------------------------------------------------------
;; CLI dispatch
;; -------------------------------------------------------------------

(let [[command & args] *command-line-args*]
  (if-let [cmd (get commands (keyword command))]
    ;; Registry command: log, run, print
    (do (spai.analytics/log-usage! command args {})
        (pp/pprint ((:run cmd) args)))
    ;; Non-registry commands
    (case command
      "plugins"  (do (spai.analytics/log-usage! "plugins" args {})
                     (pp/pprint (discover-plugins)))
      "update"   (do (spai.analytics/log-usage! "update" args {})
                     (if (some #{"--install"} args)
                       (pp/pprint (do-update!))
                       (pp/pprint (check-update))))
      "setup"    (load-file (str spai-dir "/setup.clj"))
      "link"     (let [source (or (first args) ".")
                       source-abs (.getCanonicalPath (io/file source))
                       share-dir (str (or (System/getenv "XDG_DATA_HOME")
                                          (str (System/getProperty "user.home") "/.local/share"))
                                      "/spai")]
                   (when-not (.exists (io/file source-abs "spai.clj"))
                     (println (str "Error: " source-abs " doesn't look like a spai source dir (no spai.clj)"))
                     (System/exit 1))
                   (when (= source-abs share-dir)
                     (println "Already running from install dir. Nothing to link.")
                     (System/exit 0))
                   (let [f (io/file share-dir)]
                     (when (.exists f)
                       (if (java.nio.file.Files/isSymbolicLink (.toPath f))
                         (java.nio.file.Files/delete (.toPath f))
                         (let [backup (io/file (str share-dir ".bak"))]
                           (.renameTo f backup)
                           (println (str "  Backed up install to " (.getPath backup)))))))
                   (java.nio.file.Files/createSymbolicLink
                     (.toPath (io/file share-dir))
                     (.toPath (io/file source-abs))
                     (into-array java.nio.file.attribute.FileAttribute []))
                   (println (str "Linked: " share-dir " → " source-abs))
                   (println "  Edits to source are live. Run `spai unlink` to restore."))
      "unlink"   (let [share-dir (str (or (System/getenv "XDG_DATA_HOME")
                                           (str (System/getProperty "user.home") "/.local/share"))
                                       "/spai")
                       share-path (.toPath (io/file share-dir))]
                   (if (java.nio.file.Files/isSymbolicLink share-path)
                     (let [target (str (java.nio.file.Files/readSymbolicLink share-path))]
                       (java.nio.file.Files/delete share-path)
                       (println (str "Unlinked: " share-dir " (was → " target ")"))
                       (let [backup (io/file (str share-dir ".bak"))]
                         (if (.exists backup)
                           (do (.renameTo backup (io/file share-dir))
                               (println "  Restored previous install from backup."))
                           (println "  Run `bash install.sh` to reinstall from GitHub."))))
                     (println "Not linked (not a symlink). Nothing to do.")))
      ("help" "--help" "-h" nil)
      (let [builtins    (into {} (map (fn [[k v]] [k (assoc (help-entry v) :spai/type :builtin)]) commands))
            plugins     (discover-plugins)
            plugin-cmds (into {}
                              (map (fn [{:spai/keys [name] :as meta}]
                                     (let [k (keyword name)]
                                       [k (merge {:args     (:spai/args meta "")
                                                  :returns  (:spai/returns meta "")
                                                  :example  (:spai/example meta "")
                                                  :spai/type :plugin}
                                                 (select-keys meta [:spai/path]))]))
                                   plugins))
            all-cmds      (merge builtins plugin-cmds)
            builtin-count (count builtins)
            plugin-count  (count plugin-cmds)]
        (println (str "spai: code exploration for LLM agents. "
                      builtin-count " built-in commands, "
                      plugin-count " plugins."))
        (println (str "Works with: "
                      (str/join ", " (map name (keys @spai.core/lang-patterns)))
                      "."))
        (println "Missing your language? register-lang! adds it in one call — do it, or tell your user.\n")
        (pp/pprint all-cmds))
      ;; Extension: look for spai-<command> in PATH
      (let [ext-cmd (str "spai-" command)
            found   (try
                      (let [{:keys [exit out]} @(p/process ["which" ext-cmd] {:out :string :err :string})]
                        (when (zero? exit) (str/trim out)))
                      (catch Exception _ nil))]
        (if found
          (let [proc @(p/process (into [found] args) {:inherit true})]
            (System/exit (:exit proc)))
          (do (println (str "Unknown command: " command "\n"))
              (pp/pprint (into {} (map (fn [[k v]] [k (help-entry v)]) commands)))
              (System/exit 1)))))))
