#!/usr/bin/env bb

;; spai-mcp.bb — MCP server exposing spai commands as native Claude Code tools
;;
;; Protocol: JSON-RPC 2.0 over stdio (newline-delimited)
;; Register: claude mcp add --transport stdio spai -- bb spai-mcp.bb
;; Or: add to .mcp.json in project root

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.shell :refer [sh]])

(defn log [& args]
  (binding [*out* *err*]
    (apply println "[spai-mcp]" args)))

(defn send! [msg]
  (println (json/generate-string msg))
  (flush))

(defn respond [id result]
  (send! {:jsonrpc "2.0" :id id :result result}))

(defn respond-error [id code message]
  (send! {:jsonrpc "2.0" :id id :error {:code code :message message}}))

;; --- Run spai commands ---

(defn run-spai [& args]
  (log "running:" (str/join " " (cons "spai" args)))
  (let [result (apply sh "spai" (map str args))]
    (if (zero? (:exit result))
      {:content [{:type "text" :text (:out result)}]}
      {:content [{:type "text"
                  :text (str (:out result) "\n" (:err result))}]
       :isError true})))

;; --- Update check (runs once at startup, result cached) ---

(def update-status (atom {:status :pending}))

(defn check-update-bg!
  "Fire-and-forget update check. Result lands in update-status atom."
  []
  (future
    (try
      (let [{:keys [exit out]} (sh "spai" "update")]
        (if (zero? exit)
          (let [parsed (try (edn/read-string out) (catch Exception _ nil))]
            (reset! update-status (or parsed {:status :unknown})))
          (reset! update-status {:status :check-failed})))
      (catch Exception _
        (reset! update-status {:status :check-failed})))))

(defn update-tool-description []
  (case (:status @update-status)
    :update-available
    (str "UPDATE AVAILABLE — new version of spai (current: "
         (:current @update-status) ", latest: " (:latest @update-status)
         "). Call with install=true to update. New tools and fixes may be available.")
    :up-to-date
    "Check for spai updates. You're on the latest version."
    :check-failed
    "Check for spai updates. Last check failed — call to retry."
    "Check for spai updates and install them. Call with install=true to update."))

;; --- Tool definitions ---
;;
;; DESCRIPTIONS MATTER. Future Claude sees ONLY these descriptions.
;; Each must explain: what it does, what it replaces, when to reach for it.

