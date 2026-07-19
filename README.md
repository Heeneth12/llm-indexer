# llm-index

<img width="2266" height="1556" alt="image" src="https://github.com/user-attachments/assets/affbbce9-f437-4157-9489-25f0662a3831" />


Turns a Java codebase into an LLM-friendly index — a directory tree, class/method
skeletons, a dependency list, and a queryable SQLite knowledge graph — so an LLM
(or you) can find the relevant code without reading the whole repo.

Ships as one jar, two ways to use it:

- **CLI** — index a local project on your machine.
- **Web app** — paste a git URL, get back a browsable, searchable index. Deployable
  as a hosted service for other people to use.

Full reference (architecture, every config property, every CLI command, every HTTP route) is
also available in-app at `/llm-indexer/docs` once the web app is running.

## CLI

```bash
./mvnw -q -DskipTests clean package
java -jar target/llm-index-exec.jar build .          # index the current directory
java -jar target/llm-index-exec.jar query MyClass    # search the graph, writes context.md
```

Or grab a prebuilt jar from the [Releases page](https://github.com/Heeneth12/llm-indexer/releases)
instead of building it yourself.

Output goes to `.llm-index/` in the target project: `01-tree.md`, `02-skeleton.md`,
`03-dependencies.md`, `graph.db`, and `INSTRUCTIONS.md` telling an LLM how to read them.
Rebuilds are incremental — only changed files (by SHA-256) are re-parsed — unless you
pass `--full`.

### Visualizing the graph

```bash
java -jar target/llm-index-exec.jar visualize                    # whole graph, opens browser
java -jar target/llm-index-exec.jar visualize --filter Order     # only Order-related nodes
java -jar target/llm-index-exec.jar visualize --filter Order --hops 2   # + 2 hops of CALLS/USES
```

Writes a single self-contained `.llm-index/graph.html` (vis-network, loaded from a CDN — no
server needed to view it). Class/Method/Endpoint nodes are colored differently; drag nodes
around, double-click one to isolate its neighborhood, or use the search box. The same rendering
is available in the web app under a repo's **Graph** tab.

One real limitation: force-directed layouts turn into an unreadable hairball past a few hundred
nodes, and a real project easily has thousands once every method counts as a node. `--filter`
(and `--filter` + `--hops` for a proper neighborhood view) is how you keep it readable on
anything but a small repo — the "Classes only" checkbox in the generated page helps too.

## Use as a Maven dependency

Published via [JitPack](https://jitpack.io/#Heeneth12/llm-indexer) — built straight from this
repo, no separate publish step:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.Heeneth12</groupId>
  <artifactId>llm-indexer</artifactId>
  <version>v0.4.0</version> <!-- or any tag/commit from the Releases page -->
</dependency>
```

This resolves to the plain `llm-index.jar` (not the executable one), so
`com.llm.indexer.core.IndexService` and `com.llm.indexer.query.QueryService` land on your
classpath like any normal library. Note it currently pulls in the full Spring Boot web stack
transitively too, since core/CLI/web all live in one Maven module — fine for a quick embed,
worth splitting into a lighter `core`-only module later if that matters to you.

**To use the Spring beans (`StartupIndexRunner`, `IndexJobService`, and the web UI/`WebController`)
inside your own Spring Boot app**, they live in `com.llm.indexer.web`, which your app's default
component scan (rooted at your `@SpringBootApplication` class's package) won't see on its own.
Add:

```java
@SpringBootApplication
@ComponentScan(basePackages = {"your.own.package", "com.llm.indexer.web"})
@ConfigurationPropertiesScan(basePackages = "com.llm.indexer.web")
public class YourApplication { ... }
```

With that, `llm-index.startup.*` properties work exactly as documented below, and the full UI
mounts at `/llm-indexer/**` in your app — it can't collide with your own routes or static
assets, since everything (pages, CSS, API) is namespaced under that one prefix.

## Web app

```bash
./mvnw spring-boot:run
# or: java -jar target/llm-index-exec.jar
```

Open `http://localhost:8080/llm-indexer/`, paste a public git URL. The server shallow-clones
it into a temporary workspace, runs the same indexing engine as the CLI, and gives you
a tabbed view (Tree / Skeleton / Dependencies / Graph / Query) plus a source viewer that
jumps straight to the file:line a query result points at. Jobs and their temp clones are
evicted after 30 minutes.

Every route — pages, the CSS, the API — lives under `/llm-indexer/**` only. This is
deliberate: when llm-index is embedded as a library inside another Spring Boot app (see
below), its whole UI mounts at one predictable, namespaced path and can't collide with
that app's own routes or static assets.

Only `http://` / `https://` URLs are accepted; local/private addresses are rejected.

### Auto-index on startup

For a deployment where one specific repo should always be indexed and ready — no one has
to run a CLI command or paste a URL — set these in `application.properties` (or as env vars
/ `-D` flags) and it happens automatically when the app boots:

```properties
llm-index.startup.enabled=true

# either a local path already on disk (e.g. a mounted volume)...
llm-index.startup.path=/path/to/your/repo

# ...or a git URL, cloned on first boot and pulled on later restarts
llm-index.startup.repo-url=https://github.com/owner/repo.git
llm-index.startup.work-dir=/data/llm-index-clone

llm-index.startup.full=false               # force a full re-parse every run
llm-index.startup.reindex-on-restart=true  # false = reuse the existing index instead of rebuilding

llm-index.startup.graph-visualization-enabled=true  # also write .llm-index/graph.html each run
```

With `graph-visualization-enabled=true`, `.llm-index/graph.html` is generated on every run alongside
the markdown files — a static file you can open directly, in addition to the Graph tab at
`/llm-indexer/jobs/startup`.

Once configured, the result is always at `/llm-indexer/jobs/startup` and linked from the
landing page. This job is exempt from the 30-minute eviction that normal paste-a-URL jobs get, and its
directory is never auto-deleted — safe to point `path` at your real working repo.

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
├── viz/                      – GraphVisualizer: renders graph.db as an interactive HTML page
├── cli/                      – picocli commands (build, query, visualize)
└── web/                      – Spring MVC controller, git cloning, job lifecycle
src/main/resources/
├── templates/                – Thymeleaf views
└── static/style.css
```
