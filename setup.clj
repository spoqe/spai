#!/usr/bin/env bb
;; spai setup — post-install configuration
;;
;; Run after install.sh, or anytime via `spai setup`.
;; Handles: PATH, Claude Code hooks, dependency checks.
;; Re-runnable. Idempotent. Readable.

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[cheshire.core :as json]
         '[clojure.java.shell :refer [sh]])

(def home (System/getProperty "user.home"))
(def share-dir (str (or (System/getenv "XDG_DATA_HOME")
                        (str home "/.local/share"))
                    "/spai"))
(def bin-dir (str home "/.local/bin"))
(def interactive? (and (System/console) (not= (System/getenv "CI") "true")))

;; --- Output helpers ---

(def colors? (boolean (System/console)))
(defn info [msg] (println (if colors? (str "\033[1m\033[32m>>>\033[0m " msg)
                                      (str ">>> " msg))))
(defn warn [msg] (println (if colors? (str "\033[1m\033[33m>>>\033[0m " msg)
                                      (str ">>> " msg))))

(defn ask
  "Ask y/n question. Returns true if yes. default is :y or :n."
  [prompt default]
  (if-not interactive?
    (= default :y)
    (do
      (print (str "  " prompt (if (= default :y) " [Y/n] " " [y/N] ")))
      (flush)
      (let [reply (str/trim (or (read-line) ""))]
        (if (str/blank? reply)
          (= default :y)
          (str/starts-with? (str/lower-case reply) "y"))))))

;; --- Dependencies ---

(defn try-install
  "Run an optional-dep install command, tolerating a missing binary.
  Returns true on success. Never throws — a failed optional install must
  not abort setup."
  [install-cmd label]
  (info (str "Installing " label "..."))
  (let [r (try (apply sh install-cmd)
               (catch Exception e {:exit 1 :err (.getMessage e)}))]
    (if (zero? (:exit r))
      (do (info (str label " installed.")) true)
      (do (warn (str "Install failed — run manually: " (str/join " " install-cmd)))
          (when (seq (:err r)) (println (:err r)))
          false))))

;; ripgrep ships static release binaries, so we can install it user-locally
;; into ~/.local/bin — no sudo, no package manager. Same pattern as babashka.
(def ripgrep-version "15.2.0")

(defn ripgrep-target
  "ripgrep release target triple for this platform, or nil if unknown.
  Prefers the static musl build on Linux (no glibc dependency)."
  [os arch]
  (let [mac? (str/includes? os "mac")
        lin? (str/includes? os "linux")
        arm? (or (str/includes? arch "aarch64") (str/includes? arch "arm64"))
        x64? (or (str/includes? arch "amd64") (str/includes? arch "x86_64"))]
    (cond
      (and mac? arm?) "aarch64-apple-darwin"
      (and mac? x64?) "x86_64-apple-darwin"
      (and lin? arm?) "aarch64-unknown-linux-musl"
      (and lin? x64?) "x86_64-unknown-linux-musl"
      :else nil)))

(defn install-ripgrep-binary
  "Download the ripgrep static binary into bin-dir. No sudo, no package
  manager. Returns true on success, never throws."
  [target]
  (let [asset (str "ripgrep-" ripgrep-version "-" target)
        url   (str "https://github.com/BurntSushi/ripgrep/releases/download/"
                   ripgrep-version "/" asset ".tar.gz")
        tmp   (str (System/getProperty "java.io.tmpdir") "/spai-rg-" ripgrep-version)]
    (info (str "Downloading ripgrep " ripgrep-version " (" target ", no sudo)..."))
    (let [r (try
              (sh "bash" "-c"
                  (str "set -e; rm -rf '" tmp "'; mkdir -p '" tmp "' '" bin-dir "'; "
                       "curl -sSfL '" url "' | tar xz -C '" tmp "' --strip-components=1; "
                       "cp '" tmp "/rg' '" bin-dir "/rg'; chmod +x '" bin-dir "/rg'; "
                       "rm -rf '" tmp "'"))
              (catch Exception e {:exit 1 :err (.getMessage e)}))]
      (if (zero? (:exit r))
        (do (info (str "ripgrep installed → " bin-dir "/rg")) true)
        (do (warn "ripgrep download failed — falling back to grep.")
            (when (seq (:err r)) (println (:err r)))
            false)))))