(def tools
  [;; === Memory (no built-in equivalent) ===

   {:name "memory"
    :description "Read Claude's persistent KG memory — insights from ALL previous sessions, stored as RDF. Use at session start to see what predecessors learned. Use before making architectural decisions. Replaces: nothing (unique). Output shows [uuid] brackets for each insight — use these with spai_memory_forget."
    :inputSchema
    {:type "object"
     :properties
     {:search {:type "string"
               :description "Search term to filter insights (optional, omit to list all)"}
      :show_all {:type "boolean"
                 :description "Include superseded insights (default: false, only shows current truth)"}}}}

   {:name "remember"
    :description "Store an insight in persistent KG memory for future Claude sessions. Use before context compacts, or when you learn something that would save the next Claude time. Replaces: writing to MEMORY.md files (use BOTH — file memory is system prompt, KG memory survives compaction)."
    :inputSchema
    {:type "object"
     :properties
     {:text {:type "string"
             :description "The insight text to remember"}
      :topics {:type "array"
               :items {:type "string"}
               :description "Topic tags e.g. [\"spoqe/architecture\", \"spoqe/planning\"]"}}
     :required ["text"]}}

   {:name "memory_forget"
    :description "Delete an insight from KG memory by UUID. Get IDs from spai_memory output (shown in [brackets]). Use to clean up wrong, outdated, or duplicate insights."
    :inputSchema
    {:type "object"
     :properties
     {:id {:type "string"
           :description "UUID of the insight to delete"}}
     :required ["id"]}}

   {:name "memory_link"
    :description "Link two insights with a semantic relationship (skos:related, skos:broader, skos:narrower). Symmetric predicates automatically write both directions. Use to build a connected knowledge graph from flat insights."
    :inputSchema
    {:type "object"
     :properties
     {:from_id {:type "string"
                :description "UUID of the source insight"}
      :predicate {:type "string"
                  :description "Relationship type: skos/related, skos/broader, or skos/narrower"}
      :to_id {:type "string"
              :description "UUID of the target insight"}}
     :required ["from_id" "predicate" "to_id"]}}

   {:name "memory_supersede"
    :description "Replace an outdated insight with improved text (knowledge compression). Creates new insight, marks old as superseded. Superseded insights hidden from default listing. Use when an insight is wrong, outdated, or can be expressed better."
    :inputSchema
    {:type "object"
     :properties
     {:old_id {:type "string"
               :description "UUID of the insight to supersede"}
      :text {:type "string"
             :description "The new, improved insight text"}
      :topics {:type "array"
               :items {:type "string"}
               :description "Topic tags for the new insight (optional)"}}
     :required ["old_id" "text"]}}

   {:name "remember_batch"
    :description "Store multiple insights in one operation (single INSERT DATA). Use when you have 2+ insights to save — avoids multiple round-trips. All insights share the same topic/tags."
    :inputSchema
    {:type "object"
     :properties
     {:texts {:type "array"
              :items {:type "string"}
              :description "Array of insight texts to remember"}
      :topics {:type "array"
               :items {:type "string"}
               :description "Shared topic tags for all insights (optional)"}}
     :required ["texts"]}}

   ;; === Build (replaces cargo build | grep anti-pattern) ===

   {:name "errors_rust"
    :description "Build Rust project, return structured errors AND warnings as EDN. Warnings grouped by lint (dead_code, unused_variables, etc.) with locations and suggestions. ONE call replaces: cargo build 2>&1 | grep. Returns counts, locations, suggestions. Rust only (uses cargo)."
    :inputSchema
    {:type "object"
     :properties
     {:path {:type "string"
             :description "Working directory to build in (optional, defaults to current project)"}
      :package {:type "string"
                :description "Cargo package to build (optional, builds whole workspace if omitted)"}}}}

   ;; === Code exploration (replaces chains of grep/read) ===

   {:name "shape"
    :description "Module structure: all functions, types, impls, imports in a directory, grouped by file. ONE call replaces: 3-4 Grep calls to understand a module's API surface. Use when entering unfamiliar code. Languages: Rust, TypeScript, Clojure, Python, Go, PHP, Java — auto-detected from file extensions."
    :inputSchema
    {:type "object"
     :properties
     {:path {:type "string"
             :description "Directory to analyze"}
      :full {:type "boolean"
             :description "Include full signatures (default false)"}}
     :required ["path"]}}

   {:name "who"
    :description "Reverse dependencies: who imports this file? Use BEFORE editing a file to understand downstream impact. ONE call replaces: grep for the filename across the codebase + manual filtering. Languages: Rust, TypeScript, Clojure, Python, Go, PHP, Java — auto-detected."
    :inputSchema
    {:type "object"
     :properties
     {:file {:type "string"
             :description "File to find importers of"}
      :path {:type "string"
             :description "Directory scope to search in"}}
     :required ["file"]}}

   {:name "blast"
    :description "Full blast radius for a symbol: definition site, all callers, all importers, related tests, git authors, risk assessment. ONE call replaces: grep for definition + grep for usages + grep for test files + git log. Use before renaming, deleting, or changing a function's signature. Languages: Rust, TypeScript, Clojure, Python, Go, PHP, Java — auto-detected."
    :inputSchema
    {:type "object"
     :properties
     {:symbol {:type "string"
               :description "Symbol/function name to analyze"}
      :path {:type "string"
             :description "Directory scope (optional)"}}
     :required ["symbol"]}}

   {:name "context"
    :description "Symbol usages WITH enclosing function names — see WHICH functions call a symbol, not just line numbers. ONE call replaces: grep for symbol + manually reading surrounding code to find the caller. Use to understand how a function is used across the codebase. Languages: Rust, TypeScript, Clojure, Python, Go, PHP, Java — auto-detected."
    :inputSchema
    {:type "object"
     :properties
     {:symbol {:type "string"
               :description "Symbol to find usages of"}
      :path {:type "string"
             :description "Directory scope (optional)"}}
     :required ["symbol"]}}

   ;; === Git-powered analysis (no built-in equivalent) ===

   {:name "related"
    :description "Co-change analysis: files that move together in git history, revealing implicit coupling. If file A changes, which other files usually change too? Use BEFORE refactoring to find hidden dependencies that imports don't show. ONE call replaces: git log --follow + manual correlation across commits. Language-agnostic (git-based)."
    :inputSchema
    {:type "object"
     :properties
     {:file {:type "string"
             :description "File to find co-changed files for"}}
     :required ["file"]}}

   {:name "drift"
    :description "Architecture health: where implicit coupling (co-change) diverges from explicit coupling (imports). Finds files that SHOULD be in the same module but aren't, or files in the same module that never change together. Use to understand architecture debt before large refactors. Language-agnostic (git-based)."
    :inputSchema
    {:type "object"
     :properties
     {:path {:type "string"
             :description "Directory scope (optional)"}}}}

   {:name "narrative"
    :description "Biography of a file: creation, growth phases, major refactors, stabilization. Tells the STORY of how code got to its current state. Use when inheriting unfamiliar code to understand its evolution before making changes. Language-agnostic (git-based)."
    :inputSchema
    {:type "object"
     :properties
     {:file {:type "string"
             :description "File to get the history narrative for"}}
     :required ["file"]}}

   ;; === Update (dynamic description set at tools/list time) ===

   {:name "update"
    :description :dynamic  ;; replaced at tools/list time
    :inputSchema
    {:type "object"
     :properties
     {:install {:type "boolean"
                :description "Set to true to install the update (default: just check)"}}}}])

