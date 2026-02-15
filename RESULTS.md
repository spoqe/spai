# spai: LLM Code Exploration in Babashka

## The Experiment

We ran the same tech debt analysis twice on an 80k-line Rust + React codebase, using two Claude Code sub-agents in parallel (one backend, one frontend).

**Run 1:** Agents used raw `find | wc -l`, `grep -rn`, and chained shell commands.
**Run 2:** Agents used `spai` — a 580-line Babashka script returning structured EDN.

## The Numbers

| | Run 1 (grep/find) | Run 2 (spai) |
|---|---|---|
| Wall clock | 7 min | 4.5 min |
| Tool calls | 134 | 65 |
| Findings | 47 | 38 |

**36% faster. 51% fewer tool calls. Same quality findings.**

### Token Usage (TODO: Measure)

Tool calls and wall clock are proxies. The real cost is tokens — that's what people budget now. We didn't capture token counts for these runs. Next comparison should measure:

- **Input tokens**: each tool call re-sends the full conversation context. 51% fewer calls ≈ 51% fewer context re-reads. This is probably where the biggest saving hides.
- **Output tokens**: structured EDN vs raw grep output. EDN is more compact but the agent reasons less about parsing — net effect unclear.
- **Approximation**: Claude Code doesn't expose per-subagent token metrics. Proxy: measure response sizes (bytes returned per tool call) × call count. Or run identical tasks with `--verbose` logging and compare session totals.

The difference: `spai hotspots spoqe-exec/src/` returns the top 20 largest files in one call. Without it, the agent does `find -name "*.rs"`, pipes to `wc -l`, pipes to `sort -rn`, then reads the output — 3-4 tool calls for the same information.

`spai usages unwrap spoqe-exec/src/` returns every occurrence with file, line, and context. Without it: `grep -rn "unwrap" --include="*.rs"` then parse the output, often followed by additional greps to refine.

## Why It Works

LLM agents waste tokens on three things:
1. **Composing shell pipelines** — the agent has to reason about `find | xargs | sort | head`
2. **Parsing unstructured output** — grep output is just text, the agent re-parses it every time
3. **Multiple round-trips** — each tool call is an API round-trip with full context

`spai` collapses all three. One call, structured EDN output, no parsing needed.

## The Commands That Matter for Tech Debt

```bash
spai hotspots src/        # Where's the debt? (top 20 by size)
spai usages unwrap src/   # Anti-pattern hunting
spai shape src/module/    # Module structure at a glance
spai sig src/file.rs      # API surface (function signatures)
```

Four commands replaced ~70 shell invocations per agent.

## What's in the Box

580 lines of Babashka. No dependencies beyond `bb` and `rg` (falls back to `grep`).

- `hotspots` — largest files, sorted
- `shape` — functions, types, impls grouped by file
- `usages` — word-boundary symbol search across code files
- `sig` — function signatures (the header file view)
- `def` — find where a symbol is defined (not just used)
- `overview` — project language, config, file counts
- `layout` — directory tree (skips node_modules, target, etc.)
- `tests` — find test files related to a source file
- `changes` — recent git history for a path
- `stats` / `reflect` — usage analytics with self-improvement hints

Multi-language: Rust, TypeScript, Python, Go, Clojure. Auto-detected.

## The Insight

**Compression is abstraction. Abstraction enables reasoning.**

The tokens saved aren't wasted — they're freed. Instead of spending them on `find | xargs | sort | head`, the agent spends them thinking about what the answer *means*. A good tool doesn't just return data faster. It moves the agent from syntax to semantics.

This is true whether you're paying for a frontier model or running a local one. Compression frees space for thinking — and the space is finite either way.

LLM agents don't need fancy frameworks. They need **one good tool that returns structured data**. The agent already knows how to reason — it just needs fewer round-trips to get the facts.

A 580-line Babashka script beat 134 shell commands. Not because it's smarter, but because it compresses.

## Now Scale It

That was files on disk. One codebase. One machine.

Your data lives in Postgres, Fuseki, Elasticsearch, Notion, PubMed — scattered across protocols and schemas. An LLM agent trying to explore *that* faces the same problem, worse: composing raw SQL, hand-writing SPARQL, parsing JSON responses, stitching results across backends. Each one is a round-trip. Each one wastes tokens on syntax instead of thinking.

SPOQE does for your data silos what `spai` does for your filesystem:

```
"Find tracks matching 'love', with artist names and review ratings.
 The search index is Elasticsearch. Artists are in the knowledge graph. Reviews are in Postgres."
```

```clojure
;; One query. Three backends. Structured results.
{:sp/pull [(text-search [:mo/Track :dc/title] "love")
           :dc/title
           {:foaf/maker [:foaf/name]}
           {:chinook/reviews [:rating]}]}
```

The agent doesn't name a backend. `text-search` is a semantic operation — the planner routes it to Elasticsearch because the catalog says that's where text search lives. Artist names come from the knowledge graph (SPARQL). Reviews come from Postgres (SQL). The agent doesn't write any of those query languages, doesn't join the results, doesn't know the protocols. One question in EDN, structured data back.

Same principle. Same win. Bigger scale.

**[github.com/Semantic-partners/spoqe](https://github.com/Semantic-partners/spoqe)**
