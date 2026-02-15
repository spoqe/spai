;; spai/code — shape, usages, definition, sig, who, deps, context, patterns
;; Code structure analysis commands.

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

;; -------------------------------------------------------------------
;; who — reverse file dependencies
;; -------------------------------------------------------------------

(def ^:private import-patterns
  "Patterns for finding import/use statements, by language."
  {:rust       {:pattern "^use\\s+" :extract #"use\s+(?:crate::)?(\S+?)(?:::\{|;)"}
   :typescript {:pattern "^import\\s+" :extract #"from\s+['\"]([^'\"]+)['\"]"}
   :clojure    {:pattern "\\(:?require\\s+" :extract #"\[([^\s\]]+)"}
   :python     {:pattern "^(import|from)\\s+" :extract #"(?:from|import)\s+(\S+)"}
   :go         {:pattern "^import\\s+" :extract #"\"([^\"]+)\""}})

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
        ;; Also try path-based module name (e.g., service/mod -> federation)
        mod-name (let [rel (relativize (str (.getPath (io/file path)) "/") file)
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
                               (or (grepf term path "-w") [])))
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
;; -------------------------------------------------------------------

(defn- find-src-root
  "Find the src/ root for resolving crate:: imports.
   Walks up from path looking for Cargo.toml, returns its src/ subdir."
  [start-path]
  (loop [dir (let [f (io/file start-path)]
               (if (.isFile f) (.getParentFile f) f))]
    (when dir
      (let [cargo (io/file dir "Cargo.toml")
            src   (io/file dir "src")]
        (if (and (.exists cargo) (.isDirectory src))
          (.getCanonicalPath src)
          (recur (.getParentFile dir)))))))

(defn- extract-rust-imports
  "Extract all use/mod declarations from a Rust file with module paths."
  [file-path]
  (let [;; use statements
        use-hits  (or (grepf "^\\s*(pub\\s+)?use\\s+" file-path) [])
        ;; mod declarations (these define submodules)
        mod-hits  (or (grepf "^\\s*(pub(\\(crate\\))?\\s+)?mod\\s+\\w+\\s*;" file-path) [])
        parse-use (fn [text]
                    ;; Extract the full path from: use crate::foo::bar::{Baz, Qux};
                    ;; We want "crate::foo::bar" (the module, not the items)
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

(defn- extract-ts-imports
  "Extract import paths from a TypeScript file."
  [file-path]
  (let [hits (or (grepf "^import\\s+" file-path) [])]
    (->> hits
         (keep (fn [h]
                 (when-let [m (re-find #"from\s+['\"]([^'\"]+)['\"]" (:text h))]
                   {:module (second m) :line (:line h) :kind :import}))))))

(defn- extract-python-imports
  "Extract imports from a Python file with proper from/import handling."
  [file-path]
  (let [hits (or (grepf "^\\s*(import|from)\\s+" file-path) [])]
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

(defn- find-python-root
  "Find the Python project root. Walks up looking for pyproject.toml, setup.py, etc."
  [start-path]
  (loop [dir (let [f (io/file start-path)]
               (if (.isFile f) (.getParentFile f) f))]
    (when dir
      (if (or (.exists (io/file dir "pyproject.toml"))
              (.exists (io/file dir "setup.py"))
              (.exists (io/file dir "setup.cfg")))
        (.getCanonicalPath dir)
        (recur (.getParentFile dir))))))

(defn- resolve-python-module
  "Try to resolve a Python module to a project file.
   Handles both absolute (foo.bar) and relative (.foo) imports."
  [mod-str current-file project-root]
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
            ;; Walk up one less than dot count (. = current, .. = parent)
            base   (loop [d (io/file cur-dir) n (dec dots)]
                     (if (or (<= n 0) (nil? d)) d
                       (recur (.getParentFile d) (dec n))))
            as-path (str/replace rest "." "/")]
        (when base
          (if (seq as-path)
            (try-file (.getPath base) as-path)
            ;; from . import foo → look for __init__.py in current package
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

(defn- extract-generic-imports
  "Extract imports using the generic import-patterns config."
  [file-path lang]
  (when-let [ip (get import-patterns lang)]
    (let [hits (or (grepf (:pattern ip) file-path) [])]
      (->> hits
           (keep (fn [h]
                   (let [matched (re-find (:extract ip) (:text h))]
                     (when matched
                       {:module (if (vector? matched) (last matched) matched)
                        :line (:line h)
                        :kind :import}))))))))

(defn- resolve-rust-module
  "Try to resolve a Rust module path to a project file."
  [mod-str src-root current-file]
  (let [clean   (-> mod-str
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

(defn- resolve-ts-module
  "Try to resolve a TypeScript relative import to a project file."
  [mod-str current-file]
  (when (str/starts-with? mod-str ".")
    (let [cur-dir (.getParent (io/file current-file))
          base    (str cur-dir "/" mod-str)]
      (or (let [f (io/file (str base ".ts"))]     (when (.exists f) (.getCanonicalPath f)))
          (let [f (io/file (str base ".tsx"))]    (when (.exists f) (.getCanonicalPath f)))
          (let [f (io/file (str base "/index.ts"))]  (when (.exists f) (.getCanonicalPath f)))
          (let [f (io/file (str base "/index.tsx"))] (when (.exists f) (.getCanonicalPath f)))))))

(defn- classify-import
  "Classify an import as :local or :external."
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
    :clojure    :unknown
    :go         :unknown
    :unknown))

(defn deps
  "Import/dependency graph.
   Single file: what this file imports, resolved to project files.
   Directory: full graph with hub/load-bearing analysis."
  [path]
  (let [path (or path ".")
        lang (detect-lang path)
        f    (io/file path)]
    (if-not (get import-patterns lang)
      {:path path :error "No import patterns for this language yet"}
      (let [files (if (.isFile f)
                    [(.getCanonicalPath f)]
                    (->> (file-seq f)
                         (filter #(.isFile %))
                         (remove #(some skip-dirs (str/split (.getPath %) #"/")))
                         (filter #(re-find source-exts (.getName %)))
                         (map #(.getCanonicalPath %))
                         sort))
            ;; For Rust: find crate root for resolving crate:: paths
            src-root (when (= lang :rust)
                       (find-src-root (or (first files) path)))
            ;; For Python: find project root for resolving absolute imports
            py-root  (when (= lang :python)
                       (find-python-root (or (first files) path)))
            ;; Use git root or crate root for relativizing paths
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
            ;; Extract + resolve imports for each file
            entries  (mapv
                       (fn [file-path]
                         (let [raw-imports (case lang
                                            :rust       (extract-rust-imports file-path)
                                            :typescript (extract-ts-imports file-path)
                                            :python     (extract-python-imports file-path)
                                            (extract-generic-imports file-path lang))
                               ;; Collect mod names so bare imports can match them
                               mod-names  (when (= lang :rust)
                                            (set (keep (fn [{:keys [kind module]}]
                                                         (when (= kind :mod)
                                                           (str/replace module "self::" "")))
                                                       raw-imports)))
                               imports (mapv (fn [{:keys [module] :as imp}]
                                               (let [;; Bare names matching a mod decl are local
                                                     cls (if (and (= lang :rust)
                                                                  mod-names
                                                                  (contains? mod-names module)
                                                                  (not= :mod (:kind imp)))
                                                           :local
                                                           (classify-import module lang))
                                                     ;; For bare local names, resolve via self::
                                                     ;; For "super" alone, resolve parent mod
                                                     resolve-mod (cond
                                                                   (and (= cls :local) (= lang :rust)
                                                                        (not (re-find #"::" module)))
                                                                   (str "self::" module)

                                                                   (and (= cls :local) (= lang :rust)
                                                                        (= module "super"))
                                                                   "super::mod"

                                                                   :else module)
                                                     resolved (case lang
                                                                :rust (when (= cls :local)
                                                                        (resolve-rust-module resolve-mod src-root file-path))
                                                                :typescript (when (= cls :local)
                                                                              (resolve-ts-module module file-path))
                                                                :python (resolve-python-module module file-path py-root)
                                                                nil)
                                                     ;; For Python :probe — resolution determines local vs external
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
          ;; Single file view
          (first entries)
          ;; Directory view: graph + analysis
          (let [;; Build reverse dependency map from resolved paths
                rev-deps (reduce
                           (fn [acc entry]
                             (reduce (fn [a imp]
                                       (if-let [r (:resolved imp)]
                                         (update a r (fnil conj #{}) (:file entry))
                                         a))
                                     acc (:imports entry)))
                           {} entries)
                ;; Hub files: most imports
                hubs (->> entries
                          (sort-by :local >)
                          (take 10)
                          (mapv #(hash-map :file (:file %)
                                          :local-imports (:local %)
                                          :total-imports (count (:imports %)))))
                ;; Load-bearing files: most depended on
                load-bearing (->> rev-deps
                                  (sort-by #(count (val %)) >)
                                  (take 10)
                                  (mapv (fn [[f deps]]
                                          {:file f :depended-on-by (count deps)
                                           :dependents (vec (sort deps))})))
                ;; External dep frequency
                ext-freq (->> entries
                              (mapcat :external)
                              frequencies
                              (sort-by val >)
                              (take 15)
                              vec)]
            {:path          path
             :language      lang
             :total-files   (count entries)
             :hubs          hubs
             :load-bearing  load-bearing
             :external-deps ext-freq
             :graph         entries}))))))

;; -------------------------------------------------------------------
;; context — usages with enclosing function
;; -------------------------------------------------------------------

(defn context
  "Usages with enclosing function name. Not just line numbers —
   'called from handle_query, plan_step, and execute_bridge.'"
  [symbol path]
  (let [path    (or path ".")
        lang    (detect-lang path)
        fn-pat  (get-in lang-patterns [lang :functions])
        matches (:matches (usages symbol path))]
    (if-not fn-pat
      {:symbol symbol :path path :matches matches}
      ;; For each match, find the nearest preceding function definition in the same file
      (let [;; Get all function definitions
            all-fns (->> (grepf fn-pat path)
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
                                                   (extract-fn-name (:text enclosing) lang)))))))]
        {:symbol   symbol
         :path     path
         :count    (count enriched)
         :matches  enriched
         :summary  (->> enriched
                        (map :in)
                        (remove nil?)
                        frequencies
                        (sort-by val >)
                        vec)}))))

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
                        (remove #(some skip-dirs (str/split (.getPath %) #"/")))
                        (filter #(re-find source-exts (.getName %)))
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
