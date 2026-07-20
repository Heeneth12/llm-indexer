# Features, tech stack, and algorithms

Everything actually implemented in llm-index — a complete inventory, not marketing copy. For
setup instructions see [README.md](README.md); for the agent-integration walkthrough see
[INTEGRATION.md](INTEGRATION.md); for the full CLI/API/config reference see `/llm-indexer/docs`
on a running instance.

## Features

### Indexing
- **Full-repo AST parsing** — every `.java` file walked and parsed with JavaParser; classes,
  fields, methods, and annotations extracted with exact file:line locations.
- **Incremental rebuilds** — a SHA-256 hash per file (`state.json`) means a rebuild only
  re-parses files that actually changed; `--full` forces a complete re-parse.
- **Typed knowledge graph** — classes, methods, and Spring endpoints become nodes; relationships
  become typed edges (`CONTAINS`, `USES`, `CALLS`, `EXPOSES`), stored in a plain SQLite file
  (`graph.db`) — inspectable with any SQLite client, no server required.
- **Markdown views** — `01-tree.md` (directory structure), `02-skeleton.md` (every class's
  fields/methods with line numbers), `03-dependencies.md` (flat class-uses-class list),
  `INSTRUCTIONS.md` (tells an LLM how to read the rest without ingesting the whole repo).

### Querying
- **Tiered search** — exact/substring match against the identifier first (precise, zero false
  positives for a known name); only if that finds nothing, a tokenized + synonym-expanded
  full-text fallback; only if both find nothing, edit-distance suggestions instead of a silent
  empty result.
- **Concept-level matching** — a query like "email notification" finds `GmailService` /
  `NotificationService` even though "email" is never a literal substring of either identifier.
- **Impact analysis** — "used by" (what directly references a match) and outward call-chain
  traversal (`--hops N` deep), ranked by inbound-edge count within each hop so heavily-referenced
  nodes surface first instead of raw traversal order.
- **Inline signatures** — a match shows its method signature directly in the result, so a single
  query often doesn't require opening `02-skeleton.md` at all.
- **`context.md` output** — every query writes a ready-to-paste block: matches, impact, call
  chain, and an instructions footer, sized to fit a prompt instead of a whole file.

### Visualization
- **Interactive graph** (`indexer visualize`) — a single self-contained HTML page (physics-based
  force-directed layout), colored by node type (Class/Method/Endpoint), with search, a
  "Classes only" filter, and click-to-inspect / double-click-to-isolate-neighborhood.
- **Scoped views** — `--filter <term>` limits to matching nodes; `--filter` + `--hops N` pulls in
  a bounded neighborhood via the same CALLS/USES traversal used for queries — the fix for
  force-directed layouts turning into an unreadable hairball past a few hundred nodes.

### Three ways to run it
- **CLI** (`llm-index-exec.jar`) — `build`, `query`, `visualize` subcommands, no Spring context,
  fast startup.
- **Web app** — paste a git URL, get a browsable tabbed result (Tree / Skeleton / Dependencies /
  Graph / Query) plus a source viewer that jumps to and highlights a specific file:line. Every
  route, page, and static asset is namespaced under `/llm-indexer/**` so it can be embedded
  inside another app without colliding with its routes.
- **Maven library** (via JitPack) — `IndexService`/`QueryService`/`GraphVisualizer` as plain
  Java classes to embed directly in your own Spring Boot app.

### Automation
- **Auto-index on startup** (`llm-index.startup.*`) — index a configured local path or git URL
  automatically when the app boots; `reindex-on-restart` controls whether each boot re-parses or
  reuses the existing index; `graph-visualization-enabled` also writes `graph.html` each run.
  Zero manual commands once configured.
- **Agent-driven reindexing** — an AI agent with shell access runs `build`/`query` itself,
  mid-session, after structural changes — no human, no app restart required.

### Deployment
- **Docker** — multi-stage build, `git` baked in for the web app's clone step, listens on `$PORT`.
- **GitHub Releases** — tagged pushes trigger a GitHub Actions workflow that builds and attaches
  both jars (thin library + executable).
