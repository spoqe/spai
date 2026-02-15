;; spai/code — shape, usages, definition, sig, who, context, patterns
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
