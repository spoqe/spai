(ns spai.project
  "Project structure and discovery: overview, layout, tests, hotspots, todos."
  (:require [spai.core :as core]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private project-files
  "Files that describe a project. Ordered by priority."
  ["Cargo.toml" "package.json" "pyproject.toml" "go.mod" "pom.xml"
   "build.gradle" "Makefile" "CMakeLists.txt" "deps.edn" "bb.edn"
   "project.clj" "Gemfile" "composer.json" "mix.exs" "CLAUDE.md"
   "README.md" "README" "readme.md"])

(defn overview
  "Project overview: language, config, top-level structure, entry points."
  [path]
  (let [root   (io/file (or path "."))
        files  (->> (.listFiles root)
                    (remove #(core/skip-dirs (.getName %)))
                    (sort-by #(.getName %)))
        dirs   (filter #(.isDirectory %) files)
        found  (->> project-files
                    (keep (fn [f]
                            (let [fp (io/file root f)]
                              (when (.exists fp)
                                {:file f :size (.length fp)}))))
                    vec)
        lang   (core/detect-lang (or path "."))
        src    (->> (file-seq root)
                    (remove #(.isDirectory %))
                    (remove #(some core/skip-dirs (str/split (.getPath %) #"/")))
                    (map #(.getName %)))]
    {:path       (or path ".")
     :language   lang
     :config     found
     :dirs       (mapv #(.getName %) dirs)
     :file-count (count src)
     :by-extension (->> src
                        (map #(let [n %] (when-let [i (str/last-index-of n ".")] (subs n i))))
                        (remove nil?)
                        frequencies
                        (sort-by val >)
                        (take 15)
                        vec)}))

(defn layout
  "Smart directory tree. Skips noise dirs, shows file counts per directory."
  [path]
  (let [root  (io/file (or path "."))
        walk  (fn walk [dir depth]
                (when (< depth 4)
                  (let [children (->> (.listFiles dir)
                                      (remove #(core/skip-dirs (.getName %)))
                                      (remove #(str/starts-with? (.getName %) "."))
                                      (sort-by #(vector (if (.isDirectory %) 0 1) (.getName %))))]
                    {:dir   (core/relativize (str (.getPath root) "/") (.getPath dir))
                     :files (->> children (remove #(.isDirectory %)) (mapv #(.getName %)))
                     :subdirs (->> children
                                   (filter #(.isDirectory %))
                                   (mapv #(walk % (inc depth))))})))]
    (walk root 0)))

(defn tests
  "Find test files related to a source file or symbol.
   Handles: separate test files, inline test modules (Rust #[cfg(test)]), test dirs."
  [target path]
  (let [path       (or path ".")
        f          (io/file target)
        base       (when (.exists f)
                     (let [n (.getName f)]
                       (if-let [dot (str/last-index-of n ".")]
                         (subs n 0 dot)
                         n)))
        term       (or base target)
        test-file? (fn [f] (re-find #"(?i)(test|spec)" f))
        ;; 1. Separate test files whose name contains the target
        named      (->> (file-seq (io/file path))
                        (filter #(.isFile %))
                        (map #(.getPath %))
                        (filter test-file?)
                        (filter #(str/includes? (str/lower-case %) (str/lower-case term))))
        ;; 2. Files mentioning target that are test files
        mentions   (->> (core/grepf term path)
                        (map :file)
                        distinct
                        (filter test-file?))
        ;; 3. Inline tests: files with test markers whose name or content matches target
        test-markers (set (->> (core/grepf "#\\[cfg\\(test\\)\\]|#\\[test\\]|def test_|deftest " path)
                               (map :file)
                               distinct))
        target-files (set (->> (core/grepf term path) (map :file) distinct))
        target-named (->> (file-seq (io/file path))
                          (filter #(.isFile %))
                          (map #(.getPath %))
                          (filter #(str/includes? (str/lower-case %) (str/lower-case term)))
                          set)
        inline       (set/intersection test-markers
                                       (set/union target-files target-named))]
    {:target       target
     :path         path
     :test-files   (vec (distinct (concat named mentions)))
     :inline-tests (vec (sort inline))}))

(defn hotspots
  "Find the biggest/most complex files. Where's the debt hiding?"
  [path]
  (let [root  (io/file (or path "."))
        files (->> (file-seq root)
                   (filter #(.isFile %))
                   (remove #(some core/skip-dirs (str/split (.getPath %) #"/")))
                   (remove #(str/starts-with? (.getName %) "."))
                   (filter #(re-find core/source-exts (.getName %)))
                   (mapv (fn [f]
                           (let [lines (count (str/split-lines (slurp f)))]
                             {:file  (core/relativize (str (.getPath root) "/") (.getPath f))
                              :lines lines})))
                   (sort-by :lines >)
                   (take 20))]
    {:path     (or path ".")
     :hotspots (vec files)}))

;; -------------------------------------------------------------------
;; todos — TODO/FIXME/HACK scanner
;; -------------------------------------------------------------------

(defn todos
  "Structured TODO/FIXME/HACK scan. Categorized, sorted, EDN."
  [path]
  (let [path    (or path ".")
        raw     (or (core/grepf "TODO|FIXME|HACK|XXX|WARN" path) [])
        categorize (fn [text]
                     (cond
                       (re-find #"FIXME" text) :fixme
                       (re-find #"HACK" text)  :hack
                       (re-find #"XXX" text)   :xxx
                       (re-find #"WARN" text)  :warn
                       :else                   :todo))
        items   (->> raw
                     (remove #(some core/skip-dirs (str/split (:file %) #"/")))
                     (remove #(re-find #"\.(log|lock|json)$" (:file %)))
                     (mapv (fn [m]
                             (assoc m :category (categorize (:text m)))))
                     (sort-by (juxt :category :file :line))
                     vec)
        by-cat  (->> items (group-by :category)
                     (map (fn [[k v]] [k (count v)]))
                     (into (sorted-map)))]
    {:path       path
     :total      (count items)
     :by-category by-cat
     :items      items}))