(defn check-deps []
  (println)
  (let [bb-ok  (= 0 (:exit (sh "which" "bb")))
        rg-ok  (= 0 (:exit (sh "which" "rg")))
        ollama-ok (= 0 (:exit (sh "which" "ollama")))]
    (if bb-ok
      (info (str "babashka " (str/trim (:out (sh "bb" "--version")))))
      (do (warn "babashka (bb) not found")
          (println "  Install: https://babashka.org")
          (println "  brew install borkdude/brew/babashka")
          (println)))
    (if rg-ok
      (info (str "ripgrep " (first (str/split-lines (:out (sh "rg" "--version"))))))
      (let [os     (str/lower-case (System/getProperty "os.name"))
            arch   (str/lower-case (System/getProperty "os.arch"))
            mac?   (str/includes? os "mac")
            win?   (str/includes? os "windows")
            brew?  (= 0 (:exit (sh "which" "brew")))
            target (ripgrep-target os arch)]
        (warn "ripgrep (rg) not found — spai will use grep (slower)")
        ;; Optional dep, installed sudo-free. Prefer brew on macOS (keeps it
        ;; updated); otherwise pull the static release binary into ~/.local/bin
        ;; — no sudo, no apt. Only offered interactively; a piped curl|bash run
        ;; just prints the hint.
        (cond
          (and mac? brew? interactive?
               (ask "Install ripgrep via brew?" :y))
          (try-install ["brew" "install" "ripgrep"] "ripgrep")

          (and target interactive?
               (ask (str "Install ripgrep " ripgrep-version " into " bin-dir "? (no sudo)") :y))
          (install-ripgrep-binary target)

          :else
          (do (println (if win?
                         "  Install: winget install BurntSushi.ripgrep.MSVC"
                         "  Install: https://github.com/BurntSushi/ripgrep#installation"))
              (println)))))
    (if ollama-ok
      (do (info (str "ollama " (str/trim (:out (sh "ollama" "--version")))))
          (let [list-out (:out (sh "ollama" "list"))
                has-model? (str/includes? list-out "qwen2.5-coder:7b")]
            (when-not has-model?
              (when (ask "Pull qwen2.5-coder:7b for spai search? (2.2GB) [optional]" :y)
                (info "Pulling qwen2.5-coder:7b...")
                (let [r (sh "ollama" "pull" "qwen2.5-coder:7b")]
                  (if (zero? (:exit r))
                    (info "qwen2.5-coder:7b pulled.")
                    (do (warn "Pull failed — run manually: ollama pull qwen2.5-coder:7b")
                        (println (:err r)))))))))
      (let [os   (str/lower-case (System/getProperty "os.name"))
            mac? (str/includes? os "mac")
            brew? (= 0 (:exit (sh "which" "brew")))
            install-cmd (cond
                          (and mac? brew?) ["brew" "install" "ollama"]
                          :else            nil)]
        (warn "ollama not found — spai search will not be available [optional]")
        (if (and install-cmd interactive?
                 (ask (str "Install now? (" (str/join " " install-cmd) ")") :y))
          (try-install install-cmd "ollama")
          (do (println "  Install: https://ollama.ai/download")
              (println)))))
    {:bb bb-ok :rg rg-ok :ollama ollama-ok}))

;; --- PATH ---

