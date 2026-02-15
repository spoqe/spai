#!/usr/bin/env bb
;; spai-edit: Structural editing for Clojure and EDN
;;
;; sed operates on lines. This operates on forms.
;; Uses rewrite-clj (bundled in bb) for zipper-based navigation.
;;
;; Works on: .clj, .cljs, .cljc, .edn, .bb
;; (Anything that's s-expressions.)
;;
;; Usage:
;;   spai-edit find-form <file> <name>           Show a named form
;;   spai-edit replace-form <file> <name> <new>  Replace a named form's body
;;   spai-edit validate <file>                    Check structure
;;   spai-edit forms <file>                       List all top-level forms

(require '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as node]
         '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

;; -------------------------------------------------------------------
;; Zipper helpers
;; -------------------------------------------------------------------

(defn- zloc-of-file
  "Parse a file into a zipper. Returns {:ok zloc} or {:error msg}."
  [path]
  (try
    {:ok (z/of-file path {:track-position? true})}
    (catch Exception e
      {:error (str "Parse error: " (.getMessage e))})))

(defn- top-level-forms
  "Walk all top-level forms in a zipper. Returns seq of zlocs."
  [zloc]
  (loop [loc zloc
         forms []]
    (if (nil? loc)
      forms
      (recur (z/right loc)
             (conj forms loc)))))

(defn- form-name
  "Extract the name from a top-level form, if it has one.
   (defn foo ...) → foo, (def bar ...) → bar, etc."
  [zloc]
  (when (z/list? zloc)
    (let [first-child (z/down zloc)]
      (when first-child
        (let [sym (z/sexpr first-child)]
          (when (and (symbol? sym)
                     (re-find #"^def" (name sym)))
            ;; The name is the next non-whitespace thing after the def* keyword
            (let [name-node (z/right first-child)]
              (when name-node
                (try (str (z/sexpr name-node))
                     (catch Exception _ nil))))))))))

(defn- find-named-form
  "Find a top-level form by its defined name. Returns the zloc of the form."
  [zloc target-name]
  (->> (top-level-forms zloc)
       (filter #(= target-name (form-name %)))
       first))

;; -------------------------------------------------------------------
;; Commands
;; -------------------------------------------------------------------

(defn forms
  "List all top-level forms with names, types, and line numbers."
  [path]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      {:file path
       :forms
       (->> (top-level-forms ok)
            (mapv (fn [zloc]
                    (let [pos (z/position zloc)]
                      (merge
                        {:line (first pos)
                         :type (when (z/list? zloc)
                                 (try (str (z/sexpr (z/down zloc)))
                                      (catch Exception _ "?")))
                         :preview (let [s (z/string zloc)]
                                    (if (> (count s) 80)
                                      (str (subs s 0 77) "...")
                                      s))}
                        (when-let [n (form-name zloc)]
                          {:name n}))))))})))

(defn find-form
  "Find a named form and return it. Structural boundary — no paren counting."
  [path target-name]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      (if-let [found (find-named-form ok target-name)]
        {:file   path
         :name   target-name
         :line   (first (z/position found))
         :source (z/string found)}
        {:file path :name target-name :error "Form not found"}))))

(defn replace-form
  "Replace a named form entirely. Takes new source as a string.
   Writes the file back. Returns the diff."
  [path target-name new-source]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      (if-let [found (find-named-form ok target-name)]
        (let [old-src  (z/string found)
              new-node (z/node (z/of-string new-source))
              result   (z/root-string (z/replace found new-node))]
          (spit path result)
          {:file    path
           :name    target-name
           :replaced true
           :old-lines (count (str/split-lines old-src))
           :new-lines (count (str/split-lines new-source))})
        {:file path :name target-name :error "Form not found"}))))

(defn insert-after
  "Insert a new form after a named form. Preserves spacing."
  [path target-name new-source]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      (if-let [found (find-named-form ok target-name)]
        (let [new-node   (z/node (z/of-string new-source))
              with-space (-> found
                             (z/insert-right new-node)
                             (z/insert-right (node/newlines 2)))
              result     (z/root-string with-space)]
          (spit path result)
          {:file     path
           :after    target-name
           :inserted true})
        {:file path :after target-name :error "Form not found"}))))

