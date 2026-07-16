(ns spai.core
  "Shell helpers, grep, language detection, name extraction.
   Foundation module — everything else depends on this."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; -------------------------------------------------------------------
;; Shell & grep
;; -------------------------------------------------------------------

(defn sh
  "Shell out, return stdout string. Returns nil on non-zero exit."
  [& cmd]
  (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true} cmd)]
    (when (zero? exit)
      (str/trim out))))

(def has-rg?
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

(defn grepf
  "Search for pattern in path. Prefers rg when available, falls back to grep.
   Returns seq of {:file :line :text} maps."
  [pattern path & extra-args]
  (if @has-rg?
    ;; rg: faster, respects .gitignore, structured JSON output
    (let [args (vec (concat ["rg" "--json" "--no-heading"]
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

(def lang-patterns
  "Language -> grep patterns registry. Open for extension via register-lang!."
  (atom
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
    {:functions "\\(def(n-?|method)\\s+\\S+"
     :types     "\\(def(record|type|protocol|multi)\\s+\\S+"
     :imports   "\\(:?require\\s+"}

    :python
    {:functions "^\\s*(async\\s+)?def\\s+\\w+"
     :types     "^\\s*class\\s+\\w+"
     :imports   "^(import|from)\\s+"}

    :go
    {:functions "^func\\s+(\\(\\w+\\s+\\*?\\w+\\)\\s+)?\\w+"
     :types     "^type\\s+\\w+\\s+(struct|interface)"
     :imports   "^import\\s+"}

    :php
    {:functions "^\\s*(public|protected|private)?\\s*(static\\s+)?function\\s+\\w+"
     :types     "^\\s*(abstract\\s+|final\\s+)?(class|interface|trait|enum)\\s+\\w+"
     :imports   "^\\s*(use|require|require_once|include|include_once)\\s+"}

    :java
    {:functions "^\\s*(public|protected|private)?\\s*(static\\s+)?(synchronized\\s+)?(abstract\\s+)?[\\w<>\\[\\],\\s]+\\s+\\w+\\s*\\("
     :types     "^\\s*(public|protected|private)?\\s*(static\\s+)?(abstract\\s+|final\\s+)?(class|interface|enum|record|@interface)\\s+\\w+"
     :imports   "^import\\s+"}

    :swift
    {:functions "^\\s*(@\\w+(\\([^)]*\\))?\\s+)*(open\\s+|public\\s+|internal\\s+|fileprivate\\s+|private\\s+)?(static\\s+|class\\s+|override\\s+|mutating\\s+|nonisolated\\s+)*func\\s+\\w+"
     :types     "^\\s*(open\\s+|public\\s+|internal\\s+|fileprivate\\s+|private\\s+)?(final\\s+)?(struct|class|enum|protocol|actor)\\s+\\w+"
     :impls     "^\\s*(open\\s+|public\\s+|internal\\s+|fileprivate\\s+|private\\s+)?extension\\s+\\w+"
     :imports   "^(@testable\\s+)?import\\s+"}

    :scala
    {:functions "^\\s*(override\\s+)?(private(\\[\\w+\\])?\\s+|protected(\\[\\w+\\])?\\s+)?(lazy\\s+)?(def|val|var)\\s+\\w+"
     :types     "^\\s*(sealed\\s+|abstract\\s+|final\\s+)?(case\\s+)?(class|object|trait|enum)\\s+\\w+"
     :imports   "^import\\s+"}

    :ruby
    {:functions "^\\s*(def\\s+self\\.\\w+|def\\s+\\w+)"
     :types     "^\\s*(class|module)\\s+\\w+"
     :imports   "^\\s*(require|require_relative|include|extend|prepend)\\s+"}

    :kotlin
    {:functions "^\\s*(override\\s+)?(public\\s+|private\\s+|protected\\s+|internal\\s+)?(inline\\s+|suspend\\s+|tailrec\\s+)*(fun\\s+\\w+|val\\s+\\w+|var\\s+\\w+)"
     :types     "^\\s*(public\\s+|private\\s+|protected\\s+|internal\\s+)?(sealed\\s+|abstract\\s+|open\\s+|inner\\s+|data\\s+|value\\s+|enum\\s+)*(class|interface|object)\\s+\\w+"
     :imports   "^import\\s+"}}))


(defn register-lang!
  "Register grep patterns for a new language. Extends spai to support any language.
   patterns is a map with keys :functions, :types, :imports (and optionally :impls, :mods)."
  [lang patterns]
  (swap! lang-patterns assoc lang patterns))

(def skip-dirs
  "Directories to skip when walking a project. Universal across projects."
  #{"node_modules" "target" ".git" "__pycache__" ".next" "dist" "build"
    ".svn" "vendor" ".gradle" ".idea" ".vscode" "coverage" ".mypy_cache"
    ".pytest_cache" "venv" ".venv" "env" ".tox" ".eggs"})

(def ext->lang
  "File extension (without dot) → language keyword."
  {"rs"    :rust
   "ts"    :typescript, "tsx" :typescript
   "clj"   :clojure,    "cljs" :clojure, "cljc" :clojure, "bb" :clojure
   "py"    :python
   "go"    :go
   "php"   :php
   "java"  :java
   "swift" :swift
   "scala" :scala,      "sc" :scala
   "rb"    :ruby
   "kt"    :kotlin,     "kts" :kotlin})

(defn- file-ext
  "Lowercased extension (no dot) of a filename, or nil."
  [name]
  (when-let [i (str/last-index-of name ".")]
    (str/lower-case (subs name (inc i)))))

(defn detect-lang
  "Detect primary language in path by counting source files per language and
  taking the most common. Skips vendored/build/VCS noise (see skip-dirs) so a
  TypeScript project isn't misread from stray files in node_modules or .git.
  Returns :unknown when no known source files are found."
  [path]
  (let [f (io/file path)]
    (if (.isFile f)
      (get ext->lang (file-ext (.getName f)) :unknown)
      ;; Directory: count code files by language, skipping noise dirs, then
      ;; pick the language with the most files.
      (let [counts (->> (file-seq f)
                        (filter #(.isFile %))
                        (remove #(some skip-dirs (str/split (.getPath %) #"/")))
                        (keep #(get ext->lang (file-ext (.getName %))))
                        frequencies)]
        (if (seq counts)
          (key (apply max-key val counts))
          :unknown)))))

(def ^:private core-clj-path
  "Absolute path to this file, for use in warnings."
  (try (.getCanonicalPath (io/file *file*))
       (catch Exception _ "src/spai/core.clj")))

(defn resolve-lang
  "Resolve detected language. Returns [effective-lang warning-or-nil].
   When detect-lang returns :unknown, falls back to :rust patterns and
   includes a warning with the file path for adding new language support."
  [detected]
  (if (= detected :unknown)
    [:rust (str "No language patterns matched. Using Rust patterns as best guess.\n"
                "To add your language, edit: " core-clj-path "\n"
                "Look for lang-patterns — add an entry like the existing ones.")]
    [detected nil]))

;; -------------------------------------------------------------------
;; Name extraction
;; -------------------------------------------------------------------

(defn extract-fn-name [text lang]
  (case lang
    :rust       (second (re-find #"fn\s+(\w+)" text))
    :typescript (or (second (re-find #"function\s+(\w+)" text))
                    (second (re-find #"(?:const|let)\s+(\w+)" text)))
    :clojure    (or (second (re-find #"\(defn-?\s+(\S+)" text))
                    ;; defmethod: capture "name dispatch-val"
                    (when-let [[_ nm dv] (re-find #"\(defmethod\s+(\S+)\s+(\S+)" text)]
                      (str nm " " dv)))
    :python     (second (re-find #"def\s+(\w+)" text))
    :go         (second (re-find #"func\s+(?:\([^)]+\)\s+)?(\w+)" text))
    :php        (second (re-find #"function\s+(\w+)" text))
    :java       (second (re-find #"(\w+)\s*\(" text))
    :swift      (second (re-find #"func\s+(\w+)" text))
    :scala      (second (re-find #"(?:def|val|var)\s+(\w+)" text))
    :ruby       (or (second (re-find #"def\s+self\.(\w+)" text))
                    (second (re-find #"def\s+(\w+)" text)))
    :kotlin     (or (second (re-find #"fun\s+(\w+)" text))
                    (second (re-find #"(?:val|var)\s+(\w+)" text)))
    nil))

(defn extract-type-name [text]
  (second (re-find #"(?:enum\s+class|struct|enum|trait|class|interface|type|protocol|record|object|module)\s+(\w+)" text)))

(defn extract-type-kind [text]
  (cond
    (re-find #"\bstruct\b" text)      :struct
    (re-find #"\benum\s+class\b" text) :enum
    (re-find #"\benum\b" text)        :enum
    (re-find #"\btrait\b" text)     :trait
    (re-find #"\binterface\b" text) :interface
    (re-find #"\bclass\b" text)     :class
    (re-find #"\btype\b" text)      :type
    (re-find #"\bprotocol\b" text)  :protocol
    (re-find #"\brecord\b" text)    :record
    (re-find #"\bactor\b" text)    :actor
    (re-find #"\bobject\b" text)  :object
    (re-find #"\bmodule\b" text) :module
    :else                           :unknown))

(defn relativize
  "Strip common prefix from file path for cleaner output."
  [path file]
  (if (str/starts-with? file path)
    (subs file (count path))
    file))

(def source-exts
  "Source file extensions we care about."
  #"\.(rs|ts|tsx|js|jsx|py|go|clj|cljs|java|rb|php|swift|scala|sc|rake|kt|kts)$")
