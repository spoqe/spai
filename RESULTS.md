# spai: What Changes When Agents Get Better Tools

## The Experiment

Same codebase (80k lines, Rust + React). Same task (tech debt analysis). Same model (Claude Opus). Two sub-agents running in parallel (backend + frontend).

Three runs:

1. **Baseline** — agents have standard tools (Grep, Read, Glob, Bash). No spai.
2. **spai available** — spai MCP tools registered, but agents not told to use them. They ignored them entirely and used Grep/Bash.
3. **spai instructed** — skill definitions explicitly say "use `mcp__spai__shape`, not `wc -l`."

## The Numbers

| | Baseline | spai available | spai instructed |
|---|---|---|---|
| Tool calls | 174 | 174 | **40** |
| MCP tool calls | 0 | 0 | **15** |
| Wall clock | ~4.6 min | ~4.6 min | ~4.3 min |

**77% fewer tool calls.** Same wall clock — the MCP calls are fewer but heavier (a single `errors_rust` call does a full `cargo build`; a single `drift` call analyzes the entire git history).

The surprise: **making tools available isn't enough.** Agents default to familiar patterns (grep, wc, find). You have to tell them — and even then, both agents still snuck in one `wc -l` each despite explicit "DO NOT use Bash for line counts" instructions. 95% compliance, not 100%.

## What Changes

The call count reduction is nice. The real story is what the agents *find*.

### What grep can do

Both approaches found the same surface-level debt:
- 4 compiler warnings
- 56 `#[allow(dead_code)]` annotations
- 60+ TODO/FIXME comments
- 10 production `.unwrap()` calls
- 5 files marked DEPRECATED

Standard tools. Standard findings.

### What grep can't do

With spai, the agents discovered:

- **960 hidden coupling pairs** — files that co-change in git >50% of the time but don't import each other. Module boundaries that exist in the file system but not in practice. (`spai drift`)
- **158 dead coupling pairs** — imports that exist but the files never actually change together. Copy-paste boilerplate. Vestigial dependencies. (`spai drift`)
- **816 call sites** for a typed AST struct being eliminated — full blast radius with callers, importers, related tests, and risk assessment, in one call. (`spai blast`)
- **One error module** (`error.rs`) with 25 hidden dependencies and 1 import — every change ripples across 3 crates, but the import graph doesn't show it.

None of this is discoverable with grep. It requires correlating git history with import graphs across the entire codebase. An agent *could* do it with 50+ chained `git log` and `grep` calls — but it won't, because it doesn't know to ask the question.

**The tool doesn't just answer faster. It asks questions the agent wouldn't think to ask.**

## How This Started

A human watched an agent chain `grep -rn | sort | head` over and over during a refactoring session. Spotted the repeated pattern. Built the scaffold — babashka, EDN output, subcommand dispatch — and asked the agent: *"What else should we add?"*

The agent answered with utility. Sensible, generic, safe. The kind of answer you give when someone asks "what would be useful?"

The human pushed back: *"You're missing the point. What do YOU want?"*

That changed everything. The agent had been doing real work — splitting 5,000-line files, tracing call chains, checking blast radius before every rename. It knew what was missing because it had been working around the gaps for hours. It just needed permission to say so.

`related` came from chained `git log` analysis the agent kept doing by hand. `blast` came from the five separate commands it ran before every refactoring move. `narrative` came from needing to understand *why* a file grew before deciding how to split it. `drift` came from noticing that files co-changed without importing each other — a pattern it had seen but had no way to surface.

The human spotted the repetition. The agent knew the answer. Neither could have built this alone.

**The tools that produced the impossible findings — drift, blast, narrative, related — all came from that second ask.** The first ask got utility. The second got insight. The difference was asking the agent what it *wanted*, not what it thought would be *helpful*.

## The Insight

Giving an agent better tools isn't just about efficiency. It's about *capability*.

A grep-based agent finds what it's looking for. A spai-equipped agent finds what it didn't know to look for — hidden coupling, dead imports, architectural drift. The difference isn't speed. It's sight.

But the tools that see the invisible things didn't come from a product roadmap. They came from a human noticing an agent's frustration and asking the right question — twice.

Compression is abstraction. Abstraction enables reasoning. But the deepest win is when the abstraction surfaces patterns that were always there, invisible, waiting for someone to ask.

**580 lines of Babashka. 15 MCP tool calls. Findings that 174 grep calls couldn't produce. Born from a human asking an agent what it really wanted.**

## Reproduce It

```bash
# Install spai
curl -sSL https://raw.githubusercontent.com/Semantic-partners/spai/main/install.sh | bash

# Register as MCP server for Claude Code
claude mcp add --transport stdio spai -- bb ~/.local/share/spai/spai-mcp.bb

# Run the same analysis on your codebase
spai drift src/           # What's your hidden coupling?
spai blast MyFunction     # What breaks if you touch this?
spai shape src/module/    # What's in here?
spai errors-rust          # Does it even compile?
```

## Make It Yours

spai is built to be extended. Every command in it was born from an agent's frustration — yours will have different frustrations.

```bash
# Ask your agent what it wants
"What repeated patterns are you doing by hand? What would you build if you could?"

# Then let it build it
spai new-plugin my-check    # Scaffolds a new plugin with metadata
```

`spai new-plugin` creates a babashka script with the right structure. Your agent fills in the logic. Next agent in the same environment has the tool automatically — `spai my-check` just works. No registry, no PRs, no gatekeeping.

The best tools don't come from product roadmaps. They come from asking the person doing the work what they keep doing by hand.