(defn validate
  "Structural validation: can the file be parsed? Report first error with location."
  [path]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :valid false :error error}
      (let [form-count (count (top-level-forms ok))]
        {:file       path
         :valid      true
         :form-count form-count}))))

(defn extract-body
  "Extract just the body of a named form (everything after the argvec).
   Useful for reading function bodies without the def/name/args boilerplate."
  [path target-name]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      (if-let [found (find-named-form ok target-name)]
        (let [inner  (z/down found)
              ;; Skip: defn, name, optional docstring, argvec
              after-keyword (z/right inner)
              after-name    (z/right after-keyword)
              ;; Check if next is a string (docstring)
              maybe-doc     (when after-name
                              (try
                                (let [v (z/sexpr after-name)]
                                  (when (string? v) after-name))
                                (catch Exception _ nil)))
              after-doc     (if maybe-doc (z/right maybe-doc) after-name)
              ;; Check if next is a vector (argvec)
              maybe-args    (when after-doc
                              (try
                                (let [v (z/sexpr after-doc)]
                                  (when (vector? v) after-doc))
                                (catch Exception _ nil)))
              body-start    (if maybe-args (z/right maybe-args) after-doc)]
          ;; Collect all remaining body forms
          (if body-start
            {:file path
             :name target-name
             :body (->> (iterate z/right body-start)
                        (take-while some?)
                        (mapv z/string))}
            {:file path :name target-name :body []}))
        {:file path :name target-name :error "Form not found"}))))

(defn replace-body
  "Replace just the body of a named form, keeping def/name/args/docstring intact.
   Rebuilds the form from prefix parts + new body to avoid z/remove DFS issues."
  [path target-name new-body-source]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      (if-let [found (find-named-form ok target-name)]
        (let [inner  (z/down found)
              ;; Collect prefix parts: keyword, name, optional docstring, argvec
              parts  (loop [loc (z/right inner)  ; skip the defn/def keyword
                            prefix [(z/string inner)]]
                       (if (nil? loc)
                         {:prefix prefix :body-start nil}
                         (let [v (try (z/sexpr loc) (catch Exception _ :unknown))]
                           (cond
                             ;; Symbol = name
                             (symbol? v)
                             (recur (z/right loc) (conj prefix (z/string loc)))
                             ;; String = docstring
                             (string? v)
                             (recur (z/right loc) (conj prefix (z/string loc)))
                             ;; Vector = argvec (last prefix part)
                             (vector? v)
                             {:prefix (conj prefix (z/string loc))
                              :body-start (z/right loc)}
                             ;; Anything else = body started (no argvec, e.g. def)
                             :else
                             {:prefix prefix :body-start loc}))))
              {:keys [prefix body-start]} parts]
          (if body-start
            (let [;; Build new form: (prefix-parts... new-body)
                  new-form (str "(" (str/join " " prefix) "\n  " new-body-source ")")
                  new-node (z/node (z/of-string new-form))
                  result   (z/root-string (z/replace found new-node))]
              (spit path result)
              {:file path :name target-name :replaced true})
            {:file path :name target-name :error "No body found"}))
        {:file path :name target-name :error "Form not found"}))))

;; -------------------------------------------------------------------
;; Map operations (for EDN config files: sources.edn, defs, etc.)
;; -------------------------------------------------------------------

(defn- navigate-path
  "Navigate a zipper into a map by key path. Returns the zloc at the value,
   or nil if any key is missing."
  [zloc key-path]
  (reduce (fn [loc k]
            (when loc
              (let [kw (if (str/starts-with? k ":")
                         (read-string k)
                         (read-string (str ":" k)))]
                (z/get loc kw))))
          zloc
          key-path))

(defn get-in-edn
  "Get a value at a key path in an EDN file. Like clojure.core/get-in."
  [path key-path]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      (if-let [found (navigate-path ok key-path)]
        {:file    path
         :path    (vec key-path)
         :value   (z/string found)}
        {:file path :path (vec key-path) :error "Key path not found"}))))

(defn set-in
  "Set a value at a key path in an EDN file. Like clojure.core/assoc-in.
   Writes the file."
  [path key-path new-value-source]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      (if-let [found (navigate-path ok key-path)]
        (let [new-node (z/node (z/of-string new-value-source))
              result   (z/root-string (z/replace found new-node))]
          (spit path result)
          {:file path :path (vec key-path) :set true})
        {:file path :path (vec key-path) :error "Key path not found"}))))

