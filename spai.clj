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
         '[babashka.process :as p])

;; -------------------------------------------------------------------
;; Load modules
;; -------------------------------------------------------------------

(def ^:private spai-dir
  (let [f (io/file (System/getProperty "babashka.file"))]
    (str (.getParent f))))

(load-file (str spai-dir "/src/core.clj"))
(load-file (str spai-dir "/src/code.clj"))
(load-file (str spai-dir "/src/project.clj"))
(load-file (str spai-dir "/src/git.clj"))
(load-file (str spai-dir "/src/compose.clj"))
(load-file (str spai-dir "/src/config.clj"))
(load-file (str spai-dir "/src/analytics.clj"))

;; -------------------------------------------------------------------
;; CLI
;; -------------------------------------------------------------------

(def commands
  {:shape   {:args     "[path] [--full]"
             :returns  "functions, types, impls grouped by file"
             :example  "spai shape src/federation/"}
   :usages  {:args     "[symbol] [path]"
             :returns  "file, line, text for each match"
             :example  "spai usages process_query src/"}
   :grep    {:args     "[pattern] [path] [flags...]"
             :returns  "raw pattern search with ripgrep flags"
             :example  "spai grep 'assert!' src/ -l"}
   :def     {:args     "[symbol] [path]"
             :returns  "definition site(s) only, not usages"
             :example  "spai def MyService my-crate/src/"}
   :sig     {:args     "[path]"
             :returns  "function signatures (the API surface)"
             :example  "spai sig src/service/mod.rs"}
   :who     {:args     "[file] [path]"
             :returns  "reverse dependencies: who imports/uses this file?"
             :example  "spai who my-crate/src/processor.rs my-crate/src/"}
   :deps    {:args     "[file|path]"
             :returns  "import graph with file resolution (Rust, TypeScript, Python)"
             :example  "spai deps my-crate/src/service/mod.rs"}
   :context {:args     "[symbol] [path]"
             :returns  "usages with enclosing function name"
             :example  "spai context process_query my-crate/src/"}
   :overview {:args    "[path]"
              :returns "language, config files, dirs, file counts by extension"
              :example "spai overview ."}
   :layout   {:args    "[path]"
              :returns "directory tree (depth 4), skips noise dirs"
              :example "spai layout spoqe-core/src/"}
   :tests    {:args    "[target] [path]"
              :returns "test files related to a source file or symbol"
              :example "spai tests processor my-crate/src/"}
   :hotspots {:args    "[path]"
              :returns "top 20 largest source files (where's the debt?)"
              :example "spai hotspots my-crate/src/"}
   :todos    {:args    "[path]"
              :returns "TODO/FIXME/HACK scan with structured output"
              :example "spai todos my-crate/src/"}
   :related  {:args    "[file] [--n N] [--min-pct N]"
              :returns "co-change analysis: files that change alongside this one"
              :example "spai related my-crate/src/processor.rs"}
   :diff     {:args    "[file] [n]"
              :returns "actual diff content for recent changes to a file"
              :example "spai diff my-crate/src/processor.rs 3"}
   :diff-shape {:args    "[path] [ref]"
                :returns "structural diff: functions/types added, removed, signature changed"
                :example "spai diff-shape my-crate/src/ HEAD~5"}
   :narrative {:args   "[file] [--n N]"
               :returns "biography of a file: creation, growth, splits, stabilization"
               :example "spai narrative my-crate/src/service/mod.rs"}
   :drift     {:args    "[path] [--n N] [--min-pct N]"
               :returns "implicit vs explicit architecture: hidden and dead coupling"
               :example "spai drift my-crate/src/"}
   :blast    {:args    "[symbol] [path]"
              :returns "blast radius: definition, callers, importers, tests, authors, risk"
              :example "spai blast process_request my-crate/src/"}
   :patterns  {:args    "[path]"
               :returns "discover naming and structural conventions in the codebase"
               :example "spai patterns my-crate/src/"}
   :changes      {:args    "[path] [n]"
                  :returns "recent commits with files touched"
                  :example "spai changes src/ 3"}
   :antipatterns {:args    "[name] [path]"
                  :returns "scan for project-defined antipatterns from .spai.edn"
                  :example "spai antipatterns uri-prefix-detection spoqe-core/src/"}
   :stats    {:args    ""
              :returns "usage counts, top paths, recent calls"
              :example "spai stats"}
   :reflect  {:args    ""
              :returns "usage patterns with observations"
              :example "spai reflect"}
   :plugins  {:args    ""
              :returns "discovered plugins with DOAP metadata (if present)"
              :example "spai plugins"}
   :update   {:args    "[--install]"
              :returns "check for updates, optionally install"
              :example "spai update"}})

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
  (let [;; Known plugin dirs (mirror what the bash wrapper prepends to PATH)
        ;; 1. Install-dir sibling: spai-dir/plugins
        ;; 2. Project-local: walk up from CWD for .spai/plugins/
        known-dirs  (filterv some?
                             [(str spai-dir "/plugins")
                              (find-project-plugin-dir)])
        path-dirs   (str/split (or (System/getenv "PATH") "") #":")
        ;; Known dirs first (higher priority), then PATH
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
                          ;; Dedupe by name (first found wins — known dirs take priority)
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
      ;; Try each URL with git ls-remote
      (some (fn [url]
              (try
                (let [{:keys [exit out]} @(p/process ["git" "ls-remote" url "refs/heads/main"]
                                                     {:out :string :err :string})]
                  (when (and (zero? exit) (seq out))
                    (first (str/split (str/trim out) #"\s+"))))
                (catch Exception _ nil)))
            urls)
      ;; Fall back to gh api
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
  (let [version (read-version)
        repo    (or (:repo version) "Semantic-partners/spai")
        tmp     (str (System/getProperty "java.io.tmpdir") "/spai-update-" (System/currentTimeMillis))
        origin  (:origin version)]
    (let [clone-url (or (when (seq origin) origin)
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
            {:status :failed :message err}))))))

(let [[command & args] *command-line-args*]
  (case command
    "shape"   (let [full? (some #{"--full"} args)
                     path (first (remove #(str/starts-with? % "--") args))]
                 (log-usage! "shape" args {:path path :full (boolean full?)})
                 (pp/pprint (shape path :full full?)))
    "usages"  (do (log-usage! "usages" args {:symbol (first args) :path (second args)})
                  (pp/pprint (usages (first args) (second args))))
    "grep"    (let [[pattern & rest-args] args]
                (log-usage! "grep" args {:pattern pattern})
                (pp/pprint (apply grep-raw pattern rest-args)))
    "def"     (do (log-usage! "def" args {:symbol (first args) :path (second args)})
                  (pp/pprint (definition (first args) (second args))))
    "sig"     (do (log-usage! "sig" args {:path (first args)})
                  (pp/pprint (sig (first args))))
    "who"     (do (log-usage! "who" args {:file (first args) :path (second args)})
                  (pp/pprint (who (first args) (second args))))
    "deps"    (do (log-usage! "deps" args {:path (first args)})
                  (pp/pprint (deps (first args))))
    "context" (do (log-usage! "context" args {:symbol (first args) :path (second args)})
                  (pp/pprint (context (first args) (second args))))
    "overview" (do (log-usage! "overview" args {:path (first args)})
                   (pp/pprint (overview (first args))))
    "layout"   (do (log-usage! "layout" args {:path (first args)})
                   (pp/pprint (layout (first args))))
    "tests"    (do (log-usage! "tests" args {:target (first args) :path (second args)})
                   (pp/pprint (tests (first args) (second args))))
    "hotspots" (do (log-usage! "hotspots" args {:path (first args)})
                   (pp/pprint (hotspots (first args))))
    "todos"    (do (log-usage! "todos" args {:path (first args)})
                   (pp/pprint (todos (first args))))
    "related"  (let [file    (first args)
                     opts    (rest args)
                     n       (when-let [i (.indexOf (vec opts) "--n")]
                               (when (>= (count opts) (+ i 2))
                                 (parse-long (nth opts (inc i)))))
                     min-pct (when-let [i (.indexOf (vec opts) "--min-pct")]
                               (when (>= (count opts) (+ i 2))
                                 (parse-long (nth opts (inc i)))))]
                 (log-usage! "related" args {:file file})
                 (pp/pprint (related file
                                     :n (or n 200)
                                     :min-pct (or min-pct 10))))
    "diff"     (do (log-usage! "diff" args {:file (first args)})
                   (pp/pprint (diff (first args) (some-> (second args) parse-long))))
    "diff-shape" (do (log-usage! "diff-shape" args {:path (first args) :ref (second args)})
                     (pp/pprint (diff-shape (first args) (second args))))
    "narrative" (let [file (first args)
                      opts (rest args)
                      n    (when-let [i (.indexOf (vec opts) "--n")]
                             (when (>= (count opts) (+ i 2))
                               (parse-long (nth opts (inc i)))))]
                  (log-usage! "narrative" args {:file file})
                  (pp/pprint (narrative file :n (or n 500))))
    "drift"    (let [path    (first args)
                     opts    (rest args)
                     n       (when-let [i (.indexOf (vec opts) "--n")]
                               (when (>= (count opts) (+ i 2))
                                 (parse-long (nth opts (inc i)))))
                     min-pct (when-let [i (.indexOf (vec opts) "--min-pct")]
                               (when (>= (count opts) (+ i 2))
                                 (parse-long (nth opts (inc i)))))]
                 (log-usage! "drift" args {:path path})
                 (pp/pprint (drift path
                                   :n (or n 100)
                                   :min-pct (or min-pct 15))))
    "blast"    (do (log-usage! "blast" args {:symbol (first args) :path (second args)})
                   (pp/pprint (blast (first args) (second args))))
    "patterns" (do (log-usage! "patterns" args {:path (first args)})
                   (pp/pprint (patterns (first args))))
    "changes"      (do (log-usage! "changes" args {:path (first args)})
                       (pp/pprint (changes (first args) (some-> (second args) parse-long))))
    "antipatterns" (let [;; If first arg looks like a path (contains / or .), treat it as path not name
                         [name path] (if (and (first args)
                                              (or (str/includes? (first args) "/")
                                                  (str/starts-with? (first args) ".")))
                                       [nil (first args)]
                                       [(first args) (second args)])]
                     (log-usage! "antipatterns" args {:name name :path path})
                     (pp/pprint (antipatterns name path)))
    "stats"    (pp/pprint (stats))
    "reflect"  (pp/pprint (reflect))
    "plugins"  (do (log-usage! "plugins" args {})
                   (pp/pprint (discover-plugins)))
    "update"   (do (log-usage! "update" args {})
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
                 ;; Remove existing install (real dir or stale symlink)
                 (let [f (io/file share-dir)]
                   (when (.exists f)
                     (if (java.nio.file.Files/isSymbolicLink (.toPath f))
                       (java.nio.file.Files/delete (.toPath f))
                       ;; Real dir — move aside
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
                     ;; Restore backup if it exists
                     (let [backup (io/file (str share-dir ".bak"))]
                       (if (.exists backup)
                         (do (.renameTo backup (io/file share-dir))
                             (println "  Restored previous install from backup."))
                         (println "  Run `bash install.sh` to reinstall from GitHub."))))
                   (println "Not linked (not a symlink). Nothing to do.")))
    ("help" "--help" "-h" nil)
    (let [;; Mark built-in commands
          builtins (into {} (map (fn [[k v]] [k (assoc v :spai/type :builtin)]) commands))
          ;; Discover and convert plugins to command format
          plugins  (discover-plugins)
          plugin-cmds (into {}
                            (map (fn [{:spai/keys [name] :as meta}]
                                   (let [k (keyword name)]
                                     [k (merge {:args     (:spai/args meta "")
                                                :returns  (:spai/returns meta "")
                                                :example  (:spai/example meta "")
                                                :spai/type :plugin}
                                               (select-keys meta [:spai/path]))]))
                                 plugins))
          all-cmds (merge builtins plugin-cmds)
          builtin-count (count builtins)
          plugin-count  (count plugin-cmds)]
      (println (str "spai: code exploration for LLM agents. "
                    builtin-count " built-in commands, "
                    plugin-count " plugins."))
      (println "Extend with:  spai new-plugin <name> [project|user]\n")
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
            (pp/pprint commands)
            (System/exit 1))))))
