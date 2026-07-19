package com.llm.indexer.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config-driven auto-indexing: set these in application.properties (or as env
 * vars / -D flags) and the app indexes a repo on boot with no CLI command and
 * no pasting a URL into the form. Backs the `llm-index.startup.*` properties.
 */
@ConfigurationProperties(prefix = "llm-index.startup")
public class StartupIndexProperties {

    /** Master switch. Nothing below does anything unless this is true. */
    private boolean enabled = false;

    /** A local directory already on disk (e.g. a mounted volume) to index on boot. */
    private String path;

    /** Alternative to {@code path}: a git URL to clone (or pull, on restart) and index on boot. */
    private String repoUrl;

    /** Where to clone {@code repoUrl} into. Defaults to a fixed dir under the system temp root. */
    private String workDir;

    /** Force a full re-parse every run, ignoring the SHA-256 change cache. */
    private boolean full = false;

    /** If false, and an index already exists from a previous run, reuse it instead of rebuilding. */
    private boolean reindexOnRestart = true;

    /** If true, also write .llm-index/graph.html (same output as `indexer visualize`) after each run. */
    private boolean graphVisualizationEnabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }

    public boolean isFull() { return full; }
    public void setFull(boolean full) { this.full = full; }

    public boolean isReindexOnRestart() { return reindexOnRestart; }
    public void setReindexOnRestart(boolean reindexOnRestart) { this.reindexOnRestart = reindexOnRestart; }

    public boolean isGraphVisualizationEnabled() { return graphVisualizationEnabled; }
    public void setGraphVisualizationEnabled(boolean graphVisualizationEnabled) { this.graphVisualizationEnabled = graphVisualizationEnabled; }
}
