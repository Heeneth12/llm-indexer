# Connecting llm-index to an AI agent

llm-index isn't a plugin for one specific tool — it's a CLI (and, if you run the web app, an
HTTP API) that any agent with shell or network access can call. "Integrating" it means telling
your agent to use it instead of reading files blindly. This guide covers: indexing a repo,
wiring each major agent up to call it, and getting the most out of it once it's wired up.

## Why bother

Without an index, an agent either pastes whole files into its context (most of which is
irrelevant to the actual change) or greps around guessing. With llm-index, a query for a class
or keyword returns only the matching file:line references — typically under 10% of a file's raw
code — plus what depends on it and its outward call chain. Same understanding, a fraction of the
tokens, and edits land on the exact lines instead of nearby ones.

## Step 0 — Get the jar

```bash
# build it yourself
./mvnw -q -DskipTests clean package
# jar is at target/llm-index-exec.jar

# or grab a prebuilt one
# https://github.com/Heeneth12/llm-indexer/releases
```

Put it somewhere stable and remember the path — every snippet below references
`llm-index-exec.jar` directly. Aliasing it helps:

```bash
alias llm-index='java -jar /path/to/llm-index-exec.jar'
```

## Step 1 — Index the repo

Run this once per project, from the project root:

```bash
llm-index build .
```

Writes `.llm-index/` next to the code: `01-tree.md`, `02-skeleton.md`, `03-dependencies.md`,
`graph.db`, and `INSTRUCTIONS.md`. Rebuilds are incremental (a SHA-256 cache means only changed
files get re-parsed), so re-running this after every save is cheap. If you're running the web
app instead of the CLI, set `llm-index.startup.enabled=true` + `path` in
`application.properties` and this happens automatically on every boot — see the main
[README](README.md#auto-index-on-startup).

## Step 2 — Wire up your agent

The pattern is the same everywhere: add an instructions file the agent reads automatically,
telling it to run `llm-index query <term>` before editing instead of reading whole files or
grepping. Below is the exact snippet and file for each tool.

### Claude Code

File: `CLAUDE.md` at the repo root (Claude Code reads this automatically as project context).

```markdown
## Code navigation

This repo is indexed with llm-index. Before editing, run:

    java -jar /path/to/llm-index-exec.jar query <ClassName or keyword>

Read only the file:line references it returns — don't grep or open whole files speculatively.
If you need to trace further outward (what calls this, what it calls), add `--hops 2` or `--hops 3`.
Rebuild the index after structural changes: `java -jar /path/to/llm-index-exec.jar build .`
```

### Cursor

File: `.cursor/rules/llm-index.mdc` (Cursor's agent mode can run terminal commands when allowed).

```markdown
---
description: Use llm-index for code navigation before editing
alwaysApply: true
---

Before editing code, run `java -jar /path/to/llm-index-exec.jar query <term>` to find exact
file:line references instead of reading files speculatively. Add `--hops N` to trace callers
or callees further outward.
```

### GitHub Copilot (agent mode)

File: `.github/copilot-instructions.md` (natively read by Copilot's agent mode in VS Code).

```markdown
Before making changes, run `java -jar /path/to/llm-index-exec.jar query <ClassName>` from the
repo root to locate the exact file:line references involved, rather than searching broadly.
```

### Windsurf

File: `.windsurfrules` — same instruction as Cursor's above.

### Aider

Aider doesn't run arbitrary shell commands mid-session by default, so pre-generate context and
feed it in directly:

```bash
llm-index query OrderService --hops 2
aider --read .llm-index/context.md
```

Or add a short note to `CONVENTIONS.md` (which Aider reads via `--read`) pointing at the same
workflow.

### Any chat LLM with no shell access (ChatGPT web, Claude.ai chat, etc.)

No file-based integration is possible — do it manually:

```bash
llm-index query OrderService --hops 2
```

Paste the printed `context.md` content into the chat as your first message.

## Step 3 — Use it efficiently

- **Start with `--hops 1`.** It's the cheapest useful query — bump to 2 or 3 only if the agent
  actually needs to trace further outward. Every extra hop grows the result.
- **Let the agent drive queries itself when it can.** If it has shell access, the efficiency win
  is the agent running `query` per task on demand — not you manually re-pasting context for
  every change. A human doing the pasting is the fallback for chat-only tools, not the ideal.
- **Rebuild after structural changes**, not after every keystroke. The SHA-256 cache makes this
  cheap, but there's no point rebuilding mid-edit before you've saved.
- **Don't reach for `visualize` as per-task context.** The interactive graph is for exploring
  structure ("what does this subsystem look like") — it's too much for a single focused change.
  Use `query` for that; save `visualize --filter <term> --hops 2` for understanding a
  neighborhood before a bigger refactor.
- **Scope `visualize` if you use it.** Past a few hundred nodes, force-directed layouts turn
  into a hairball. `--filter <term>` (optionally with `--hops`) keeps it readable.

## A note on true tool/function-call integration

Everything above works by the agent shelling out to a CLI command and reading text back. Some
agents (Claude Code, Claude Desktop, Cursor, and others) also support **MCP (Model Context
Protocol)** servers — pluggable tool providers the agent can call as real functions
(`query_index(term, hops)`) instead of parsing CLI output. llm-index doesn't ship one yet; the
CLI/instructions-file approach above is the current integration path. Worth revisiting if you
want tighter, structured integration later.
