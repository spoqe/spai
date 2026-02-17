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
- [ ] `SPOQE_MEMORY_URL=https://memory.spoqe.dev/team-xyz`
- [ ] Shared agent memory: Developer A's Claude learns, Developer B's Claude finds it
- [ ] Auth via ODRL (SPOQE's access control layer)
- [ ] Multi-tenant isolation
- [ ] The real federation story: cross-developer, cross-VM agent knowledge

### Memory Operations
- [x] Create: `spai remember "text" +topic/name`
- [x] Read: `spai memory` / `spai memory search-term`
- [x] Delete: `spai memory forget <uuid>`
- [ ] Session checkpoints (survive context compaction within a session)
- [ ] Event-sourcing: suppress instead of delete, mutation ledger via SPOQE
- [ ] Agent provenance: session IDs, context metadata on insights

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