(defn merge-in
  "Merge key-value pairs into a map at a key path. Like (update-in m path merge {...}).
   Takes pairs as alternating key value strings. Writes the file."
  [path key-path & kv-pairs]
  (let [{:keys [ok error]} (zloc-of-file path)]
    (if error
      {:file path :error error}
      (if-let [found (navigate-path ok key-path)]
        (let [pairs   (partition 2 kv-pairs)
              updated (reduce (fn [loc [k v]]
                                (let [kw  (read-string k)
                                      val (z/node (z/of-string v))]
                                  (z/assoc loc kw val)))
                              found
                              pairs)
              result  (z/root-string updated)]
          (spit path result)
          {:file path :path (vec key-path) :merged (mapv first pairs)})
        {:file path :path (vec key-path) :error "Key path not found"}))))

;; -------------------------------------------------------------------
;; CLI
;; -------------------------------------------------------------------

(def commands
  {:forms        {:args    "[file]"
                  :returns "all top-level forms with names and line numbers"
                  :example "spai-edit forms explore.clj"}
   :find-form    {:args    "[file] [name]"
                  :returns "the full source of a named form"
                  :example "spai-edit find-form explore.clj tests"}
   :replace-form {:args    "[file] [name] [new-source]"
                  :returns "replaces the entire named form, writes file"
                  :example "spai-edit replace-form f.clj foo '(defn foo [x] (inc x))'"}
   :insert-after {:args    "[file] [name] [new-source]"
                  :returns "inserts a new form after the named one"
                  :example "spai-edit insert-after f.clj foo '(defn bar [x] x)'"}
   :extract-body {:args    "[file] [name]"
                  :returns "just the body forms (no def/name/args)"
                  :example "spai-edit extract-body explore.clj shape"}
   :replace-body {:args    "[file] [name] [new-body]"
                  :returns "replaces just the body, keeps signature intact"
                  :example "spai-edit replace-body f.clj foo '(inc x)'"}
   :validate     {:args    "[file]"
                  :returns "parse check with error location"
                  :example "spai-edit validate explore.clj"}
   :get-in       {:args    "[file] [key...]"
                  :returns "value at key path in an EDN map"
                  :example "spai-edit get-in sources.edn :sources :kg"}
   :set-in       {:args    "[file] [key...] [value]"
                  :returns "sets value at key path, writes file"
                  :example "spai-edit set-in sources.edn :sources :kg :endpoint '\"http://new\"'"}
   :merge-in     {:args    "[file] [key...] -- [k v ...]"
                  :returns "merges k/v pairs into map at path, writes file"
                  :example "spai-edit merge-in sources.edn :sources :kg -- :timeout 5000 :auth :bearer"}})

(let [[command & args] *command-line-args*]
  (case command
    "forms"        (pp/pprint (forms (first args)))
    "find-form"    (pp/pprint (find-form (first args) (second args)))
    "replace-form" (pp/pprint (replace-form (first args) (second args) (nth args 2)))
    "insert-after" (pp/pprint (insert-after (first args) (second args) (nth args 2)))
    "extract-body" (pp/pprint (extract-body (first args) (second args)))
    "replace-body" (pp/pprint (replace-body (first args) (second args) (nth args 2)))
    "validate"     (pp/pprint (validate (first args)))
    "get-in"       (pp/pprint (get-in-edn (first args) (rest args)))
    "set-in"       (let [file     (first args)
                         path+val (rest args)
                         key-path (butlast path+val)
                         value    (last path+val)]
                     (pp/pprint (set-in file (vec key-path) value)))
    "merge-in"     (let [file      (first args)
                         remaining (rest args)
                         sep-idx   (.indexOf (vec remaining) "--")
                         key-path  (if (pos? sep-idx) (subvec (vec remaining) 0 sep-idx) [])
                         kv-pairs  (if (pos? sep-idx) (subvec (vec remaining) (inc sep-idx)) (vec remaining))]
                     (pp/pprint (apply merge-in file key-path kv-pairs)))
    ("help" "--help" "-h") (pp/pprint commands)
    nil (pp/pprint commands)
    (do (println (str "Unknown command: " command "\n"))
        (pp/pprint commands)
        (System/exit 1))))