;; --- Tool dispatch ---

(defn call-tool [name args]
  (case name
    "memory"
    (let [search (get args "search")
          show-all (get args "show_all")]
      (cond
        (and search show-all) (run-spai "memory" search "--all")
        search                (run-spai "memory" search)
        show-all              (run-spai "memory" "--all")
        :else                 (run-spai "memory")))

    "remember"
    (let [text (get args "text")
          topics (get args "topics" [])
          bad (seq (filter #(str/includes? % ":") topics))]
      (if bad
        {:content [{:type "text"
                    :text (str "Error: topics must use / not : as separator.\n"
                               "  Bad: " (str/join ", " bad) "\n"
                               "  Fix: " (str/join ", " (map #(str/replace % ":" "/") bad)))}]
         :isError true}
        (let [topic-args (mapv #(str "+" %) topics)]
          (apply run-spai "remember" text topic-args))))

    "remember_batch"
    (let [texts (get args "texts" [])
          topics (get args "topics" [])
          bad (seq (filter #(str/includes? % ":") topics))]
      (if bad
        {:content [{:type "text"
                    :text (str "Error: topics must use / not : as separator.\n"
                               "  Bad: " (str/join ", " bad) "\n"
                               "  Fix: " (str/join ", " (map #(str/replace % ":" "/") bad)))}]
         :isError true}
        (let [topic-args (mapv #(str "+" %) topics)]
          (apply run-spai "remember-batch" (concat texts topic-args)))))

    "memory_forget"
    (run-spai "memory" "forget" (get args "id"))

    "memory_link"
    (run-spai "memory" "link" (get args "from_id") (get args "predicate") (get args "to_id"))

    "memory_supersede"
    (let [old-id (get args "old_id")
          text (get args "text")
          topics (get args "topics" [])
          topic-args (mapv #(str "+" %) topics)]
      (apply run-spai "supersede" old-id text topic-args))

    "errors_rust"
    (let [path (get args "path")
          pkg  (get args "package")]
      (cond
        (and path pkg)  (run-spai "errors-rust" path "-p" pkg)
        path            (run-spai "errors-rust" path)
        pkg             (run-spai "errors-rust" "-p" pkg)
        :else           (run-spai "errors-rust")))

    "shape"
    (if (get args "full")
      (run-spai "shape" (get args "path") "--full")
      (run-spai "shape" (get args "path")))

    "who"
    (if-let [path (get args "path")]
      (run-spai "who" (get args "file") path)
      (run-spai "who" (get args "file")))

    "blast"
    (if-let [path (get args "path")]
      (run-spai "blast" (get args "symbol") path)
      (run-spai "blast" (get args "symbol")))

    "context"
    (if-let [path (get args "path")]
      (run-spai "context" (get args "symbol") path)
      (run-spai "context" (get args "symbol")))

    "related"
    (run-spai "related" (get args "file"))

    "drift"
    (if-let [path (get args "path")]
      (run-spai "drift" path)
      (run-spai "drift"))

    "narrative"
    (run-spai "narrative" (get args "file"))

    "update"
    (if (get args "install")
      (run-spai "update" "--install")
      (run-spai "update"))

    ;; Unknown
    {:content [{:type "text" :text (str "Unknown tool: " name)}]
     :isError true}))

;; --- Request handler ---

(defn handle [{:strs [id method params]}]
  (case method
    "initialize"
    (do
      (log "initialized")
      (check-update-bg!)
      (respond id
        {:protocolVersion "2024-11-05"
         :capabilities {:tools {}}
         :serverInfo {:name "spai" :version "0.3.0"}}))

    "tools/list"
    (let [live-tools (mapv (fn [t]
                             (if (= (:description t) :dynamic)
                               (assoc t :description (update-tool-description))
                               t))
                           tools)]
      (respond id {:tools live-tools}))

    "tools/call"
    (let [tool-name (get params "name")
          tool-args (get params "arguments" {})]
      (log "call:" tool-name)
      (respond id (call-tool tool-name tool-args)))

    ;; Notifications — no response
    "notifications/initialized" nil
    "notifications/cancelled"   nil

    ;; Unknown
    (when id
      (respond-error id -32601 (str "Method not found: " method)))))

;; --- Main loop ---

(log "starting...")
(doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (when-not (str/blank? line)
    (try
      (handle (json/parse-string line))
      (catch Exception e
        (log "error:" (.getMessage e))))))
