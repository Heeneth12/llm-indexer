package com.llm.indexer.web;

import com.llm.indexer.core.IndexResult;

import java.nio.file.Path;
import java.time.Instant;

public class IndexJob {

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    private final String id;
    private final String repoUrl;
    private final Path tempDir;
    private final Instant createdAt;

    private volatile Status status = Status.PENDING;
    private volatile IndexResult result;
    private volatile String errorMessage;

    public IndexJob(String id, String repoUrl, Path tempDir) {
        this.id = id;
        this.repoUrl = repoUrl;
        this.tempDir = tempDir;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getRepoUrl() { return repoUrl; }
    public Path getTempDir() { return tempDir; }
    public Instant getCreatedAt() { return createdAt; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public IndexResult getResult() { return result; }
    public void setResult(IndexResult result) { this.result = result; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Path graphDb() { return tempDir.resolve(".llm-index").resolve("graph.db"); }
}
