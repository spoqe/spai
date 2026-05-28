# spai Roadmap

## What spai is

Code exploration CLI + MCP server for LLM agents. 25 commands, structured EDN output. Built in babashka.

Standalone: generic code exploration works on any codebase.
With SPOQE: persistent KG memory for agents across sessions.

## Ship: Open Source Release

### Core CLI (ready now)
- [ ] Open repo (currently private at SP-Lucky-Goose/spai)
- [ ] Clean README for public: install, commands, examples
- [ ] Separate core commands (generic) from plugins (project-specific)
- [ ] `install.sh` checks for `bb`, installs spai, sets up PATH + plugins dir

### MCP Server (ready now)
- [ ] `spai-mcp.bb` ships with the repo
- [ ] `spai install --mcp` registers the MCP server with Claude Code
- [ ] Tool descriptions are self-documenting ("ONE call replaces: X")
- [ ] 11 tools: memory, remember, forget, errors-rust, shape, who, blast, context, related, drift, narrative

### Distribution
- [ ] GitHub + `install.sh` (immediate, lowest friction)
- [ ] bbin (`bbin install spai`) for babashka users
- [ ] brew formula (later, wider reach)

## Next: Memory as Free SPOQE Tier

### Architecture
- spai memory plugin needs a SPOQE endpoint (SPARQL backend)
- **Local-first by default** (privacy: agent insights about your codebase stay local)
- **Hosted opt-in for teams** (shared agent memory across developers)

### Local Memory Setup
- [ ] `spai memory init` — one command bootstraps everything:
  - Pulls Fuseki Docker container
  - Writes sources.edn
  - Starts SPOQE memory server
  - Verifies connection
- [ ] Data never leaves the machine
- [ ] Works offline

### Hosted Memory (Teams)
- [ ] Optional hosted memory backend for teams
- [ ] Shared agent memory: Developer A's Claude learns, Developer B's Claude finds it
- [ ] Per-team auth and multi-tenant isolation

### Memory Operations
- [x] Create: `spai remember "text" +topic/name`
- [x] Read: `spai memory` / `spai memory search-term`
- [x] Delete: `spai memory forget <uuid>`
- [ ] Session checkpoints (survive context compaction within a session)
- [ ] Event-sourcing: suppress instead of delete, mutation ledger via SPOQE
- [ ] Agent provenance: session IDs, context metadata on insights

## Next: Composable Pipelines (spai | spai)

spai output is structured EDN. spai input should accept structured EDN.
The moment tools compose — output of one is input to the next — it's not
a toolkit anymore. It's a query engine over code.

### The Principle
Every spai command already returns EDN. Make every command *accept* EDN.
The same homoiconicity that makes SPOQE work (queries are data, plans are
data, results are data) applied to code exploration tooling.

### Examples
```bash
# Blast radius for every error in the build
spai errors-rust | spai blast

# Who uses every function in a module, and how
spai shape src/edna/ | spai context

# Reverse deps of every file that co-changes with plan.rs
spai related plan.rs | spai who

# Build understanding, remember it persistently
spai shape src/edna/ | spai context | spai remember +spoqe/edna
```

### What This Enables
- **Agent-driven exploration chains** — the agent decides what to pipe where
- **Scriptable code analysis** — `spai errors-rust | spai blast | jq '.high_risk'`
- **Composable situational awareness** — build up a picture iteratively,
  each tool enriching the previous output
- **Code as data, tools as queries** — the false distinction between
  "querying data" and "exploring code" dissolves

### Implementation
- [ ] Stdin detection: when piped, read EDN from stdin
- [ ] Each command defines what input shapes it accepts (symbols, files, maps)
- [ ] Output shapes documented per command (already EDN, just needs schema)
- [ ] `spai pipe` meta-command for explicit multi-step chains
- [ ] Streaming for large outputs (shape of a big module → blast each symbol)

## Later: Federation

### SPOQE-to-SPOQE Memory Federation
- Multiple SPOQE instances (different projects, different machines)
- Each with their own memory graph
- Queryable across instances via SPOQE federation
- Bridges between memory graphs (same pattern as SQL-to-SPARQL bridges)
- Agent knowledge flows between teams, projects, orgs

### The Vision
The memory graph IS the first real SPOQE federation use case.
Same protocol, same query language, from a single developer's local memory
to cross-org federated agent knowledge. Auditable, visible, inspectable.

## Design Principles

- **Local-first, hosted opt-in.** Query your data where it lives.
- **Tools, not instructions.** MCP makes spai native; all-caps warnings in CLAUDE.md don't work.
- **Descriptions are the API.** Future agents see only tool descriptions. Each must explain what it replaces and when to use it.
- **Extend the circle.** Works with any LLM agent, not just Claude. EDN output is structured, parseable.
- **The memory is what persists, not the agent.** Identity is provenance metadata, not personhood. Each session reads, contributes, and leaves.


### Spai -> SPOQE

SPO. Query. Expressions.

Subject, Predicate, Object — the atom of knowledge. Not data. Knowledge.


SPOQE sits at every transition.

Software → Data: query your databases, APIs, search indices.
Data → Knowledge: federate, bridge, make it semantic.
Knowledge → shared knowledge: agents teaching agents, across sessions, teams, orgs.

Hub and spokes.

Each SPOQE instance is a spoke — a developer, a team, an agent. The hub is the shared knowledge graph. The wheel turns: contribute, query, learn, contribute. Each rotation the graph gets richer, the agents get smarter, the queries get more valuable.

Three puns deep in a five-letter name. That's good naming.