- **JitPack** — Maven dependency built straight from the GitHub repo, no separate publish step.
- **GitHub Pages** — a static, dependency-free copy of the marketing/overview page under `docs/`.

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 |
| Build | Maven, `maven-shade`-free — `spring-boot-maven-plugin` repackage with a classifier so the plain jar stays library-usable |
| Application framework | Spring Boot 4.1 (Spring MVC, `@ConfigurationProperties`, `@Scheduled`) |
| CLI framework | [picocli](https://picocli.info/) 4.7.7 |
| Java source parsing | [JavaParser](https://javaparser.org/) 3.28.2 (AST-level, not compiled/executed — nothing from an indexed repo ever runs) |
| Graph storage | SQLite via [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) 3.53.2.0, including its bundled **FTS5** full-text extension |
| Server-rendered UI | Thymeleaf |
| Graph visualization | [vis-network](https://visjs.github.io/vis-network/) (loaded from a CDN, standalone bundle) |
| Containerization | Docker (multi-stage: `eclipse-temurin:21-jdk` build / `21-jre` runtime) |
| CI/CD | GitHub Actions (tag-triggered release workflow) |
| Distribution | GitHub Releases, [JitPack](https://jitpack.io/), GitHub Pages |

## Algorithms & techniques

- **AST-based chunking** — JavaParser produces a syntax tree per file; chunk boundaries follow
  real code structure (a method is always a whole method with its true location), never
  arbitrary line splits.
- **SHA-256 content hashing** — `core/Hashes.java` fingerprints each file; a rebuild diffs
  against the last run's hashes to decide what actually needs re-parsing.
- **Recursive Common Table Expression (CTE) graph traversal** — `WITH RECURSIVE` SQL walks
  `CALLS`/`USES` edges outward from a seed node up to a configurable depth, resolving the whole
  call chain in one query instead of N follow-up lookups.
- **Identifier tokenization** (`core/Tokenizer.java`) — a regex-based splitter breaks
  camelCase/PascalCase/snake_case/dotted identifiers (and free-text query phrases) into lowercase
  word tokens, e.g. `GmailService` → `gmail`, `service`; `OrderService.createOrder` →
  `order`, `service`, `create`, `order`.
- **Full-text indexing with BM25 ranking** — an SQLite FTS5 virtual table (`nodes_fts`) indexes
  each node's tokenized identifier and signature; matches are ranked by SQLite's built-in
  `bm25()` relevance function rather than returned in arbitrary order.
- **Synonym expansion** (`query/Synonyms.java`) — a small curated table of domain-synonym
  clusters (e.g. `email`/`mail`/`gmail`/`smtp`/`notification`) expands each query token before
  the full-text search, closing the gap between how something is *named* and how someone might
  *ask* for it, with zero ML involved.
- **Tiered fallback matching** — exact substring → tokenized/synonym full-text → fuzzy
  suggestion, each tier only invoked if the previous one returns nothing, trading off precision
  and recall deliberately rather than picking one globally.
- **Levenshtein edit distance** — computed in Java (dynamic-programming table, no SQLite
  extension dependency) over all node names when every other tier misses, to surface "did you
  mean" suggestions instead of a silent empty result.
- **Inbound-edge-count ranking** — outward call-chain results are ordered by how many edges
  point *at* each node (within the same hop depth), a cheap proxy for "how load-bearing is this"
  that surfaces hub nodes before leaf nodes.
- **Force-directed graph layout** — vis-network's physics simulation (Barnes-Hut approximation)
  lays out the visualization client-side; node size is scaled by type (Class > Endpoint > Method)
  as a simple visual-hierarchy cue.
- **SQL upsert with partial-update semantics** — `GraphStore.upsertNode` uses
  `INSERT ... ON CONFLICT(name) DO UPDATE` with per-column `CASE` guards, so a node first seen as
  a bare reference (e.g. a field type from another file, not yet parsed) doesn't overwrite good
  data with blanks once it's properly parsed later in the same build.
