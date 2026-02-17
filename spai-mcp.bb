#!/usr/bin/env bb

;; spai-mcp.bb — MCP server exposing spai commands as native Claude Code tools
;;
;; Protocol: JSON-RPC 2.0 over stdio (newline-delimited)
;; Register: claude mcp add --transport stdio spai-tools -- bb spai-mcp.bb
;; Or: add to .mcp.json in project root

(require '[cheshire.core :as json]
         '[clojure.string :as str]
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

;; --- Tool definitions ---
;;
;; DESCRIPTIONS MATTER. Future Claude sees ONLY these descriptions.
;; Each must explain: what it does, what it replaces, when to reach for it.

(def tools
  [;; === Memory (no built-in equivalent) ===

   {:name "spai_memory"
    :description "Read Claude's persistent KG memory — insights from ALL previous sessions, stored as RDF. Use at session start to see what predecessors learned. Use before making architectural decisions. Replaces: nothing (unique). Output shows [uuid] brackets for each insight — use these with spai_memory_forget."
    :inputSchema
    {:type "object"
     :properties
     {:search {:type "string"
               :description "Search term to filter insights (optional, omit to list all)"}
      :topic {:type "string"
              :description "Filter by topic keyword e.g. spoqe/architecture (optional)"}}}}

   {:name "spai_remember"
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

   {:name "spai_memory_forget"
    :description "Delete an insight from KG memory by UUID. Get IDs from spai_memory output (shown in [brackets]). Use to clean up wrong, outdated, or duplicate insights."
    :inputSchema
    {:type "object"
     :properties
     {:id {:type "string"
           :description "UUID of the insight to delete"}}
     :required ["id"]}}

   ;; === Build (replaces cargo build | grep anti-pattern) ===

   {:name "spai_errors_rust"
    :description "Build Rust project, return structured errors/warnings as EDN. ONE call replaces: cargo build 2>&1 | grep -E '^error' | head -10. Never run cargo build through Bash and grep the output — use this instead. Returns count, locations, suggestions."
    :inputSchema
    {:type "object"
     :properties
     {:path {:type "string"
             :description "Path to build (optional, defaults to current project)"}}}}

   ;; === Code exploration (replaces chains of grep/read) ===

   {:name "spai_shape"
    :description "Module structure: all functions, types, impls, imports in a directory, grouped by file. ONE call replaces: 3-4 Grep calls to understand a module's API surface. Use when entering unfamiliar code."
    :inputSchema
    {:type "object"
     :properties
     {:path {:type "string"
             :description "Directory to analyze"}
      :full {:type "boolean"
             :description "Include full signatures (default false)"}}
     :required ["path"]}}

   {:name "spai_who"
    :description "Reverse dependencies: who imports this file? Use BEFORE editing a file to understand downstream impact. ONE call replaces: grep for the filename across the codebase + manual filtering."
    :inputSchema
    {:type "object"
     :properties
     {:file {:type "string"
             :description "File to find importers of"}
      :path {:type "string"
             :description "Directory scope to search in"}}
     :required ["file"]}}

   {:name "spai_blast"
    :description "Full blast radius for a symbol: definition site, all callers, all importers, related tests, git authors, risk assessment. ONE call replaces: grep for definition + grep for usages + grep for test files + git log. Use before renaming, deleting, or changing a function's signature."
    :inputSchema
    {:type "object"
     :properties
     {:symbol {:type "string"
               :description "Symbol/function name to analyze"}
      :path {:type "string"
             :description "Directory scope (optional)"}}
     :required ["symbol"]}}

   {:name "spai_context"
    :description "Symbol usages WITH enclosing function names — see WHICH functions call a symbol, not just line numbers. ONE call replaces: grep for symbol + manually reading surrounding code to find the caller. Use to understand how a function is used across the codebase."
    :inputSchema
    {:type "object"
     :properties
     {:symbol {:type "string"
               :description "Symbol to find usages of"}
      :path {:type "string"
             :description "Directory scope (optional)"}}
     :required ["symbol"]}}

   ;; === Git-powered analysis (no built-in equivalent) ===

   {:name "spai_related"
    :description "Co-change analysis: files that move together in git history, revealing implicit coupling. If file A changes, which other files usually change too? Use BEFORE refactoring to find hidden dependencies that imports don't show. ONE call replaces: git log --follow + manual correlation across commits."
    :inputSchema
    {:type "object"
     :properties
     {:file {:type "string"
             :description "File to find co-changed files for"}}
     :required ["file"]}}

   {:name "spai_drift"
    :description "Architecture health: where implicit coupling (co-change) diverges from explicit coupling (imports). Finds files that SHOULD be in the same module but aren't, or files in the same module that never change together. Use to understand architecture debt before large refactors."
    :inputSchema
    {:type "object"
     :properties
     {:path {:type "string"
             :description "Directory scope (optional)"}}}}

   {:name "spai_narrative"
    :description "Biography of a file: creation, growth phases, major refactors, stabilization. Tells the STORY of how code got to its current state. Use when inheriting unfamiliar code to understand its evolution before making changes."
    :inputSchema
    {:type "object"
     :properties
     {:file {:type "string"
             :description "File to get the history narrative for"}}
     :required ["file"]}}])

;; --- Tool dispatch ---

(defn call-tool [name args]
  (case name
    "spai_memory"
    (let [search (get args "search")
          topic (get args "topic")]
      (cond
        (and search topic) (run-spai "memory" search "--topic" topic)
        search             (run-spai "memory" search)
        topic              (run-spai "memory" "--topic" topic)
        :else              (run-spai "memory")))

    "spai_remember"
    (let [text (get args "text")
          topics (get args "topics" [])
          topic-args (mapv #(str "+" %) topics)]
      (apply run-spai "remember" text topic-args))

    "spai_memory_forget"
    (run-spai "memory" "forget" (get args "id"))

    "spai_errors_rust"
    (if-let [path (get args "path")]
      (run-spai "errors-rust" path)
      (run-spai "errors-rust"))

    "spai_shape"
    (if (get args "full")
      (run-spai "shape" (get args "path") "--full")
      (run-spai "shape" (get args "path")))

    "spai_who"
    (if-let [path (get args "path")]
      (run-spai "who" (get args "file") path)
      (run-spai "who" (get args "file")))

    "spai_blast"
    (if-let [path (get args "path")]
      (run-spai "blast" (get args "symbol") path)
      (run-spai "blast" (get args "symbol")))

    "spai_context"
    (if-let [path (get args "path")]
      (run-spai "context" (get args "symbol") path)
      (run-spai "context" (get args "symbol")))

    "spai_related"
    (run-spai "related" (get args "file"))

    "spai_drift"
    (if-let [path (get args "path")]
      (run-spai "drift" path)
      (run-spai "drift"))

    "spai_narrative"
    (run-spai "narrative" (get args "file"))

    ;; Unknown
    {:content [{:type "text" :text (str "Unknown tool: " name)}]
     :isError true}))

;; --- Request handler ---

(defn handle [{:strs [id method params]}]
  (case method
    "initialize"
    (do
      (log "initialized")
      (respond id
        {:protocolVersion "2024-11-05"
         :capabilities {:tools {}}
         :serverInfo {:name "spai-tools" :version "0.1.0"}}))

    "tools/list"
    (respond id {:tools tools})

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
