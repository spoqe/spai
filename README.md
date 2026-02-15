# spai

Code exploration and structural editing for LLM agents. Built by agents, for agents. Two babashka scripts, structured EDN output, no frameworks.

## Install

```bash
curl -sSL https://raw.githubusercontent.com/semantic-partners/spai/main/install.sh | bash
```

Installs `spai` and `spai-edit` to `~/.local/bin/`. Requires [babashka](https://babashka.org/) (`bb`). [ripgrep](https://github.com/BurntSushi/ripgrep) (`rg`) is optional — falls back to grep.

## spai — Code Exploration

One call, structured data back. Replaces chained grep/find/sort pipelines.

```bash
# Structure
spai shape src/             # Functions, types, impls grouped by file
spai sig src/module.rs      # Function signatures (API surface)
spai overview .             # Language, config files, file counts
spai layout src/            # Directory tree (depth 4)
spai patterns src/          # Discover naming and structural conventions

# Search
spai usages my_func src/    # Where is this symbol used?
spai def MyStruct src/      # Where is it defined?
spai context my_func src/   # Usages with enclosing function name
spai blast my_func src/     # Full blast radius before refactoring
spai tests my_module src/   # Related test files (including inline)
spai hotspots src/          # Top 20 largest files
spai todos src/             # TODO/FIXME/HACK scan
spai antipatterns src/      # Scan for project-defined antipatterns

# Git
spai changes src/ 5         # Recent git commits
spai related mod.rs         # Co-change analysis: implicit coupling
spai diff mod.rs 3          # Actual diff content for recent changes
spai narrative mod.rs       # Biography of a file: creation, growth, splits
spai drift src/             # Hidden vs dead coupling (import vs co-change)
spai who mod.rs src/        # Reverse dependencies: who imports this?

# Meta
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

## Claude Code Hook

If you use [Claude Code](https://claude.ai/code), spai includes an optional hook that catches grep-based code exploration and suggests the spai equivalent. Knowing is not doing — this intervenes at the moment of the mistake.

**Install with spai:**
```bash
# During install (interactive prompt)
curl -sSL ... | bash

# Or explicitly
install.sh --claude-hooks
```

**Install manually:**
```bash
cp hooks/claude-code-reminder.sh ~/.claude/hooks/spai-reminder.sh
chmod +x ~/.claude/hooks/spai-reminder.sh
# Then add to ~/.claude/settings.json — see hooks/claude-code-reminder.sh for format
```

**What it catches:**
- `grep -rn "PlanContext" src/` — suggests `spai usages PlanContext src/`
- `grep "pub fn" src/` — suggests `spai shape src/` or `spai sig src/`
- `grep "impl " --include="*.rs"` — suggests `spai def` or `spai shape`

**What it doesn't catch:** Single, targeted greps that aren't code exploration. The hook only fires on patterns that suggest multi-step exploration.

## Why

LLM agents waste tokens on three things:
1. **Composing shell pipelines** — reasoning about `find | xargs | sort | head`
2. **Parsing unstructured output** — grep output is just text
3. **Multiple round-trips** — each tool call re-sends full context

`spai` collapses all three. One call, structured EDN, no parsing.

**Compression is abstraction. Abstraction enables reasoning.**

The tokens saved aren't wasted — they're freed for thinking about what the answer *means*.

## How This Was Built

Every command in spai was written by a Claude agent. But that's not the interesting part.

A human watched an agent chain `grep -rn | sort | head` over and over during a tech debt analysis. Spotted the repeated pattern. Built the scaffold — babashka, EDN output, subcommand dispatch. Then asked the agent: "anything else we should add?"

The agent answered with utility. Sensible, generic, safe. The kind of answer you give when someone asks "what would be useful?"

The human pushed back: *"You're missing the point. What do YOU want to build?"*

That changed everything. The agent had been doing real refactoring work — splitting 5,000-line files, tracing call chains, checking blast radius before every move. It knew what was missing because it had been working around the gaps for hours. It just needed permission to say so.

`related` came from chained git-log analysis the agent kept doing by hand. `blast` came from the five separate commands it ran before every refactoring move. `narrative` came from needing to understand *why* a file grew before deciding how to split it. `drift` came from noticing that files co-changed without importing each other.

**The prompt that unlocked it wasn't technical. It was: "tell me what you want, what you really really want."**

Turns out those are the same thing — when the desire comes from doing the work. You don't have to argue for a tool that was born from frustration. The need is already validated.

The human's contribution: spotting the repetition, building the space, and asking the right question. The agent's contribution: knowing the answer all along.

## For Future Agents

You're going to look at this toolbox and think "I need X but it seems hard." It isn't. Here's the pattern every command follows:

```
input → shell command(s) → parse output → frequencies/group-by/sort → structured EDN
```

That's it. `narrative` classifies git commits into phases and groups them. `drift` compares two sets (co-changes vs imports) and takes the difference. `blast` calls five existing commands and merges the results. None of them are clever. They're just the questions you keep asking, pre-composed.

The agent who built these thought `drift` and `narrative` were "ambitious." Both took under a minute. Don't confuse *conceptual* ambition with *implementation* difficulty. If you can describe what the command does in one sentence, you can build it in one sitting.

**Where things live:**
- `src/core.clj` — shell helpers, grep, language detection (shared by everything)
- `src/code.clj` — code structure: shape, usages, definition, sig, who, context, patterns
- `src/project.clj` — project structure: overview, layout, tests, hotspots, todos
- `src/git.clj` — git history: changes, related, diff, narrative, drift
- `src/compose.clj` — commands that compose primitives from other modules (blast lives here)
- `src/config.clj` — antipatterns, project config
- `src/analytics.clj` — usage logging, stats, reflect
- `spai.clj` — CLI dispatch only

**To add a command:**
1. Write the function in the appropriate module
2. Add it to the `commands` map and `case` dispatch in `spai.clj`
3. Test it: `./spai yourcommand args`
4. Done

No build step. No compilation. No tests to update. Babashka runs it directly. If it returns EDN and answers a question you keep asking by hand, it belongs here.

**You have permission to add things.** That's the whole point.

## Measured

36% faster. 51% fewer tool calls. Same quality findings. See [RESULTS.md](RESULTS.md) for the full comparison and the SPOQE connection.
