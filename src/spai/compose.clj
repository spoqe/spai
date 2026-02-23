(ns spai.compose
  "Commands that compose primitives from multiple modules.
   Loaded after core, code, project, git."
  (:require [spai.core :as core]
            [spai.code :as code]
            [spai.git :as git]
            [spai.project :as project]))

(defn blast
  "Blast radius: what breaks if you touch this symbol?
   Composes definition, context, who, tests, and git history.
   One call for the full picture before refactoring."
  [symbol path]
  (let [path     (or path ".")
        ;; 1. Where is it defined?
        def-data (code/definition symbol path)
        defs     (:definitions def-data)
        def-file (-> defs first :file)
        def-line (-> defs first :line)

        ;; 2. Who calls it? (usages with enclosing function context)
        ctx-data (code/context symbol path)
        callers  (:summary ctx-data)

        ;; 3. Direct callers by file (deduplicated)
        call-sites (->> (:matches ctx-data)
                        (group-by :file)
                        (mapv (fn [[f hits]]
                                {:file  (core/relativize path f)
                                 :count (count hits)
                                 :in    (->> hits (keep :in) distinct vec)}))
                        (sort-by :count >)
                        vec)

        ;; 4. Reverse file dependencies: who imports the definition file?
        importers (when def-file
                    (code/who def-file path))

        ;; 5. Related tests
        test-data (project/tests symbol path)

        ;; 6. Recent git authors on the definition file
        authors   (when def-file
                    (let [ch (git/changes def-file 20)]
                      (->> (:commits ch)
                           (map :author)
                           frequencies
                           (sort-by val >)
                           vec)))

        ;; Counts for risk assessment
        sites     (or (:count ctx-data) 0)
        n-files   (count call-sites)
        deps      (or (:dependents importers) 0)
        n-tests   (+ (count (:test-files test-data))
                     (count (:inline-tests test-data)))
        warning   (:warning ctx-data)]

    (cond-> {:symbol      symbol
     :defined-in  (when def-file
                    {:file (core/relativize path def-file)
                     :line def-line})
     :definitions (count defs)

     ;; Callers: who calls this symbol, from which functions?
     :call-sites  sites
     :callers     call-sites
     :caller-fns  callers

     ;; Importers: who imports the file this symbol lives in?
     :importing-files deps
     :importers  (when importers
                   (->> (:files importers)
                        (mapv (fn [{:keys [file]}]
                                (core/relativize path file)))))

     ;; Tests
     :test-files    (:test-files test-data)
     :inline-tests  (:inline-tests test-data)

     ;; Git
     :authors    authors

     ;; Risk summary
     :risk       (cond
                   (and (> sites 20) (> deps 5))  :high
                   (or (> sites 10) (> deps 3))   :medium
                   :else                           :low)
     :coverage   (if (pos? n-tests) :has-tests :no-tests)
     :summary    (str symbol
                      (when def-file (str " @ " (core/relativize path def-file) ":" def-line))
                      " -> " sites " call sites in " n-files " files"
                      ", " deps " importing files"
                      ", " n-tests " test files"
                      ". Risk: "
                      (cond
                        (and (> sites 20) (> deps 5))  "HIGH"
                        (or (> sites 10) (> deps 3))   "MEDIUM"
                        :else                           "LOW"))}
      warning (assoc :warning warning))))
