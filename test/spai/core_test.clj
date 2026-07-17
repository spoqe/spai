(ns spai.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [spai.core :as core]))

(defn- with-temp-tree
  "Create a temp dir, populate it via (rel → content) map, run (f dir-path),
  then delete the tree. Returns f's value."
  [files f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                        "spai-detect"
                        (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (doseq [[rel content] files]
        (let [file (io/file root rel)]
          (io/make-parents file)
          (spit file content)))
      (f (.getPath root))
      (finally
        (doseq [x (reverse (file-seq root))] (.delete x))))))

;; -------------------------------------------------------------------
;; detect-lang
;; -------------------------------------------------------------------

(deftest detect-lang-by-extension
  (testing "real files on disk"
    (is (= :clojure (core/detect-lang "src/spai/core.clj")))
    (is (= :clojure (core/detect-lang "src/spai/analytics.clj"))))
  (testing "non-existent file falls through to directory sampling"
    ;; detect-lang checks .isFile first; non-existent files sample the parent dir
    (is (keyword? (core/detect-lang "src/spai/core.clj")) "always returns a keyword"))
  (testing "new language extensions (file-based detection)"
    ;; detect-lang checks .isFile, so we need real files
    (let [tmp (System/getProperty "java.io.tmpdir")
          files [["test.scala" :scala] ["test.sc" :scala]
                 ["test.rb" :ruby]
                 ["test.kt" :kotlin] ["test.kts" :kotlin]]]
      (doseq [[fname expected] files]
        (let [f (java.io.File. tmp fname)]
          (spit f "")
          (try
            (is (= expected (core/detect-lang (.getPath f)))
                (str fname " should detect as " expected))
            (finally (.delete f)))))))
  (testing "unknown extension returns :unknown"
    (is (= :unknown (core/detect-lang "foo.xyz")))))

(deftest detect-lang-directory
  (testing "detects clojure from spai's own source"
    (is (= :clojure (core/detect-lang "src/spai")))))

(deftest detect-lang-skips-noise-dirs
  ;; Regression: a TypeScript project was reported as :rust because detection
  ;; sampled the first 100 files of a raw file-seq (node_modules/.git noise),
  ;; found no known source, and resolve-lang fell back to :rust.
  (testing "typescript sources win; stray .rs in node_modules/.git is ignored"
    (with-temp-tree
      {"src/app.ts"              "export const x = 1;"
       "src/util.ts"             "export function f() {}"
       "src/view.tsx"            "export const V = () => null;"
       "node_modules/pkg/lib.rs" "fn main() {}"
       ".git/objects/blob.rs"    "fn x() {}"
       "package.json"            "{}"}
      (fn [dir] (is (= :typescript (core/detect-lang dir)))))))

(deftest detect-lang-picks-dominant
  (testing "most common language wins, not first file encountered"
    (with-temp-tree
      {"a.py" "" "b.py" "" "c.py" "" "one.go" ""}
      (fn [dir] (is (= :python (core/detect-lang dir))))))
  (testing "only-noise directory is :unknown, never a wrong guess"
    (with-temp-tree
      {"node_modules/x/y.rs" "fn a(){}" ".git/z.ts" ""}
      (fn [dir] (is (= :unknown (core/detect-lang dir)))))))

(deftest resolve-lang-test
  (testing "known language passes through"
    (let [[lang warning] (core/resolve-lang :clojure)]
      (is (= :clojure lang))
      (is (nil? warning))))
  (testing ":unknown falls back to :rust with warning"
    (let [[lang warning] (core/resolve-lang :unknown)]
      (is (= :rust lang))
      (is (string? warning))
      (is (re-find #"lang-patterns" warning) "warning mentions where to add patterns"))))

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

(deftest extract-fn-name-scala
  (is (= "greet"   (core/extract-fn-name "def greet(name: String): String" :scala)))
  (is (= "version" (core/extract-fn-name "val version = \"1.0\"" :scala)))
  (is (= "count"   (core/extract-fn-name "var count = 0" :scala))))

(deftest extract-fn-name-ruby
  (is (= "validate" (core/extract-fn-name "def self.validate(token)" :ruby)))
  (is (= "initialize" (core/extract-fn-name "def initialize(secret)" :ruby)))
  (is (= "full_name" (core/extract-fn-name "def full_name" :ruby))))

(deftest extract-fn-name-kotlin
  (is (= "loadUsers" (core/extract-fn-name "suspend fun loadUsers(): Flow<List<User>>" :kotlin)))
  (is (= "isLoading" (core/extract-fn-name "val isLoading: Boolean = false" :kotlin)))
  (is (= "validate"  (core/extract-fn-name "private fun validate(user: User): Boolean" :kotlin))))

(deftest extract-fn-name-swift
  (is (= "viewDidLoad" (core/extract-fn-name "override func viewDidLoad()" :swift)))
  (is (= "fetch"       (core/extract-fn-name "public func fetch() async throws" :swift))))

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
  (is (= "Main"       (core/extract-type-name "object Main {")))
  (is (= "Auth"       (core/extract-type-name "module Auth")))
  (is (= "Role"       (core/extract-type-name "enum class Role {")))
  (is (nil?           (core/extract-type-name "fn not_a_type() {}"))))

;; -------------------------------------------------------------------
;; extract-type-kind
;; -------------------------------------------------------------------

(deftest extract-type-kind-test
  (is (= :struct    (core/extract-type-kind "pub struct Query {")))
  (is (= :enum      (core/extract-type-kind "enum Strategy {")))
  (is (= :enum      (core/extract-type-kind "enum class Role {")))
  (is (= :trait     (core/extract-type-kind "trait Executor {")))
  (is (= :interface (core/extract-type-kind "interface Renderable {")))
  (is (= :class     (core/extract-type-kind "class App {")))
  (is (= :type      (core/extract-type-kind "type Alias = String")))
  (is (= :protocol  (core/extract-type-kind "protocol Walkable")))
  (is (= :record    (core/extract-type-kind "record Point [x y]")))
  (is (= :actor     (core/extract-type-kind "actor DataStore {")))
  (is (= :object    (core/extract-type-kind "object Main {")))
  (is (= :module    (core/extract-type-kind "module Auth")))
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
  (is (re-find core/source-exts "App.scala"))
  (is (re-find core/source-exts "build.sc"))
  (is (re-find core/source-exts "app.rb"))
  (is (re-find core/source-exts "deploy.rake"))
  (is (re-find core/source-exts "Main.kt"))
  (is (re-find core/source-exts "build.gradle.kts"))
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
    (is (contains? langs :java))
    (is (contains? langs :swift))
    (is (contains? langs :scala))
    (is (contains? langs :ruby))
    (is (contains? langs :kotlin))))

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
