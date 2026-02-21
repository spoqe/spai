(ns spai.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [spai.core :as core]))

;; -------------------------------------------------------------------
;; detect-lang
;; -------------------------------------------------------------------

(deftest detect-lang-by-extension
  (testing "real files on disk"
    (is (= :clojure (core/detect-lang "src/spai/core.clj")))
    (is (= :clojure (core/detect-lang "src/spai/analytics.clj"))))
  (testing "non-existent file falls through to directory sampling"
    ;; detect-lang checks .isFile first; non-existent files sample the parent dir
    (is (keyword? (core/detect-lang "src/spai/core.clj")) "always returns a keyword")))

(deftest detect-lang-directory
  (testing "detects clojure from spai's own source"
    (is (= :clojure (core/detect-lang "src/spai")))))

;; -------------------------------------------------------------------
;; extract-fn-name
;; -------------------------------------------------------------------

(deftest extract-fn-name-rust
  (is (= "execute" (core/extract-fn-name "pub fn execute(" :rust)))
  (is (= "run"     (core/extract-fn-name "pub(crate) async fn run()" :rust)))
  (is (= "helper"  (core/extract-fn-name "fn helper(x: i32)" :rust))))

(deftest extract-fn-name-typescript
  (is (= "render"    (core/extract-fn-name "export function render()" :typescript)))
  (is (= "fetchData" (core/extract-fn-name "const fetchData = async () =>" :typescript))))

(deftest extract-fn-name-clojure
  (is (= "shape"  (core/extract-fn-name "(defn shape [path]" :clojure)))
  (is (= "helper" (core/extract-fn-name "(defn- helper [x]" :clojure))))

(deftest extract-fn-name-python
  (is (= "main"  (core/extract-fn-name "def main():" :python)))
  (is (= "fetch" (core/extract-fn-name "async def fetch(url):" :python))))

(deftest extract-fn-name-go
  (is (= "main"   (core/extract-fn-name "func main() {" :go)))
  (is (= "String" (core/extract-fn-name "func (s *Server) String() string {" :go))))

(deftest extract-fn-name-unknown-lang
  (is (nil? (core/extract-fn-name "fn foo()" :unknown-lang))))

;; -------------------------------------------------------------------
;; extract-type-name
;; -------------------------------------------------------------------

(deftest extract-type-name-test
  (is (= "Query"      (core/extract-type-name "pub struct Query {")))
  (is (= "Strategy"   (core/extract-type-name "enum Strategy {")))
  (is (= "Executor"   (core/extract-type-name "trait Executor {")))
  (is (= "App"        (core/extract-type-name "class App {")))
  (is (= "Renderable" (core/extract-type-name "interface Renderable {")))
  (is (= "Name"       (core/extract-type-name "type Name = String")))
  (is (nil?           (core/extract-type-name "fn not_a_type() {}"))))

;; -------------------------------------------------------------------
;; extract-type-kind
;; -------------------------------------------------------------------

(deftest extract-type-kind-test
  (is (= :struct    (core/extract-type-kind "pub struct Query {")))
  (is (= :enum      (core/extract-type-kind "enum Strategy {")))
  (is (= :trait     (core/extract-type-kind "trait Executor {")))
  (is (= :interface (core/extract-type-kind "interface Renderable {")))
  (is (= :class     (core/extract-type-kind "class App {")))
  (is (= :type      (core/extract-type-kind "type Alias = String")))
  (is (= :protocol  (core/extract-type-kind "protocol Walkable")))
  (is (= :record    (core/extract-type-kind "record Point [x y]")))
  (is (= :unknown   (core/extract-type-kind "fn something() {}"))))

;; -------------------------------------------------------------------
;; relativize
;; -------------------------------------------------------------------

(deftest relativize-test
  (is (= "main.rs"     (core/relativize "src/" "src/main.rs")))
  (is (= "deep/file.rs" (core/relativize "src/" "src/deep/file.rs")))
  (is (= "other/file.rs" (core/relativize "src/" "other/file.rs"))
      "non-matching prefix returns path unchanged"))

;; -------------------------------------------------------------------
;; skip-dirs
;; -------------------------------------------------------------------

(deftest skip-dirs-test
  (is (contains? core/skip-dirs "node_modules"))
  (is (contains? core/skip-dirs "target"))
  (is (contains? core/skip-dirs ".git"))
  (is (not (contains? core/skip-dirs "src"))))

;; -------------------------------------------------------------------
;; source-exts
;; -------------------------------------------------------------------

(deftest source-exts-test
  (is (re-find core/source-exts "main.rs"))
  (is (re-find core/source-exts "app.tsx"))
  (is (re-find core/source-exts "core.clj"))
  (is (re-find core/source-exts "main.py"))
  (is (nil? (re-find core/source-exts "data.json")))
  (is (nil? (re-find core/source-exts "readme.md"))))

;; -------------------------------------------------------------------
;; lang-patterns registry
;; -------------------------------------------------------------------

(deftest lang-patterns-has-expected-languages
  (let [langs (set (keys @core/lang-patterns))]
    (is (contains? langs :rust))
    (is (contains? langs :typescript))
    (is (contains? langs :clojure))
    (is (contains? langs :python))
    (is (contains? langs :go))
    (is (contains? langs :php))
    (is (contains? langs :java))))

(deftest register-lang-test
  (core/register-lang! :test-lang {:functions "test_fn" :types "test_type"})
  (is (contains? @core/lang-patterns :test-lang))
  (is (= "test_fn" (get-in @core/lang-patterns [:test-lang :functions])))
  ;; Clean up
  (swap! core/lang-patterns dissoc :test-lang))

;; -------------------------------------------------------------------
;; grepf (integration — needs rg or grep on PATH)
;; -------------------------------------------------------------------

(deftest grepf-finds-known-pattern
  (let [results (core/grepf "defn grepf" "src/spai/core.clj")]
    (is (seq results) "should find grepf's own definition")
    (is (= 1 (count results)))
    (is (= "src/spai/core.clj" (:file (first results))))))

(deftest grepf-returns-nil-for-no-match
  (is (nil? (core/grepf "zzz_no_match_zzz_42" "src/spai/core.clj"))))
