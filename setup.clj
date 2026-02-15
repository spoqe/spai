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

(defn check-deps []
  (println)
  (let [bb-ok  (= 0 (:exit (sh "which" "bb")))
        rg-ok  (= 0 (:exit (sh "which" "rg")))]
    (if bb-ok
      (info (str "babashka " (str/trim (:out (sh "bb" "--version")))))
      (do (warn "babashka (bb) not found")
          (println "  Install: https://babashka.org")
          (println "  brew install borkdude/brew/babashka")
          (println)))
    (if rg-ok
      (info (str "ripgrep " (first (str/split-lines (:out (sh "rg" "--version"))))))
      (do (warn "ripgrep (rg) not found — spai will use grep (slower)")
          (println "  Install: brew install ripgrep")
          (println)))
    {:bb bb-ok :rg rg-ok}))

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

(defn install-hook []
  (.mkdirs (io/file (str claude-dir "/hooks")))
  (io/copy (io/file hook-src) (io/file hook-dst))
  (.setExecutable (io/file hook-dst) true)

  ;; Wire into settings.json
  (let [settings (if (.exists (io/file settings-file))
                   (json/parse-string (slurp settings-file) true)
                   {})]
    (if (some-> settings :hooks :Bash :command (str/includes? "spai-reminder"))
      (info "Claude Code hook already configured")
      (let [updated (assoc-in settings [:hooks :Bash :command] hook-dst)]
        (spit settings-file (json/generate-string updated {:pretty true}))
        (info "Claude Code hook configured in settings.json"))))
  (info "Claude Code hook installed"))

(defn setup-claude-hook [flags]
  (when (.isDirectory (io/file claude-dir))
    (cond
      (contains? flags "--claude-hooks")
      (install-hook)

      interactive?
      (do (println)
          (info "Claude Code detected!")
          (println "  spai includes a hook that reminds Claude agents to use spai")
          (println "  instead of chaining grep commands for code exploration.")
          (println)
          (when (ask "Install Claude Code hook?" :n)
            (install-hook)))

      :else
      (do (println)
          (info "Claude Code detected. To install the spai reminder hook:")
          (println (str "  spai setup --claude-hooks"))
          (println (str "  Or manually: cp " hook-src " " hook-dst))))))

;; --- Main ---

(defn -main [& args]
  (let [flags (set args)]
    (check-deps)
    (ensure-path)
    (setup-claude-hook flags)
    (println)
    (info "Setup complete!")
    (println "  spai help")
    (println "  spai-edit help")
    (println)
    (println (str "  Per-project config: create .spai.edn"))
    (println (str "  Custom commands: drop spai-* scripts in " share-dir "/plugins/"))
    (println (str "  Docs: " share-dir "/README.md"))))

(apply -main *command-line-args*)
