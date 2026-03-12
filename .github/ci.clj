#!/usr/bin/env bb
;; spai CI/CD Pipeline — baci pattern (Clojure data → YAML)

(require '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[clj-yaml.core :as yaml])

;; GitHub Actions workflow command emitters
(defn gh-group [name] (println (str "::group::" name)))
(defn gh-endgroup [] (println "::endgroup::"))
(defn gh-error [msg] (println (str "::error::" msg)))
(defn gh-warning [msg] (println (str "::warning::" msg)))

(defmacro with-group [name & body]
  `(do (gh-group ~name)
       (try ~@body
            (finally (gh-endgroup)))))

(defn run-bash [cmd & [{:keys [continue-on-error] :or {continue-on-error false}}]]
  (println (str "$ " cmd))
  (let [result (shell/sh "bash" "-c" cmd)]
    (print (:out result))
    (print (:err result))
    (when-not (zero? (:exit result))
      (if continue-on-error
        (gh-warning (str "Command failed but continuing: " cmd))
        (do
          (gh-error (str "Command failed: " cmd))
          (System/exit (:exit result)))))
    result))

;; Pipeline stages
(defn test-stage []
  (with-group "🧪 Unit + contract tests"
    (run-bash "bb test")))

(defn smoke-stage []
  (with-group "💨 Smoke test — all commands run"
    ;; Code analysis
    (run-bash "bb spai.clj shape src/")
    (run-bash "bb spai.clj usages grepf src/")
    (run-bash "bb spai.clj def shape-raw src/")
    (run-bash "bb spai.clj sig src/spai/core.clj")
    (run-bash "bb spai.clj who src/spai/core.clj src/")
    (run-bash "bb spai.clj context grepf src/")
    (run-bash "bb spai.clj grep 'defn-?\\s' src/")
    (run-bash "bb spai.clj patterns src/")
    ;; Project analysis
    (run-bash "bb spai.clj overview .")
    (run-bash "bb spai.clj layout src/")
    (run-bash "bb spai.clj hotspots src/")
    (run-bash "bb spai.clj todos src/")
    ;; Git commands
    (run-bash "bb spai.clj changes src/ 3")
    (run-bash "bb spai.clj diff-shape src/ HEAD~1")
    (run-bash "bb spai.clj diff src/spai/core.clj 2")
    ;; Structural editing
    (run-bash "bb spai-edit.clj forms src/spai/core.clj")
    (run-bash "bb spai-edit.clj validate src/spai/core.clj")
    (run-bash "bb spai-edit.clj validate spai.clj")))

(defn multilang-stage []
  (with-group "🌍 Multi-language detection smoke test"
    ;; Create temp files for each supported language, verify detection
    (run-bash (str "cd /tmp && "
                   "echo 'fn main() {}' > test_spai.rs && "
                   "echo 'function f() {}' > test_spai.ts && "
                   "echo '(defn f [])' > test_spai.clj && "
                   "echo 'def f(): pass' > test_spai.py && "
                   "echo 'func main() {}' > test_spai.go && "
                   "echo '<?php function f() {}' > test_spai.php && "
                   "echo 'class F {}' > test_spai.java && "
                   "echo 'func f() {}' > test_spai.swift && "
                   "echo 'def f(): Unit = ()' > test_spai.scala && "
                   "echo 'def f; end' > test_spai.rb && "
                   "echo 'fun f() {}' > test_spai.kt && "
                   "cd -"))
    (doseq [ext ["rs" "ts" "clj" "py" "go" "php" "java" "swift" "scala" "rb" "kt"]]
      (run-bash (str "bb spai.clj shape /tmp/test_spai." ext)))
    ;; Cleanup
    (run-bash "rm -f /tmp/test_spai.*")))

(defn install-stage []
  (with-group "📦 Install simulation"
    (run-bash (str "SHARE_DIR=\"$HOME/.local/share/spai\" && "
                   "BIN_DIR=\"$HOME/.local/bin\" && "
                   "mkdir -p \"$SHARE_DIR\" \"$BIN_DIR\" && "
                   "cp -r ./* \"$SHARE_DIR/\" && "
                   "chmod +x \"$SHARE_DIR\"/*.clj 2>/dev/null || true"))
    (run-bash (str "cat > $HOME/.local/bin/spai << 'WRAPPER'\n"
                   "#!/usr/bin/env bash\n"
                   "export PATH=\"$HOME/.local/share/spai/plugins:$PATH\"\n"
                   "_d=\"$PWD\"\n"
                   "while [ \"$_d\" != \"/\" ]; do\n"
                   "  [ -d \"$_d/.spai/plugins\" ] && export PATH=\"$_d/.spai/plugins:$PATH\" && break\n"
                   "  _d=\"$(dirname \"$_d\")\"\n"
                   "done\n"
                   "unset _d\n"
                   "bb \"$HOME/.local/share/spai/spai.clj\" \"$@\"\n"
                   "WRAPPER\n"
                   "chmod +x $HOME/.local/bin/spai"))
    (run-bash (str "cat > $HOME/.local/bin/spai-edit << 'WRAPPER'\n"
                   "#!/usr/bin/env bash\n"
                   "bb \"$HOME/.local/share/spai/spai-edit.clj\" \"$@\"\n"
                   "WRAPPER\n"
                   "chmod +x $HOME/.local/bin/spai-edit"))
    (run-bash "export PATH=\"$HOME/.local/bin:$PATH\" && spai help")
    (run-bash "export PATH=\"$HOME/.local/bin:$PATH\" && spai-edit help")))

(defn run-pipeline []
  (test-stage)
  (smoke-stage)
  (multilang-stage)
  (install-stage))

;; ─── Pipeline as data ───────────────────────────────────────────────

(def pipeline
  {:name "CI"
   :on {:push {:branches ["main"]}
        :pull_request {:branches ["main"]}}
   :jobs
   {:test
    {:runs-on "ubuntu-latest"
     :steps [{:uses "actions/checkout@v4"
              :with {:fetch-depth 20}}
             {:name "Install babashka"
              :uses "turtlequeue/setup-babashka@v1.5.0"
              :with {:babashka-version "1.3.186"}}
             {:name "Install ripgrep"
              :run "sudo apt-get install -y ripgrep"}
             {:name "Verify deps"
              :run "bb --version && rg --version"}
             {:name "Test"
              :run "bb .github/ci.clj test"}
             {:name "Smoke"
              :run "bb .github/ci.clj smoke"}
             {:name "Multi-language"
              :run "bb .github/ci.clj multilang"}]}
    :install
    {:runs-on "ubuntu-latest"
     :steps [{:uses "actions/checkout@v4"}
             {:name "Install babashka"
              :uses "turtlequeue/setup-babashka@v1.5.0"
              :with {:babashka-version "1.3.186"}}
             {:name "Install ripgrep"
              :run "sudo apt-get install -y ripgrep"}
             {:name "Install + verify"
              :run "bb .github/ci.clj install"}]}}})

;; ─── YAML emitter ──────────────────────────────────────────────────

(defn emit-yaml-cmd []
  (let [header  (str "# GENERATED by ci.clj — do not edit by hand\n"
                     "# Source of truth: .github/ci.clj\n"
                     "# Regenerate: bb .github/ci.clj emit-yaml\n\n")
        content (yaml/generate-string pipeline
                                      :dumper-options {:flow-style :block})
        yaml    (str header content)
        path    ".github/workflows/ci.yml"]
    (spit path yaml)
    (println (str "Generated " path " (" (count (str/split-lines yaml)) " lines)"))))

;; CLI
(defn -main [& args]
  (case (first args)
    "run"       (run-pipeline)
    "test"      (test-stage)
    "smoke"     (smoke-stage)
    "multilang" (multilang-stage)
    "install"   (install-stage)
    "emit-yaml" (emit-yaml-cmd)
    (do (println "Usage: bb ci.clj <run|test|smoke|multilang|install|emit-yaml>")
        (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
