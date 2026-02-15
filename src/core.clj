;; spai/core — shell helpers, grep, language detection, name extraction
;; Loaded first. Everything else depends on this.

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.pprint :as pp]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

;; -------------------------------------------------------------------
;; Shell & grep
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

(defn- relativize
  "Strip common prefix from file path for cleaner output."
  [path file]
  (if (str/starts-with? file path)
    (subs file (count path))
    file))

(def ^:private skip-dirs
  "Directories to skip in layout/overview. Universal across projects."
  #{"node_modules" "target" ".git" "__pycache__" ".next" "dist" "build"
    ".svn" "vendor" ".gradle" ".idea" ".vscode" "coverage" ".mypy_cache"
    ".pytest_cache" "venv" ".venv" "env" ".tox" ".eggs"})

(def ^:private source-exts
  "Source file extensions we care about."
  #"\.(rs|ts|tsx|js|jsx|py|go|clj|cljs|java|rb)$")