(defn shell-rc-file
  "Detect the user's shell rc file."
  []
  (let [shell (or (System/getenv "SHELL") "")
        name  (last (str/split shell #"/"))]
    (case name
      "zsh"  (str home "/.zshrc")
      "bash" (let [bp (str home "/.bash_profile")]
               (if (.exists (io/file bp)) bp (str home "/.bashrc")))
      "fish" (str home "/.config/fish/config.fish")
      nil)))

(defn path-line [shell-name]
  (if (= shell-name "fish")
    "set -gx PATH $HOME/.local/bin $PATH"
    "export PATH=\"$HOME/.local/bin:$PATH\""))

(defn ensure-path []
  (let [path     (System/getenv "PATH")
        on-path? (some #(= % bin-dir) (str/split (or path "") #":"))]
    (if on-path?
      (info (str bin-dir " is on PATH"))
      (let [rc   (shell-rc-file)
            name (last (str/split (or (System/getenv "SHELL") "") #"/"))
            line (path-line name)]
        (if (nil? rc)
          (do (warn (str bin-dir " is not in your PATH"))
              (println (str "  Add to your shell config: " line)))
          (let [content (when (.exists (io/file rc)) (slurp rc))
                already? (and content (str/includes? content ".local/bin"))]
            (cond
              already?
              (info (str bin-dir " already in " rc " (restart shell or: source " rc ")"))

              (ask (str "Add to " rc "?") :y)
              (do (spit rc (str content "\n\n# spai\n" line "\n") )
                  (info (str "Added to " rc " — restart shell or: source " rc)))

              :else
              (println (str "  Add manually: " line)))))))))

;; --- Claude Code hook ---

(def claude-dir (str home "/.claude"))
(def hook-src (str share-dir "/hooks/claude-code-reminder.sh"))
(def hook-dst (str claude-dir "/hooks/spai-reminder.sh"))
(def settings-file (str claude-dir "/settings.json"))

(def hook-matchers
  "Tools to intercept with the spai reminder hook."
  ["Bash" "Grep" "Glob"])

(defn spai-hook-entry
  "Build a PreToolUse hook entry for a given tool matcher."
  [matcher]
  {:matcher matcher
   :hooks   [{:type "command" :command hook-dst}]})

(defn has-spai-hooks?
  "Check if settings already has spai hooks in PreToolUse."
  [settings]
  (let [pre-tool-use (get-in settings [:hooks :PreToolUse])]
    (and (sequential? pre-tool-use)
         (some (fn [entry]
                 (some-> entry :hooks first :command (str/includes? "spai-reminder")))
               pre-tool-use))))

(defn install-hook []
  (.mkdirs (io/file (str claude-dir "/hooks")))
  (io/copy (io/file hook-src) (io/file hook-dst))
  (.setExecutable (io/file hook-dst) true)

  ;; Wire into settings.json using PreToolUse format
  (let [settings (if (.exists (io/file settings-file))
                   (json/parse-string (slurp settings-file) true)
                   {})]
    (if (has-spai-hooks? settings)
      (info "Claude Code hooks already configured")
      (let [;; Remove old-style :Bash hook if present
            cleaned  (if (some-> settings :hooks :Bash :command (str/includes? "spai-reminder"))
                       (update settings :hooks dissoc :Bash)
                       settings)
            ;; Get existing PreToolUse entries (non-spai)
            existing (filterv (fn [entry]
                                (not (some-> entry :hooks first :command
                                             (str/includes? "spai-reminder"))))
                              (get-in cleaned [:hooks :PreToolUse] []))
            ;; Add spai entries for each matcher
            spai-entries (mapv spai-hook-entry hook-matchers)
            updated  (assoc-in cleaned [:hooks :PreToolUse]
                                (into existing spai-entries))]
        (spit settings-file (json/generate-string updated {:pretty true}))
        (info "Claude Code hooks configured (Bash, Grep, Glob)"))))
  (info "Claude Code hook installed"))

(defn setup-claude-hook [flags]
  (when (.isDirectory (io/file claude-dir))
    (cond
      (contains? flags "--claude-hooks")
      (install-hook)

      interactive?
      (do (println)
          (info "Claude Code detected!")
          (println "  spai includes hooks that nudge Claude toward spai tools (recon,")
          (println "  blast, shape, etc.) when it reaches for raw Grep/Glob/Bash.")
          (println "  Escalates with repetition. Hooks fire on Bash, Grep, and Glob.")
          (println)
          (when (ask "Install Claude Code hook?" :n)
            (install-hook)))

      :else
      (do (println)
          (info "Claude Code detected. To install the spai reminder hook:")
          (println (str "  spai setup --claude-hooks"))
          (println (str "  Or manually: cp " hook-src " " hook-dst))))))

;; --- MCP server ---

(def mcp-script (str share-dir "/spai-mcp.bb"))

(defn find-bb []
  (let [candidates [(str home "/.local/bin/bb") "/usr/local/bin/bb" "/opt/homebrew/bin/bb"]
        on-path    (let [r (sh "which" "bb")] (when (zero? (:exit r)) (str/trim (:out r))))]
    (or on-path
        (first (filter #(.exists (io/file %)) candidates)))))

(defn has-spai-mcp? [settings]
  (some? (get-in settings [:mcpServers :spai])))

(defn install-mcp []
  (let [bb (find-bb)]
    (if-not bb
      (do (warn "bb not found — cannot register MCP server")
          (println "  Install babashka first, then re-run: spai setup"))
      (let [settings (if (.exists (io/file settings-file))
                       (json/parse-string (slurp settings-file) true)
                       {})]
        (if (has-spai-mcp? settings)
          (info "spai MCP server already registered")
          (let [updated (assoc-in settings [:mcpServers :spai]
                                  {:command bb :args [mcp-script]})]
            (spit settings-file (json/generate-string updated {:pretty true}))
            (info "spai MCP server registered (restart Claude Code to activate)")))))))

(defn setup-mcp [flags]
  (when (.isDirectory (io/file claude-dir))
    (when-not (.exists (io/file mcp-script))
      (warn (str "spai-mcp.bb not found at " mcp-script " — skipping MCP setup"))
      (System/exit 0))
    (cond
      (contains? flags "--no-mcp")
      (info "Skipping MCP server registration (--no-mcp)")

      (contains? flags "--mcp")
      (install-mcp)

      interactive?
      (do (println)
          (info "MCP server registration — optional.")
          (println)
          (println "  spai can be registered as an MCP server, exposing its tools as native")
          (println "  Claude tools (alongside Bash, Read, Grep, etc.).")
          (println)
          (println "  Why you might want it:")
          (println "    • Native tool integration — tools appear in Claude's UI")
          (println "    • Your framework/agent expects MCP")
          (println)
          (println "  Why you might NOT want it:")
          (println "    • MCP eagerly loads ~42k tokens of tool schemas at session start —")
          (println "      whether you use them or not.")
          (println "    • The CLI approach (spai help + spai search) is lazy — loads ~1.2k")
          (println "      tokens on demand. 94% fewer tokens, same capabilities.")
          (println "    • Claude can always run spai as a shell command regardless.")
          (println)
          (println "  Recommended: skip MCP, use the CLI. You can always add it later:")
          (println "    spai setup --mcp")
          (println)
          (when (ask "Register spai MCP server anyway?" :n)
            (install-mcp)))

      :else
      (do (println)
          (info "To register spai as an MCP server (optional — adds ~42k tokens to Claude context):")
          (println "  spai setup --mcp")))))

;; --- Main ---

(defn -main [& args]
  (let [flags (set args)]
    (check-deps)
    (ensure-path)
    (setup-claude-hook flags)
    (setup-mcp flags)
    (println)
    (info "Setup complete!")
    (println "  spai help")
    (println "  spai-edit help")
    (println)
    (println (str "  Per-project config: .spai/config.edn"))
    (println (str "  Project plugins:   .spai/plugins/"))
    (println (str "  Global plugins:    " share-dir "/plugins/"))
    (println (str "  Docs: " share-dir "/README.md"))))

(apply -main *command-line-args*)
