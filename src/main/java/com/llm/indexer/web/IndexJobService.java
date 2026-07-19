package com.llm.indexer.web;

import com.llm.indexer.core.IndexResult;
import com.llm.indexer.core.IndexService;
import com.llm.indexer.query.QueryResult;
import com.llm.indexer.query.QueryService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs each indexing job (clone + build) on its own virtual thread and keeps
 * results in memory for a bounded time. No persistence layer yet — jobs are
 * ephemeral, which is fine for a "paste a URL, get an index" tool; a
 * Postgres-backed job history is a natural next step if this needs to
 * survive restarts.
 */
@Service
public class IndexJobService {

    private static final Logger log = LoggerFactory.getLogger(IndexJobService.class);
    private static final Duration JOB_TTL = Duration.ofMinutes(30);

    private final Map<String, IndexJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public String createJob(String repoUrl) {
        String id = UUID.randomUUID().toString();
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("llm-index-job-" + id.substring(0, 8) + "-");
        } catch (IOException e) {
            throw new RuntimeException("Could not allocate a workspace for this job", e);
        }

        IndexJob job = new IndexJob(id, repoUrl, tempDir);
        jobs.put(id, job);
        executor.submit(() -> runJob(job));
        return id;
    }

    private void runJob(IndexJob job) {
        job.setStatus(IndexJob.Status.RUNNING);
        try {
            GitCloner.clone(job.getRepoUrl(), job.getTempDir());
            IndexResult result = IndexService.build(job.getTempDir(), job.getTempDir().resolve(".llm-index"), true);
            job.setResult(result);
            job.setStatus(IndexJob.Status.DONE);
        } catch (Exception e) {
            log.warn("Job {} failed: {}", job.getId(), e.getMessage());
            job.setErrorMessage(e.getMessage() == null ? e.toString() : e.getMessage());
            job.setStatus(IndexJob.Status.FAILED);
        }
    }

    public Optional<IndexJob> getJob(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public QueryResult query(IndexJob job, String term, int hops) throws Exception {
        return QueryService.query(job.graphDb(), term, hops);
    }

    /** Resolves a repo-relative path against the job's clone, refusing anything that escapes it. */
    public Path resolveSourceFile(IndexJob job, String relPath) {
        Path resolved = job.getTempDir().resolve(relPath).normalize();
        if (!resolved.startsWith(job.getTempDir())) {
            throw new SecurityException("Path escapes the job workspace");
        }
        return resolved;
    }

    @Scheduled(fixedRate = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void sweepExpiredJobs() {
        Instant cutoff = Instant.now().minus(JOB_TTL);
        jobs.values().removeIf(job -> {
            boolean expired = job.getCreatedAt().isBefore(cutoff);
            if (expired) deleteQuietly(job.getTempDir());
            return expired;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        jobs.values().forEach(job -> deleteQuietly(job.getTempDir()));
    }

    private void deleteQuietly(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }
}
