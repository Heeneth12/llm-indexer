# llm-index

Turns a Java codebase into an LLM-friendly index — a directory tree, class/method
skeletons, a dependency list, and a queryable SQLite knowledge graph — so an LLM
(or you) can find the relevant code without reading the whole repo.

Ships as one jar, two ways to use it:

- **CLI** — index a local project on your machine.
- **Web app** — paste a git URL, get back a browsable, searchable index. Deployable
  as a hosted service for other people to use.

## CLI

```bash
./mvnw -q -DskipTests clean package
java -jar target/indexer-*.jar build .          # index the current directory
java -jar target/indexer-*.jar query MyClass    # search the graph, writes context.md
```

Output goes to `.llm-index/` in the target project: `01-tree.md`, `02-skeleton.md`,
`03-dependencies.md`, `graph.db`, and `INSTRUCTIONS.md` telling an LLM how to read them.
Rebuilds are incremental — only changed files (by SHA-256) are re-parsed — unless you
pass `--full`.

## Web app

```bash
./mvnw spring-boot:run
# or: java -jar target/indexer-*.jar
```

Open `http://localhost:8080`, paste a public git URL. The server shallow-clones it
into a temporary workspace, runs the same indexing engine as the CLI, and gives you
a tabbed view (Tree / Skeleton / Dependencies / Query) plus a source viewer that jumps
straight to the file:line a query result points at. Jobs and their temp clones are
evicted after 30 minutes.

Only `http://` / `https://` URLs are accepted; local/private addresses are rejected.

## Docker

```bash
docker build -t llm-indexer .
docker run -p 8080:8080 llm-indexer
```

or

```bash
docker compose up --build
```

The image needs `git` at runtime (for the web app's clone step) — that's already
baked into the Dockerfile.

## Deploying

The image is a plain container that listens on `$PORT` (defaults to `8080`), so it
runs on anything that runs a container: Render, Fly.io, Railway, a VPS behind
Docker/nginx, etc. General shape:

1. Push this repo (or just the image) to your host of choice.
2. Point it at the `Dockerfile`, or `docker build` + push the image yourself.
3. Set `PORT` if the platform requires a specific one; otherwise the default `8080` is fine.

No database is required to run it — job state is in-memory and ephemeral by design.

## Project layout

```
src/main/java/com/llm/indexer/
├── IndexerApplication.java   – entrypoint: dispatches to CLI or web server
├── core/                     – TreeBuilder, SkeletonBuilder, DepsBuilder, GraphStore,
│                                Hashes, IndexService (shared by CLI and web)
├── query/                    – QueryService: graph traversal shared by CLI and web
├── cli/                      – picocli commands (build, query)
└── web/                      – Spring MVC controller, git cloning, job lifecycle
src/main/resources/
├── templates/                – Thymeleaf views
└── static/style.css
```
