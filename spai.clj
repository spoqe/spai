#!/usr/bin/env bb
;; spai: Code exploration for LLM agents
;;
;; Returns EDN. Wraps rg and git.
;; Not a framework. Three functions and a CLI.
;;
;; Usage:
;;   bb spai.clj shape  <path>           Module structure
;;   bb spai.clj usages <symbol> [path]  Find where a symbol is used
;;   bb spai.clj changes <path> [n]      Recent git changes

;; claude: refactor me if i'm big 

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.pprint :as pp]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

;; -------------------------------------------------------------------
;; Helpers
;; -------------------------------------------------------------------

(defn- sh
  "Shell out, return stdout string. Returns nil on non-zero exit."
  [& cmd]
  (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true} cmd)]
    (when (zero? exit)
      (str/trim out))))

(def ^:private has-rg?
  "Check once at startup whether rg is available."
  (delay
    (zero? (:exit (p/shell {:out :string :err :string :continue true} "which" "rg")))))

(defn- parse-grep-line
  "Parse a grep -rn output line: file:line:text"
  [line]
  (when-let [[_ file line-num text] (re-find #"^(.+?):(\d+):(.*)" line)]
    {:file file
     :line (parse-long line-num)
     :text (str/trim text)}))

(defn- parse-rg-json-line
  "Parse an rg --json output line into {:file :line :text}."
  [line]
  (when (seq line)
    (try
      (let [parsed (json/parse-string line true)]
        (when (= "match" (:type parsed))
          (let [d (:data parsed)]
            {:file (get-in d [:path :text])
             :line (:line_number d)
             :text (str/trim (get-in d [:lines :text] ""))})))
      (catch Exception _ nil))))

(defn- grepf
  "Search for pattern in path. Prefers rg when available, falls back to grep.
   Returns seq of {:file :line :text} maps."
  [pattern path & extra-args]
  (if @has-rg?
    ;; rg: faster, respects .gitignore, structured JSON output
    (let [args (vec (concat ["rg" "--json" "--no-heading" "--no-ignore"]
                            (remove nil? extra-args)
                            [pattern path]))
          {:keys [exit out]} (apply p/shell {:out :string :err :string :continue true} args)]
      (when (zero? exit)
        (->> (str/split-lines out)
             (keep parse-rg-json-line))))
    ;; grep: universal fallback
    (let [args (vec (concat ["grep" "-rn" "-E"]
                            (remove nil? extra-args)
                            [pattern path]))
          {:keys [exit out]} (apply p/shell {:out :string :err :string :continue true} args)]
      (when (zero? exit)
        (->> (str/split-lines out)
             (keep parse-grep-line))))))

;; -------------------------------------------------------------------
;; Language detection & patterns
;; -------------------------------------------------------------------

(def ^:private lang-patterns
  {:rust
   {:functions "^\\s*pub(\\(crate\\))?\\s*(async\\s+)?fn\\s+\\w+"
    :types     "^\\s*(pub(\\(crate\\))?\\s+)?(struct|enum|trait)\\s+\\w+"
    :impls     "^\\s*impl(<[^>]*>)?\\s+\\w+"
    :mods      "^\\s*(pub(\\(crate\\))?\\s+)?mod\\s+\\w+"
    :imports   "^use\\s+"}

   :typescript
   {:functions "(export\\s+)?(default\\s+)?(async\\s+)?function\\s+\\w+|(?:export\\s+)?(?:const|let)\\s+\\w+\\s*[:=].*=>"
    :types     "(export\\s+)?(interface|type|class|enum)\\s+\\w+"
    :imports   "^import\\s+"}

   :clojure
   {:functions "\\(defn-?\\s+\\S+"
    :types     "\\(def(record|type|protocol|multi)\\s+\\S+"
    :imports   "\\(:?require\\s+"}

   :python
   {:functions "^\\s*(async\\s+)?def\\s+\\w+"
    :types     "^\\s*class\\s+\\w+"
    :imports   "^(import|from)\\s+"}

   :go
   {:functions "^func\\s+(\\(\\w+\\s+\\*?\\w+\\)\\s+)?\\w+"
    :types     "^type\\s+\\w+\\s+(struct|interface)"
    :imports   "^import\\s+"}})

(defn- detect-lang
  "Detect primary language from file extensions in path."
  [path]
  (let [f (io/file path)]
    (if (.isFile f)
      (let [name (.getName f)]
        (cond
          (str/ends-with? name ".rs")                          :rust
          (or (str/ends-with? name ".ts")
              (str/ends-with? name ".tsx"))                     :typescript
          (or (str/ends-with? name ".clj")
              (str/ends-with? name ".cljs")
              (str/ends-with? name ".bb"))                      :clojure
          (str/ends-with? name ".py")                           :python
          (str/ends-with? name ".go")                           :go
          :else                                                 :rust))
      ;; Directory - sample first 100 files
      (let [files (->> (file-seq f)
                       (filter #(.isFile %))
                       (take 100)
                       (map #(.getName %)))]
        (cond
          (some #(str/ends-with? % ".rs") files)                :rust
          (some #(or (str/ends-with? % ".ts")
                     (str/ends-with? % ".tsx")) files)          :typescript
          (some #(or (str/ends-with? % ".clj")
                     (str/ends-with? % ".cljs")) files)         :clojure
          (some #(str/ends-with? % ".py") files)                :python
          (some #(str/ends-with? % ".go") files)                :go
          :else                                                 :rust)))))

;; -------------------------------------------------------------------
;; Name extraction
;; -------------------------------------------------------------------

(defn- extract-fn-name [text lang]
  (case lang
    :rust       (second (re-find #"fn\s+(\w+)" text))
    :typescript (or (second (re-find #"function\s+(\w+)" text))
                    (second (re-find #"(?:const|let)\s+(\w+)" text)))
    :clojure    (second (re-find #"\(defn-?\s+(\S+)" text))
    :python     (second (re-find #"def\s+(\w+)" text))
    :go         (second (re-find #"func\s+(?:\([^)]+\)\s+)?(\w+)" text))
    nil))

(defn- extract-type-name [text]
  (second (re-find #"(?:struct|enum|trait|class|interface|type|protocol|record)\s+(\w+)" text)))

(defn- extract-type-kind [text]
  (cond
    (re-find #"\bstruct\b" text)    :struct
    (re-find #"\benum\b" text)      :enum
    (re-find #"\btrait\b" text)     :trait
    (re-find #"\binterface\b" text) :interface
    (re-find #"\bclass\b" text)     :class
    (re-find #"\btype\b" text)      :type
    (re-find #"\bprotocol\b" text)  :protocol
    (re-find #"\brecord\b" text)    :record
    :else                           :unknown))

;; -------------------------------------------------------------------
;; Commands
;; -------------------------------------------------------------------

(defn- shape-raw
  "Gather all definitions. Returns flat lists with full detail."
  [path]
  (let [lang (detect-lang path)
        pats (get lang-patterns lang)]
    (when pats
      (let [find-matches (fn [pat-key]
                           (when-let [pat (get pats pat-key)]
                             (grepf pat path)))]
        {:lang      lang
         :functions (->> (find-matches :functions)
                         (mapv (fn [m]
                                 (assoc m :name (extract-fn-name (:text m) lang)))))
         :types     (->> (find-matches :types)
                         (mapv (fn [m]
                                 (assoc m
                                        :name (extract-type-name (:text m))
                                        :kind (extract-type-kind (:text m))))))
         :impls     (when (:impls pats)
                      (->> (find-matches :impls)
                           (mapv (fn [m]
                                   (assoc m :name
                                          (second (re-find #"impl(?:<[^>]*>)?\s+(\w+)" (:text m))))))))
         :modules   (when (:mods pats)
                      (->> (find-matches :mods)
                           (mapv (fn [m]
                                   (assoc m :name
                                          (second (re-find #"mod\s+(\w+)" (:text m))))))))
         :imports   (vec (find-matches :imports))}))))

(defn- relativize
  "Strip common prefix from file path for cleaner output."
  [path file]
  (if (str/starts-with? file path)
    (subs file (count path))
    file))

(defn shape
  "Module structure grouped by file. Summary by default, :full for detail."
  [path & {:keys [full]}]
  (let [raw (shape-raw path)]
    (if-not raw
      {:path path :error "No patterns for this language yet"}
      (if full
        ;; Full mode: everything, flat lists (original behavior)
        (assoc raw :path path :language (:lang raw))
        ;; Summary mode: grouped by file, just names
        (let [all-defs (concat
                         (map #(assoc % :kind-group :function) (:functions raw))
                         (map #(assoc % :kind-group :type) (:types raw))
                         (when (:impls raw)
                           (map #(assoc % :kind-group :impl) (:impls raw))))
              by-file  (->> all-defs
                            (group-by :file)
                            (into (sorted-map))
                            (mapv (fn [[file defs]]
                                    (let [fns   (->> defs (filter #(= :function (:kind-group %))) (mapv :name))
                                          types (->> defs (filter #(= :type (:kind-group %)))
                                                     (mapv #(select-keys % [:name :kind])))
                                          impls (->> defs (filter #(= :impl (:kind-group %))) (mapv :name))]
                                      {:file  (relativize path file)
                                       :functions fns
                                       :types types
                                       :impls impls}))))]
          {:path     path
           :language (:lang raw)
           :files    by-file})))))

(defn usages
  "Find where a symbol is used. Word-boundary match, excludes lock/json files."
  [symbol path]
  (let [path    (or path ".")
        type-args (if @has-rg?
                    ["-g" "*.{rs,clj,cljs,ts,tsx,py,go,edn,toml,md}"]
                    ["--include=*.rs" "--include=*.clj" "--include=*.ts"
                     "--include=*.tsx" "--include=*.py" "--include=*.go"
                     "--include=*.edn" "--include=*.toml" "--include=*.md"])
        matches (or (apply grepf symbol path "-w" type-args)
                    [])]
    {:symbol  symbol
     :path    path
     :count   (count matches)
     :matches (vec matches)}))

(def ^:private def-patterns
  "Patterns that indicate a definition (not just a usage)."
  {:rust       #"^\s*(pub(\(crate\))?\s+)?(async\s+)?(fn|struct|enum|trait|type|const|static|mod)\s+"
   :typescript #"^\s*(export\s+)?(default\s+)?(async\s+)?(function|class|interface|type|enum|const|let)\s+"
   :clojure    #"\((defn?-?|defrecord|deftype|defprotocol|defmulti|defmethod|def)\s+"
   :python     #"^\s*(async\s+)?(def|class)\s+"
   :go         #"^(func|type|var|const)\s+"})

(defn definition
  "Find where a symbol is defined. Filters usages to definition-site patterns."
  [symbol path]
  (let [path    (or path ".")
        lang    (detect-lang path)
        def-pat (get def-patterns lang)
        all     (:matches (usages symbol path))]
    {:symbol symbol
     :path   path
     :definitions
     (vec (if def-pat
            (filter #(re-find def-pat (:text %)) all)
            ;; No def pattern for this lang - return all matches (fallback)
            all))}))

(defn sig
  "API surface: function signatures for a module. The header file view."
  [path]
  (let [raw (shape-raw path)]
    (if-not raw
      {:path path :error "No patterns for this language yet"}
      {:path      path
       :language  (:lang raw)
       :signatures
       (->> (:functions raw)
            (mapv (fn [m]
                    {:name (extract-fn-name (:text m) (:lang raw))
                     :file (relativize path (:file m))
                     :line (:line m)
                     :sig  (:text m)})))})))

(def ^:private skip-dirs
  "Directories to skip in layout/overview. Universal across projects."
  #{"node_modules" "target" ".git" "__pycache__" ".next" "dist" "build"
    ".svn" "vendor" ".gradle" ".idea" ".vscode" "coverage" ".mypy_cache"
    ".pytest_cache" "venv" ".venv" "env" ".tox" ".eggs"})

(def ^:private project-files
  "Files that describe a project. Ordered by priority."
  ["Cargo.toml" "package.json" "pyproject.toml" "go.mod" "pom.xml"
   "build.gradle" "Makefile" "CMakeLists.txt" "deps.edn" "bb.edn"
   "project.clj" "Gemfile" "composer.json" "mix.exs" "CLAUDE.md"
   "README.md" "README" "readme.md"])

(defn overview
  "Project overview: language, config, top-level structure, entry points."
  [path]
  (let [root   (io/file (or path "."))
        files  (->> (.listFiles root)
                    (remove #(skip-dirs (.getName %)))
                    (sort-by #(.getName %)))
        dirs   (filter #(.isDirectory %) files)
        found  (->> project-files
                    (keep (fn [f]
                            (let [fp (io/file root f)]
                              (when (.exists fp)
                                {:file f :size (.length fp)}))))
                    vec)
        lang   (detect-lang (or path "."))
        src    (->> (file-seq root)
                    (remove #(.isDirectory %))
                    (remove #(some skip-dirs (str/split (.getPath %) #"/")))
                    (map #(.getName %)))]
    {:path       (or path ".")
     :language   lang
     :config     found
     :dirs       (mapv #(.getName %) dirs)
     :file-count (count src)
     :by-extension (->> src
                        (map #(let [n %] (when-let [i (str/last-index-of n ".")] (subs n i))))
                        (remove nil?)
                        frequencies
                        (sort-by val >)
                        (take 15)
                        vec)}))

(defn layout
  "Smart directory tree. Skips noise dirs, shows file counts per directory."
  [path]
  (let [root  (io/file (or path "."))
        walk  (fn walk [dir depth]
                (when (< depth 4)
                  (let [children (->> (.listFiles dir)
                                      (remove #(skip-dirs (.getName %)))
                                      (remove #(str/starts-with? (.getName %) "."))
                                      (sort-by #(vector (if (.isDirectory %) 0 1) (.getName %))))]
                    {:dir   (relativize (str (.getPath root) "/") (.getPath dir))
                     :files (->> children (remove #(.isDirectory %)) (mapv #(.getName %)))
                     :subdirs (->> children
                                   (filter #(.isDirectory %))
                                   (mapv #(walk % (inc depth))))})))]
    (walk root 0)))


(defn tests
  "Find test files related to a source file or symbol.
   Handles: separate test files, inline test modules (Rust #[cfg(test)]), test dirs."
  [target path]
  (let [path       (or path ".")
        f          (io/file target)
        base       (when (.exists f)
                     (let [n (.getName f)]
                       (if-let [dot (str/last-index-of n ".")]
                         (subs n 0 dot)
                         n)))
        term       (or base target)
        test-file? (fn [f] (re-find #"(?i)(test|spec)" f))
        ;; 1. Separate test files whose name contains the target
        named      (->> (file-seq (io/file path))
                        (filter #(.isFile %))
                        (map #(.getPath %))
                        (filter test-file?)
                        (filter #(str/includes? (str/lower-case %) (str/lower-case term))))
        ;; 2. Files mentioning target that are test files
        mentions   (->> (grepf term path)
                        (map :file)
                        distinct
                        (filter test-file?))
        ;; 3. Inline tests: files with test markers whose name or content matches target
        test-markers (set (->> (grepf "#\\[cfg\\(test\\)\\]|#\\[test\\]|def test_|deftest " path)
                               (map :file)
                               distinct))
        target-files (set (->> (grepf term path) (map :file) distinct))
        target-named (->> (file-seq (io/file path))
                          (filter #(.isFile %))
                          (map #(.getPath %))
                          (filter #(str/includes? (str/lower-case %) (str/lower-case term)))
                          set)
        inline       (set/intersection test-markers
                                       (set/union target-files target-named))]
    {:target       target
     :path         path
     :test-files   (vec (distinct (concat named mentions)))
     :inline-tests (vec (sort inline))}))

(defn hotspots
  "Find the biggest/most complex files. Where's the debt hiding?"
  [path]
  (let [root  (io/file (or path "."))
        files (->> (file-seq root)
                   (filter #(.isFile %))
                   (remove #(some skip-dirs (str/split (.getPath %) #"/")))
                   (remove #(str/starts-with? (.getName %) "."))
                   (filter #(re-find #"\.(rs|ts|tsx|js|jsx|py|go|clj|cljs|java|rb)$" (.getName %)))
                   (mapv (fn [f]
                           (let [lines (count (str/split-lines (slurp f)))]
                             {:file  (relativize (str (.getPath root) "/") (.getPath f))
                              :lines lines})))
                   (sort-by :lines >)
                   (take 20))]
    {:path     (or path ".")
     :hotspots (vec files)}))

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

;; -------------------------------------------------------------------
;; Project config (.spai.edn)
;; -------------------------------------------------------------------

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

;; -------------------------------------------------------------------
;; Usage logging & reflection
;; -------------------------------------------------------------------

(def ^:private log-file
  "Append-only usage log. EDN records, one per line."
  (str (System/getProperty "user.dir") "/.spai/usage.log"))

(defn- log-usage!
  "Append a usage record to the log."
  [command args result-summary]
  (try
    (spit log-file
          (str (pr-str {:ts      (str (java.time.Instant/now))
                        :command command
                        :args    (vec args)
                        :result  result-summary})
               "\n")
          :append true)
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
      (assoc s :observations (observe entries))))
)

;; -------------------------------------------------------------------
;; CLI
;; -------------------------------------------------------------------

(def commands
  {:shape   {:args     "[path] [--full]"
             :returns  "functions, types, impls grouped by file"
             :example  "./spai shape src/federation/"}
   :usages  {:args     "[symbol] [path]"
             :returns  "file, line, text for each match"
             :example  "./spai usages execute_sparql src/"}
   :def     {:args     "[symbol] [path]"
             :returns  "definition site(s) only, not usages"
             :example  "./spai def FederatedExecutor spoqe-exec/src/"}
   :sig     {:args     "[path]"
             :returns  "function signatures (the API surface)"
             :example  "./spai sig src/federation/mod.rs"}
   :overview {:args    "[path]"
              :returns "language, config files, dirs, file counts by extension"
              :example "./spai overview ."}
   :layout   {:args    "[path]"
              :returns "directory tree (depth 4), skips noise dirs"
              :example "./spai layout spoqe-core/src/"}
   :tests    {:args    "[target] [path]"
              :returns "test files related to a source file or symbol"
              :example "./spai tests step_executor spoqe-exec/src/"}
   :hotspots {:args    "[path]"
              :returns "top 20 largest source files (where's the debt?)"
              :example "./spai hotspots spoqe-exec/src/"}
   :changes      {:args    "[path] [n]"
                  :returns "recent commits with files touched"
                  :example "./spai changes src/ 3"}
   :antipatterns {:args    "[name] [path]"
                  :returns "scan for project-defined antipatterns from .spai.edn"
                  :example "./spai antipatterns uri-prefix-detection spoqe-core/src/"}
   :stats    {:args    ""
              :returns "usage counts, top paths, recent calls"
              :example "./spai stats"}
   :reflect  {:args    ""
              :returns "usage patterns with observations"
              :example "./spai reflect"}})

(let [[command & args] *command-line-args*]
  (case command
    "shape"   (let [full? (some #{"--full"} args)
                     path (first (remove #(str/starts-with? % "--") args))]
                 (log-usage! "shape" args {:path path :full (boolean full?)})
                 (pp/pprint (shape path :full full?)))
    "usages"  (do (log-usage! "usages" args {:symbol (first args) :path (second args)})
                  (pp/pprint (usages (first args) (second args))))
    "def"     (do (log-usage! "def" args {:symbol (first args) :path (second args)})
                  (pp/pprint (definition (first args) (second args))))
    "sig"     (do (log-usage! "sig" args {:path (first args)})
                  (pp/pprint (sig (first args))))
    "overview" (do (log-usage! "overview" args {:path (first args)})
                   (pp/pprint (overview (first args))))
    "layout"   (do (log-usage! "layout" args {:path (first args)})
                   (pp/pprint (layout (first args))))
    "tests"    (do (log-usage! "tests" args {:target (first args) :path (second args)})
                   (pp/pprint (tests (first args) (second args))))
    "hotspots" (do (log-usage! "hotspots" args {:path (first args)})
                   (pp/pprint (hotspots (first args))))
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
    ("help" "--help" "-h") (pp/pprint commands)
    nil (pp/pprint commands)
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
