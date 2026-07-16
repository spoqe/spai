(ns spai.cli-test
  "CLI-level regression tests — invoke `bb spai.clj …` as a real user would.
   These catch dispatch bugs that unit tests on the src fns can't see."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [clojure.edn :as edn]))

(defn- run-spai [& args]
  (apply sh "bb" "spai.clj" args))

(deftest subcommand-help-flag
  ;; Regression: `spai patterns --help` treated "--help" as the [path] arg and
  ;; ran analysis on a bogus path (detected :rust, 0 fns) instead of showing help.
  (testing "`spai patterns --help` prints the command's help, not analysis"
    (let [{:keys [out exit]} (run-spai "patterns" "--help")
          m (edn/read-string out)]
      (is (zero? exit) "exits cleanly")
      (is (= "patterns" (:command m)) "help output names the command")
      (is (contains? m :usage) "help output includes :usage")
      (is (not (contains? m :total-fns))
          "did NOT fall through to pattern analysis")))
  (testing "`-h` short flag behaves the same"
    (let [{:keys [out]} (run-spai "patterns" "-h")]
      (is (= "patterns" (:command (edn/read-string out)))))))
