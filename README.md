# spai

Code exploration and structural editing for LLM agents. Two babashka scripts, structured EDN output, no frameworks.

## Install

```bash
curl -sSL https://raw.githubusercontent.com/semantic-partners/spai/main/install.sh | bash
```

Installs `spai` and `spai-edit` to `~/.local/bin/`. Requires [babashka](https://babashka.org/) (`bb`). [ripgrep](https://github.com/BurntSushi/ripgrep) (`rg`) is optional — falls back to grep.

## spai — Code Exploration

One call, structured data back. Replaces chained grep/find/sort pipelines.

```bash
spai shape src/             # Functions, types, impls grouped by file
spai usages my_func src/    # Where is this symbol used?
spai def MyStruct src/      # Where is it defined?
spai sig src/module.rs      # Function signatures (API surface)
spai overview .             # Language, config files, file counts
spai layout src/            # Directory tree (depth 4)
spai tests my_module src/   # Related test files (including inline)
spai hotspots src/          # Top 20 largest files
spai changes src/ 5         # Recent git commits
spai antipatterns src/      # Scan for project-defined antipatterns
spai stats                  # Usage analytics
spai reflect                # Usage patterns with observations
```

Multi-language: Rust, TypeScript, Python, Go, Clojure. Auto-detected.

## spai-edit — Structural Editing for Clojure/EDN

Operates on forms, not text. Uses rewrite-clj (bundled in babashka). No paren counting.

Works on: `.clj`, `.cljs`, `.cljc`, `.edn`, `.bb` — anything that's s-expressions.

```bash
# Forms
spai-edit forms spai.clj                    # List all top-level forms
spai-edit find-form spai.clj shape          # Show a named form
spai-edit replace-form f.clj foo '(defn foo [x] (inc x))'
spai-edit insert-after f.clj foo '(defn bar [x] x)'
spai-edit extract-body spai.clj shape       # Body only (no def/name/args)
spai-edit replace-body f.clj foo '(inc x)'  # Replace body, keep signature
spai-edit validate spai.clj                 # Structural parse check

# Maps (EDN config files)
spai-edit get-in sources.edn :sources :kg
spai-edit set-in sources.edn :sources :kg :endpoint '"http://new"'
spai-edit merge-in sources.edn :sources :kg -- :timeout 5000 :auth :bearer
```

## Extending spai

spai uses the git subcommand pattern: `spai foo` looks for `spai-foo` in PATH. Drop a `spai-whatever` script anywhere on PATH and it just works. No PRs, no registry, no gatekeeping.

Agent writes a new command, saves it as `spai-mycheck`, done. Next agent in the same environment has it too.

## Project Config (.spai.edn)

The tool is general-purpose. Project-specific knowledge lives in `.spai.edn` at your project root.

```edn
{:antipatterns
 {:unwrap-in-production
  {:patterns    [".unwrap()"]
   :exclude     ["test" "spec"]
   :description "No .unwrap() outside tests."
   :severity    :high}

  :todo-fixme
  {:patterns    ["TODO" "FIXME" "HACK"]
   :description "Unresolved work items."
   :severity    :low}}}
```

Then:
```bash
spai antipatterns src/                        # Run all
spai antipatterns unwrap-in-production src/    # Run one
```

**Config fields:**
- `:patterns` — literal strings to search for (fixed-string, not regex)
- `:exclude` — skip hits in files containing these substrings
- `:description` — what the antipattern means and what to do instead
- `:severity` — `:high`, `:medium`, `:low`

The tool walks up the directory tree to find `.spai.edn`, so it works from any subdirectory.

## Why

LLM agents waste tokens on three things:
1. **Composing shell pipelines** — reasoning about `find | xargs | sort | head`
2. **Parsing unstructured output** — grep output is just text
3. **Multiple round-trips** — each tool call re-sends full context

`spai` collapses all three. One call, structured EDN, no parsing.

**Compression is abstraction. Abstraction enables reasoning.**

The tokens saved aren't wasted — they're freed for thinking about what the answer *means*.

## Measured

36% faster. 51% fewer tool calls. Same quality findings. See [RESULTS.md](RESULTS.md) for the full comparison and the SPOQE connection.
