(ns spai.contract-test
  "Contract tests: tripwire for CLI return shape changes.

   When a command's return keys change, these tests fail.
   That's the signal to update CLAUDE.md, memory, MCP tool definitions,
   and skills. The contract lives HERE, not in the registry —
   so editing spai.clj alone won't silently pass."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [spai.code :as code]
            [spai.project :as project]
            [spai.git :as git]
            [spai.compose :as compose]
            [spai.analytics :as analytics]))

;; -------------------------------------------------------------------
;; Contract definitions: command → expected return keys
;; -------------------------------------------------------------------

(def ^:private contracts
  {:shape      {:keys #{:path :language :files}
                :call #(code/shape "src/spai")}
   :shape-full {:keys #{:path :lang :language :functions :types :impls :imports :modules}
                :call #(code/shape "src/spai" :full true)}
   :usages     {:keys #{:path :symbol :matches :count}
                :call #(code/usages "grepf" "src/spai")}
   :grep       {:keys #{:path :pattern :matches :count}
                :call #(code/grep-raw "defn" "src/spai")}
   :def        {:keys #{:path :symbol :definitions}
                :call #(code/definition "shape" "src/spai")}
   :sig        {:keys #{:path :language :signatures}
                :call #(code/sig "src/spai/core.clj")}
   :who        {:keys #{:file :base :dependents :files}
                :call #(code/who "src/spai/core.clj" "src/spai")}
   :context    {:keys #{:path :symbol :matches :count :summary}
                :call #(code/context "grepf" "src/spai")}
   :patterns   {:keys #{:path :language :total-fns :total-types :naming :structure :conventions}
                :call #(code/patterns "src/spai")}
   :overview   {:keys #{:path :language :config :dirs :file-count :by-extension}
                :call #(project/overview ".")}
   :layout     {:keys #{:dir :files :subdirs}
                :call #(project/layout ".")}
   :tests      {:keys #{:target :path :test-files :inline-tests}
                :call #(project/tests "core" "src/spai")}
   :hotspots   {:keys #{:path :hotspots}
                :call #(project/hotspots ".")}
   :todos      {:keys #{:path :total :by-category :items}
                :call #(project/todos ".")}
   :changes    {:keys #{:path :commits}
                :call #(git/changes "." 3)}
   :related    {:keys #{:file :commits :related :by-dir :insight}
                :call #(git/related "src/spai/core.clj" :n 20 :min-pct 10)}
   :diff       {:keys #{:file :commits}
                :call #(git/diff "src/spai/core.clj" 1)}
   :diff-shape {:keys #{:path :ref :summary :files :changes}
                :call #(git/diff-shape "." "HEAD~1")}
   :narrative  {:keys #{:file :total-commits :authors :eras :arc :summary :current-lines}
                :call #(git/narrative "src/spai/core.clj" :n 20)}
   :drift      {:keys #{:path :files-analyzed :files-with-drift :drift
                         :total-hidden-coupling :total-dead-coupling :insight}
                :call #(git/drift "src/spai" :n 20 :min-pct 15)}
   :blast      {:keys #{:symbol :defined-in :definitions :call-sites :callers :caller-fns
                         :importers :importing-files :test-files :inline-tests
                         :coverage :risk :summary :authors}
                :call #(compose/blast "grepf" "src/spai")}
   :stats      {:keys #{:total :by-command :top-paths :recent}
                :call #(analytics/stats)}
   :reflect    {:keys #{:total-calls :explored-paths :spai-commands :repeated-sequences :plugins}
                :call #(analytics/reflect)}})

;; -------------------------------------------------------------------
;; Contract test: run each, assert exact key set
;; -------------------------------------------------------------------

(deftest contract-keys-match
  (doseq [[cmd-name {:keys [keys call]}] (sort-by key contracts)]
    (testing (str ":" cmd-name " return keys")
      (let [result      (call)
            actual-keys (set (clojure.core/keys result))
            missing     (set/difference keys actual-keys)
            extra       (set/difference actual-keys keys)]
        (is (= keys actual-keys)
            (str "Contract changed for " cmd-name "."
                 (when (seq missing) (str " Missing: " missing "."))
                 (when (seq extra) (str " Added: " extra "."))
                 " Update contract_test.clj, then check CLAUDE.md and memory."))))))
