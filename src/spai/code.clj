(ns spai.code
  "Code structure analysis: shape, usages, definition, sig, who, deps, context, patterns."
  (:require [spai.core :as core]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn shape-raw
  "Gather all definitions. Returns flat lists with full detail."
  [path]
  (let [[lang warning] (core/resolve-lang (core/detect-lang path))
        pats (get @core/lang-patterns lang)]
    (when pats
      (let [find-matches (fn [pat-key]
                           (when-let [pat (get pats pat-key)]
                             (core/grepf pat path)))]
        (cond->
          {:lang      lang
           :functions (->> (find-matches :functions)
                           (mapv (fn [m]
                                   (assoc m :name (core/extract-fn-name (:text m) lang)))))
           :types     (->> (find-matches :types)
                           (mapv (fn [m]
                                   (assoc m
                                          :name (core/extract-type-name (:text m))
                                          :kind (core/extract-type-kind (:text m))))))
           :impls     (when (:impls pats)
                        (->> (find-matches :impls)
                             (mapv (fn [m]
                                     (assoc m :name
                                            (or (second (re-find #"impl(?:<[^>]*>)?\s+(\w+)" (:text m)))
                                                (second (re-find #"extension\s+(\w+)" (:text m)))))))))
           :modules   (when (:mods pats)
                        (->> (find-matches :mods)
                             (mapv (fn [m]
                                     (assoc m :name
                                            (second (re-find #"mod\s+(\w+)" (:text m))))))))
           :imports   (vec (find-matches :imports))}
          warning (assoc :warning warning))))))

(defn shape
  "Module structure grouped by file. Summary by default, :full for detail."
  [path & {:keys [full]}]
  (let [raw (shape-raw path)]
    (if-not raw
      {:path path :error "No patterns for this language yet"}
      (if full
        ;; Full mode: everything, flat lists (original behavior)
        (cond-> (assoc raw :path path :language (:lang raw))
          (:warning raw) (assoc :warning (:warning raw)))
        ;; Summary mode: grouped by file, just names
        (let [all-defs (concat
                         (map #(assoc % :kind-group :function) (:functions raw))
                         (map #(assoc % :kind-group :type) (:types raw))
                         (when (:impls raw)
                           (map #(assoc % :kind-group :impl) (:impls raw))))
              ;; Relativize against cwd if possible, otherwise against the search path
              cwd      (let [p (System/getProperty "user.dir")] (if (str/ends-with? p "/") p (str p "/")))
              abs-path (let [p (.getAbsolutePath (io/file path))] (if (str/ends-with? p "/") p (str p "/")))
              rel      (fn [file] (let [r (core/relativize cwd file)]
                                    (if (= r file) (core/relativize abs-path file) r)))
              by-file  (->> all-defs
                            (group-by :file)
                            (into (sorted-map))
                            (mapv (fn [[file defs]]
                                    (let [fns   (->> defs (filter #(= :function (:kind-group %)))
                                                     (mapv #(select-keys % [:name :line])))
                                          types (->> defs (filter #(= :type (:kind-group %)))
                                                     (mapv #(select-keys % [:name :kind :line])))
                                          impls (->> defs (filter #(= :impl (:kind-group %)))
                                                     (mapv #(select-keys % [:name :line])))]
                                      {:file  (rel file)
                                       :functions fns
                                       :types types
                                       :impls impls}))))]
          (cond-> {:path     path
                   :language (:lang raw)
                   :files    by-file}
            (:warning raw) (assoc :warning (:warning raw))))))))

(defn usages
  "Find where a symbol is used. Word-boundary match, excludes lock/json files."
  [symbol path]
  (let [path    (or path ".")
        type-args (if @core/has-rg?
                    ["-g" "*.{rs,clj,cljs,ts,tsx,py,go,php,java,swift,edn,toml,md}"]
                    ["--include=*.rs" "--include=*.clj" "--include=*.ts"
                     "--include=*.tsx" "--include=*.py" "--include=*.go"
                     "--include=*.php" "--include=*.java" "--include=*.swift"
                     "--include=*.edn" "--include=*.toml"
                     "--include=*.md"])
        matches (or (apply core/grepf symbol path "-w" type-args)
                    [])]
    {:symbol  symbol
     :path    path
     :count   (count matches)
     :matches (vec matches)}))

(defn grep-raw
  "Raw pattern search using ripgrep. Supports arbitrary rg flags."
  [pattern & args]
  (let [;; Separate path from flags (path doesn't start with -)
        [path-args flags] (split-with #(not (str/starts-with? % "-")) args)
        path (or (first path-args) ".")
        ;; Use structured output unless user requests otherwise
        structured? (not (some #{"-l" "--files-with-matches" "-c" "--count" "--no-filename"} flags))]
    (if structured?
      ;; Standard mode - use grepf for structured output
      (let [matches (or (apply core/grepf pattern path flags) [])]
        {:pattern pattern
         :path path
         :count (count matches)
         :matches (vec matches)})
      ;; Pass-through mode - call rg directly, return raw output
      (let [rg-args (concat ["rg"] flags [pattern path])
            result (apply p/shell {:out :string :continue true} rg-args)]
        {:pattern pattern
         :path path
         :flags (vec flags)
         :output (str/trim-newline (:out result))}))))

(def ^:private def-patterns
  "Patterns that indicate a definition (not just a usage)."
  {:rust       #"^\s*(pub(\(crate\))?\s+)?(async\s+)?(fn|struct|enum|trait|type|const|static|mod)\s+"
   :typescript #"^\s*(export\s+)?(default\s+)?(async\s+)?(function|class|interface|type|enum|const|let)\s+"
   :clojure    #"\((defn?-?|defrecord|deftype|defprotocol|defmulti|defmethod|def)\s+"
   :python     #"^\s*(async\s+)?(def|class)\s+"
   :go         #"^(func|type|var|const)\s+"
   :php        #"^\s*(public|protected|private)?\s*(static\s+)?(function|class|interface|trait|enum|abstract\s+class|final\s+class)\s+"
   :java       #"^\s*(public|protected|private)?\s*(static\s+)?(abstract\s+)?(class|interface|enum|record|@interface|void|int|long|boolean|String|[A-Z]\w+)\s+"
   :swift      #"^\s*(@\w+(\([^)]*\))?\s+)*(open\s+|public\s+|internal\s+|fileprivate\s+|private\s+)?(static\s+|class\s+|override\s+|mutating\s+)?(func|struct|class|enum|protocol|actor|extension|typealias|let|var)\s+"})

(defn definition
  "Find where a symbol is defined. Filters usages to definition-site patterns."
  [symbol path]
  (let [path    (or path ".")
        [lang warning] (core/resolve-lang (core/detect-lang path))
        def-pat (get def-patterns lang)
        all     (:matches (usages symbol path))]
    (cond-> {:symbol symbol
             :path   path
             :definitions
             (vec (if def-pat
                    (filter #(re-find def-pat (:text %)) all)
                    ;; No def pattern for this lang - return all matches (fallback)
                    all))}
      warning (assoc :warning warning))))

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
                    {:name (core/extract-fn-name (:text m) (:lang raw))
                     :file (core/relativize path (:file m))
                     :line (:line m)
                     :sig  (:text m)})))})))

;; -------------------------------------------------------------------
;; who — reverse file dependencies
;; -------------------------------------------------------------------

(def ^:private import-patterns
  "Patterns for finding import/use statements, by language."
  {:rust       {:pattern "^use\\s+" :extract #"use\s+(?:crate::)?(\S+?)(?:::\{|;)"}
   :typescript {:pattern "^import\\s+" :extract #"from\s+['\"]([^'\"]+)['\"]"}
   :clojure    {:pattern "\\(:?require\\s+" :extract #"\[([^\s\]]+)"}
   :python     {:pattern "^(import|from)\\s+" :extract #"(?:from|import)\s+(\S+)"}
   :go         {:pattern "^import\\s+" :extract #"\"([^\"]+)\""}
   :php        {:pattern "^\\s*(use|require|require_once|include|include_once)\\s+"
                :extract #"(?:use|require|require_once|include|include_once)\s+(\S+)"}
   :java       {:pattern "^import\\s+" :extract #"import\s+(?:static\s+)?([^;]+)"}
   :swift      {:pattern "^(@testable\\s+)?import\\s+" :extract #"import\s+(\w+)"}})

(defn who
  "Reverse file dependencies. Who imports/uses this file?
   Answers: 'if I change this file, who breaks?'"
  [file path]
  (let [path (or path ".")
        f    (io/file file)
        name (.getName f)
        ;; Strip extension for module name matching
        base (if-let [dot (str/last-index-of name ".")]
               (subs name 0 dot)
               name)
        ;; Also try path-based module name (e.g., federation/mod -> federation)
        mod-name (let [rel (core/relativize (str (.getPath (io/file path)) "/") file)
                       no-ext (if-let [dot (str/last-index-of rel ".")]
                                (subs rel 0 dot)
                                rel)]
                   ;; Convert path separators to module separators
                   (str/replace no-ext #"/" "::"))
        ;; Search for files that reference this module/file
        terms [base mod-name (str/replace mod-name #"::" "/")]
        matches (->> terms
                     distinct
                     (mapcat (fn [term]
                               (or (core/grepf term path "-w") [])))
                     ;; Remove self-references
                     (remove #(= (:file %) file))
                     ;; Keep only import-like lines
                     (filter (fn [{:keys [text]}]
                               (or (re-find #"^(use|mod|pub\s+mod|import|from|require)" text)
                                   (re-find #"from\s+['\"]" text))))
                     ;; Dedup by file
                     (group-by :file)
                     (mapv (fn [[f hits]]
                             {:file f
                              :references (mapv #(select-keys % [:line :text]) hits)}))
                     (sort-by :file)
                     vec)]
    {:file       file
     :base       base
     :dependents (count matches)
     :files      matches}))

;; -------------------------------------------------------------------
;; deps — forward dependency graph
;;
;; Three multimethods dispatch on language:
;;   extract-imports    — parse import statements from a source file
;;   find-project-root  — locate project root for module resolution
;;   resolve-module     — resolve a module reference to a file path
;; -------------------------------------------------------------------

(defmulti extract-imports
  "Extract import declarations from a source file.
   Returns seq of {:module :line :kind}."
  (fn [lang _file-path] lang))

(defmethod extract-imports :rust [_ file-path]
  (let [use-hits  (or (core/grepf "^\\s*(pub\\s+)?use\\s+" file-path) [])
        mod-hits  (or (core/grepf "^\\s*(pub(\\(crate\\))?\\s+)?mod\\s+\\w+\\s*;" file-path) [])
        parse-use (fn [text]
                    (when-let [m (re-find #"use\s+(\S+?)(?:::\{|;)" text)]
                      (second m)))
        parse-mod (fn [text]
                    (when-let [m (re-find #"mod\s+(\w+)\s*;" text)]
                      (str "self::" (second m))))]
    (concat
      (->> use-hits
           (keep (fn [h] (when-let [m (parse-use (:text h))]
                           {:module m :line (:line h) :kind :use}))))
      (->> mod-hits
           (keep (fn [h] (when-let [m (parse-mod (:text h))]
                           {:module m :line (:line h) :kind :mod})))))))

(defmethod extract-imports :typescript [_ file-path]
  (let [hits (or (core/grepf "^import\\s+" file-path) [])]
    (->> hits
         (keep (fn [h]
                 (when-let [m (re-find #"from\s+['\"]([^'\"]+)['\"]" (:text h))]
                   {:module (second m) :line (:line h) :kind :import}))))))

(defmethod extract-imports :python [_ file-path]
  (let [hits (or (core/grepf "^\\s*(import|from)\\s+" file-path) [])]
    (->> hits
         (keep (fn [h]
                 (let [text (:text h)]
                   (cond
                     ;; from .foo import bar  OR  from . import foo
                     (re-find #"^from\s+(\.\S*)\s+import" text)
                     (let [[_ rel-mod] (re-find #"^from\s+(\.\S*)\s+import\s+(.+)" text)
                           items (when rel-mod
                                   (second (re-find #"^from\s+\.\S*\s+import\s+(.+)" text)))]
                       (when rel-mod
                         {:module rel-mod :line (:line h) :kind :from
                          :items (when items
                                   (mapv str/trim (str/split (str/replace items #"\(|\)" "") #",")))}))

                     ;; from foo.bar import baz
                     (re-find #"^from\s+" text)
                     (when-let [[_ mod-name] (re-find #"^from\s+(\S+)\s+import" text)]
                       {:module mod-name :line (:line h) :kind :from})

                     ;; import foo.bar, import foo
                     (re-find #"^import\s+" text)
                     (when-let [[_ mod-name] (re-find #"^import\s+(\S+)" text)]
                       {:module (str/replace mod-name #",$" "")
                        :line (:line h) :kind :import}))))))))

(defmethod extract-imports :php [_ file-path]
  (let [hits (or (core/grepf "^\\s*(use|require|require_once|include|include_once)\\s+" file-path) [])]
    (->> hits
         (keep (fn [h]
                 (let [text (:text h)]
                   (cond
                     ;; use App\Models\User;  or  use App\Models\User as Alias;
                     (re-find #"^\s*use\s+" text)
                     (when-let [[_ ns-path] (re-find #"use\s+([\w\\]+)" text)]
                       {:module (str/replace ns-path "\\" "/")
                        :raw    ns-path
                        :line   (:line h)
                        :kind   :use})

                     ;; require/require_once/include/include_once
                     (re-find #"^\s*(require|require_once|include|include_once)" text)
                     (when-let [[_ _ path] (re-find #"(require|require_once|include|include_once)\s+['\x22]([^'\x22]+)['\x22]" text)]
                       {:module path
                        :line   (:line h)
                        :kind   :require}))))))))

(defmethod extract-imports :java [_ file-path]
  (let [hits (or (core/grepf "^import\\s+" file-path) [])]
    (->> hits
         (keep (fn [h]
                 (when-let [[_ path] (re-find #"import\s+(?:static\s+)?([^;]+)" (:text h))]
                   {:module (str/trim path)
                    :line   (:line h)
                    :kind   (if (re-find #"^import\s+static\s+" (:text h)) :static-import :import)}))))))

(defmethod extract-imports :swift [_ file-path]
  (let [hits (or (core/grepf "^(@testable\\s+)?import\\s+" file-path) [])]
    (->> hits
         (keep (fn [h]
                 (when-let [[_ _ mod-name] (re-find #"^(@testable\s+)?import\s+(\w+)" (:text h))]
                   {:module mod-name
                    :line   (:line h)
                    :kind   (if (re-find #"^@testable" (:text h)) :testable-import :import)}))))))

(defmethod extract-imports :default [lang file-path]
  (when-let [ip (get import-patterns lang)]
    (let [hits (or (core/grepf (:pattern ip) file-path) [])]
      (->> hits
           (keep (fn [h]
                   (let [matched (re-find (:extract ip) (:text h))]
                     (when matched
                       {:module (if (vector? matched) (last matched) matched)
                        :line (:line h)
                        :kind :import}))))))))

;; ---

(defmulti find-project-root
  "Find the project root directory for resolving imports."
  (fn [lang _start-path] lang))

(defmethod find-project-root :rust [_ start-path]
  (loop [dir (let [f (io/file start-path)]
               (if (.isFile f) (.getParentFile f) f))]
    (when dir
      (let [cargo (io/file dir "Cargo.toml")
            src   (io/file dir "src")]
        (if (and (.exists cargo) (.isDirectory src))
          (.getCanonicalPath src)
          (recur (.getParentFile dir)))))))

(defmethod find-project-root :python [_ start-path]
  (loop [dir (let [f (io/file start-path)]
               (if (.isFile f) (.getParentFile f) f))]
    (when dir
      (if (or (.exists (io/file dir "pyproject.toml"))
              (.exists (io/file dir "setup.py"))
              (.exists (io/file dir "setup.cfg")))
        (.getCanonicalPath dir)
        (recur (.getParentFile dir))))))

(defmethod find-project-root :php [_ start-path]
  (loop [dir (let [f (io/file start-path)]
               (if (.isFile f) (.getParentFile f) f))]
    (when dir
      (if (.exists (io/file dir "composer.json"))
        (.getCanonicalPath dir)
        (recur (.getParentFile dir))))))

(defmethod find-project-root :java [_ start-path]
  (loop [dir (let [f (io/file start-path)]
               (if (.isFile f) (.getParentFile f) f))]
    (when dir
      (if (or (.exists (io/file dir "pom.xml"))
              (.exists (io/file dir "build.gradle"))
              (.exists (io/file dir "build.gradle.kts")))
        (.getCanonicalPath dir)
        (recur (.getParentFile dir))))))

(defmethod find-project-root :swift [_ start-path]
  (loop [dir (let [f (io/file start-path)]
               (if (.isFile f) (.getParentFile f) f))]
    (when dir
      (if (or (.exists (io/file dir "Package.swift"))
              (some #(str/ends-with? (.getName %) ".xcodeproj")
                    (.listFiles dir)))
        (.getCanonicalPath dir)
        (recur (.getParentFile dir))))))

(defmethod find-project-root :default [_ _] nil)

;; ---

(defmulti resolve-module
  "Try to resolve a module reference to a project file path.
   Returns canonical path string or nil."
  (fn [lang _mod-str _current-file _project-root] lang))

(defmethod resolve-module :rust [_ mod-str current-file src-root]
  (let [;; Normalize bare names: foo → self::foo, super alone → super::mod
        mod-str (cond
                  (= mod-str "super")            "super::mod"
                  (not (re-find #"::" mod-str))   (str "self::" mod-str)
                  :else                            mod-str)
        clean   (-> mod-str
                    (str/replace #"::\{.*$" "")
                    (str/replace #"::\*$" "")
                    str/trim)
        cur-dir (.getParent (io/file current-file))
        try-resolve (fn [base rest-path]
                      (when base
                        (let [as-path (str/replace rest-path "::" "/")]
                          (or (let [f (io/file (str base "/" as-path ".rs"))]
                                (when (.exists f) (.getCanonicalPath f)))
                              (let [f (io/file (str base "/" as-path "/mod.rs"))]
                                (when (.exists f) (.getCanonicalPath f)))))))]
    (cond
      (str/starts-with? clean "crate::")
      (try-resolve src-root (subs clean 7))

      (str/starts-with? clean "super::")
      (try-resolve (.getParent (io/file cur-dir)) (subs clean 7))

      (str/starts-with? clean "self::")
      (try-resolve cur-dir (subs clean 6))

      :else nil)))

(defmethod resolve-module :typescript [_ mod-str current-file _]
  (when (str/starts-with? mod-str ".")
    (let [cur-dir (.getParent (io/file current-file))
          base    (str cur-dir "/" mod-str)]
      (or (let [f (io/file (str base ".ts"))]     (when (.exists f) (.getCanonicalPath f)))
          (let [f (io/file (str base ".tsx"))]    (when (.exists f) (.getCanonicalPath f)))
          (let [f (io/file (str base "/index.ts"))]  (when (.exists f) (.getCanonicalPath f)))
          (let [f (io/file (str base "/index.tsx"))] (when (.exists f) (.getCanonicalPath f)))))))

(defmethod resolve-module :python [_ mod-str current-file project-root]
  (let [cur-dir  (.getParent (io/file current-file))
        try-file (fn [base as-path]
                   (when base
                     (or (let [f (io/file (str base "/" as-path ".py"))]
                           (when (.exists f) (.getCanonicalPath f)))
                         (let [f (io/file (str base "/" as-path "/__init__.py"))]
                           (when (.exists f) (.getCanonicalPath f))))))]
    (cond
      ;; Relative: .foo → current dir, ..foo → parent, etc.
      (str/starts-with? mod-str ".")
      (let [dots   (count (re-find #"^\.+" mod-str))
            rest   (subs mod-str dots)
            base   (loop [d (io/file cur-dir) n (dec dots)]
                     (if (or (<= n 0) (nil? d)) d
                       (recur (.getParentFile d) (dec n))))
            as-path (str/replace rest "." "/")]
        (when base
          (if (seq as-path)
            (try-file (.getPath base) as-path)
            (let [f (io/file (.getPath base) "__init__.py")]
              (when (.exists f) (.getCanonicalPath f))))))

      ;; Absolute: try from project root, then common src/ layouts
      :else
      (let [as-path (str/replace mod-str "." "/")
            roots   (if project-root
                      [project-root
                       (str project-root "/src")
                       (str project-root "/lib")]
                      [cur-dir])]
        (some #(try-file % as-path) roots)))))

(defmethod resolve-module :php [_ mod-str current-file project-root]
  (let [cur-dir  (.getParent (io/file current-file))
        as-path  (str/replace mod-str "\\" "/")
        try-file (fn [base path]
                   (when base
                     (let [f (io/file (str base "/" path ".php"))]
                       (when (.exists f) (.getCanonicalPath f)))))]
    (cond
      ;; Relative path (require/include): try from current dir
      (or (str/starts-with? mod-str "./")
          (str/starts-with? mod-str "../")
          (str/ends-with? mod-str ".php"))
      (let [f (io/file cur-dir mod-str)]
        (when (.exists f) (.getCanonicalPath f)))

      ;; Namespace (use statement): try PSR-4 resolution from project root
      :else
      (when project-root
        (or (try-file project-root as-path)
            (try-file (str project-root "/src") as-path)
            (try-file (str project-root "/lib") as-path)
            (try-file (str project-root "/app") as-path))))))

(defmethod resolve-module :java [_ mod-str _current-file project-root]
  (let [;; com.foo.bar.Baz → com/foo/bar/Baz.java
        as-path (str (str/replace mod-str "." "/") ".java")
        try-file (fn [base]
                   (when base
                     (let [f (io/file (str base "/" as-path))]
                       (when (.exists f) (.getCanonicalPath f)))))]
    (when project-root
      (or (try-file (str project-root "/src/main/java"))
          (try-file (str project-root "/src"))
          (try-file project-root)
          ;; Android layout
          (try-file (str project-root "/app/src/main/java"))))))

(defmethod resolve-module :default [_ _ _ _] nil)

;; ---

(defn- classify-import
  "Classify an import as :local, :external, or :probe."
  [mod-str lang]
  (case lang
    :rust       (if (or (str/starts-with? mod-str "crate::")
                        (str/starts-with? mod-str "super::")
                        (str/starts-with? mod-str "self::")
                        (= mod-str "crate")
                        (= mod-str "super")
                        (= mod-str "self"))
                  :local :external)
    :typescript (if (str/starts-with? mod-str ".") :local :external)
    :python     (if (str/starts-with? mod-str ".") :local :probe)
    :php        :probe
    :java       (if (or (str/starts-with? mod-str "java.")
                        (str/starts-with? mod-str "javax.")
                        (str/starts-with? mod-str "jakarta.")
                        (str/starts-with? mod-str "org.w3c.")
                        (str/starts-with? mod-str "org.xml.")
                        (str/starts-with? mod-str "org.ietf."))
                  :external :probe)
    :swift      :external  ;; Swift imports are always module-level (no relative imports)
    :clojure    :unknown
    :go         :unknown
    :unknown))

(defn deps
  "Import/dependency graph.
   Single file: what this file imports, resolved to project files.
   Directory: full graph with hub/load-bearing analysis."
  [path]
  (let [path (or path ".")
        [lang warning] (core/resolve-lang (core/detect-lang path))
        f    (io/file path)]
    (if-not (get import-patterns lang)
      {:path path :error "No import patterns for this language yet"}
      (let [files (if (.isFile f)
                    [(.getCanonicalPath f)]
                    (->> (file-seq f)
                         (filter #(.isFile %))
                         (remove #(some core/skip-dirs (str/split (.getPath %) #"/")))
                         (filter #(re-find core/source-exts (.getName %)))
                         (map #(.getCanonicalPath %))
                         sort))
            project-root (find-project-root lang (or (first files) path))
            target-dir (if (.isFile f) (.getParent f) (.getCanonicalPath f))
            git-root (try (str/trim (:out @(p/process ["git" "rev-parse" "--show-toplevel"]
                                                       {:out :string :err :string
                                                        :dir target-dir})))
                          (catch Exception _ nil))
            rel      (fn [p]
                       (when p
                         (let [base (or git-root (.getCanonicalPath f))]
                           (if (str/starts-with? p base)
                             (let [r (subs p (count base))]
                               (if (str/starts-with? r "/") (subs r 1) r))
                             p))))
            entries  (mapv
                       (fn [file-path]
                         (let [raw-imports (extract-imports lang file-path)
                               ;; Rust: bare names matching a mod decl are local
                               mod-names  (when (= lang :rust)
                                            (set (keep (fn [{:keys [kind module]}]
                                                         (when (= kind :mod)
                                                           (str/replace module "self::" "")))
                                                       raw-imports)))
                               imports (mapv (fn [{:keys [module] :as imp}]
                                               (let [cls (if (and (= lang :rust)
                                                                  mod-names
                                                                  (contains? mod-names module)
                                                                  (not= :mod (:kind imp)))
                                                           :local
                                                           (classify-import module lang))
                                                     resolved (resolve-module lang module file-path project-root)
                                                     final-cls (if (= cls :probe)
                                                                 (if resolved :local :external)
                                                                 cls)]
                                                 (cond-> (assoc imp :scope final-cls)
                                                   resolved (assoc :resolved (rel resolved)))))
                                             (vec raw-imports))]
                           {:file     (or (rel file-path) file-path)
                            :imports  imports
                            :local    (count (filter #(= :local (:scope %)) imports))
                            :external (vec (distinct (map :module (filter #(#{:external :unknown} (:scope %)) imports))))}))
                       files)]
        (if (.isFile f)
          (cond-> (first entries)
            warning (assoc :warning warning))
          (let [rev-deps (reduce
                           (fn [acc entry]
                             (reduce (fn [a imp]
                                       (if-let [r (:resolved imp)]
                                         (update a r (fnil conj #{}) (:file entry))
                                         a))
                                     acc (:imports entry)))
                           {} entries)
                hubs (->> entries
                          (sort-by :local >)
                          (take 10)
                          (mapv #(hash-map :file (:file %)
                                          :local-imports (:local %)
                                          :total-imports (count (:imports %)))))
                load-bearing (->> rev-deps
                                  (sort-by #(count (val %)) >)
                                  (take 10)
                                  (mapv (fn [[f deps]]
                                          {:file f :depended-on-by (count deps)
                                           :dependents (vec (sort deps))})))
                ext-freq (->> entries
                              (mapcat :external)
                              frequencies
                              (sort-by val >)
                              (take 15)
                              vec)]
            (cond-> {:path          path
                     :language      lang
                     :total-files   (count entries)
                     :hubs          hubs
                     :load-bearing  load-bearing
                     :external-deps ext-freq
                     :graph         entries}
              warning (assoc :warning warning))))))))

;; -------------------------------------------------------------------
;; context — usages with enclosing function
;; -------------------------------------------------------------------

(defn context
  "Usages with enclosing function name. Not just line numbers —
   'called from handle_query, plan_step, and execute_bridge.'"
  [symbol path]
  (let [path    (or path ".")
        [lang warning] (core/resolve-lang (core/detect-lang path))
        fn-pat  (get-in @core/lang-patterns [lang :functions])
        matches (:matches (usages symbol path))]
    (if-not fn-pat
      (cond-> {:symbol symbol :path path :matches matches}
        warning (assoc :warning warning))
      ;; For each match, find the nearest preceding function definition in the same file
      (let [;; Get all function definitions
            all-fns (->> (core/grepf fn-pat path)
                         (group-by :file))
            ;; For each usage, find the enclosing function
            enriched (->> matches
                          (mapv (fn [m]
                                  (let [file-fns (get all-fns (:file m) [])
                                        ;; Find the nearest function defined before this line
                                        enclosing (->> file-fns
                                                       (filter #(< (:line %) (:line m)))
                                                       (sort-by :line >)
                                                       first)]
                                    (assoc m :in (when enclosing
                                                   (core/extract-fn-name (:text enclosing) lang)))))))]
        (cond-> {:symbol   symbol
                 :path     path
                 :count    (count enriched)
                 :matches  enriched
                 :summary  (->> enriched
                                (map :in)
                                (remove nil?)
                                frequencies
                                (sort-by val >)
                                vec)}
          warning (assoc :warning warning))))))

;; -------------------------------------------------------------------
;; patterns — inductive convention discovery
;; -------------------------------------------------------------------

(defn patterns
  "Discover the conventions this codebase actually follows.
   Not prescriptive (antipatterns) — descriptive. What patterns exist?
   Learn the codebase's own dialect."
  [path]
  (let [path (or path ".")
        raw  (shape-raw path)
        lang (:lang raw)]
    (if-not raw
      {:path path :error "No patterns for this language yet"}
      (let [fns    (:functions raw)
            types  (:types raw)

            ;; Function naming conventions
            fn-names   (keep :name fns)
            fn-prefixes (->> fn-names
                             (keep #(second (re-find #"^([a-z]+_)" %)))
                             frequencies
                             (filter #(>= (val %) 3))  ;; at least 3 uses = convention
                             (sort-by val >)
                             vec)
            fn-suffixes (->> fn-names
                             (keep #(second (re-find #"_([a-z]+)$" %)))
                             frequencies
                             (filter #(>= (val %) 3))
                             (sort-by val >)
                             vec)

            ;; Type naming conventions
            type-names  (keep :name types)
            type-suffixes (->> type-names
                               (keep #(second (re-find #"([A-Z][a-z]+)$" %)))
                               frequencies
                               (filter #(>= (val %) 2))
                               (sort-by val >)
                               vec)

            ;; File naming conventions
            files  (->> (file-seq (io/file path))
                        (filter #(.isFile %))
                        (remove #(some core/skip-dirs (str/split (.getPath %) #"/")))
                        (filter #(re-find core/source-exts (.getName %)))
                        (map #(.getName %)))
            file-patterns (->> files
                               (keep #(second (re-find #"^(.+?)[_.]" %)))
                               frequencies
                               (filter #(>= (val %) 2))
                               (sort-by val >)
                               vec)
            file-suffixes (->> files
                               (keep (fn [f]
                                       (let [no-ext (if-let [d (str/last-index-of f ".")] (subs f 0 d) f)]
                                         (second (re-find #"[_.]([a-z]+)$" no-ext)))))
                               frequencies
                               (filter #(>= (val %) 2))
                               (sort-by val >)
                               vec)

            ;; Structural patterns: functions per file distribution
            by-file    (->> fns (group-by :file))
            fns-per-file (->> by-file
                              (map (fn [[_ fs]] (count fs)))
                              sort vec)
            median-fns (when (seq fns-per-file)
                         (nth fns-per-file (/ (count fns-per-file) 2)))

            ;; Visibility patterns (Rust-specific)
            visibility (when (= lang :rust)
                         (let [pub-fns  (count (filter #(re-find #"^\s*pub\s" (:text %)) fns))
                               priv-fns (- (count fns) pub-fns)]
                           {:public pub-fns :private priv-fns
                            :ratio (when (pos? (count fns))
                                     (str (Math/round (* 100.0 (/ pub-fns (count fns)))) "% public"))}))]

        {:path       path
         :language   lang
         :total-fns  (count fns)
         :total-types (count types)
         :naming
         {:fn-prefixes   fn-prefixes
          :fn-suffixes   fn-suffixes
          :type-suffixes type-suffixes
          :file-prefixes file-patterns
          :file-suffixes file-suffixes}
         :structure
         {:fns-per-file    {:median median-fns :distribution fns-per-file}
          :files-with-fns  (count by-file)
          :visibility      visibility}
         :conventions
         (vec (remove nil?
           [(when (seq fn-prefixes)
              (str "Function prefix conventions: "
                   (str/join ", " (map #(str (key %) " (" (val %) "x)") (take 5 fn-prefixes)))))
            (when (seq fn-suffixes)
              (str "Function suffix conventions: "
                   (str/join ", " (map #(str (key %) " (" (val %) "x)") (take 5 fn-suffixes)))))
            (when (seq type-suffixes)
              (str "Type suffix conventions: "
                   (str/join ", " (map #(str (key %) " (" (val %) "x)") (take 5 type-suffixes)))))
            (when (seq file-suffixes)
              (str "File suffix conventions: "
                   (str/join ", " (map #(str (key %) " (" (val %) "x)") (take 5 file-suffixes)))))
            (when (and median-fns (> median-fns 10))
              (str "Files tend to be large (median " median-fns " functions)"))
            (when (and visibility (> (get-in visibility [:public] 0) (* 2 (get-in visibility [:private] 0))))
              "Most functions are public — consider tightening visibility")]))}))))
