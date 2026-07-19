package com.llm.indexer.web;

import com.llm.indexer.core.IndexResult;
import com.llm.indexer.core.IndexService;
import com.llm.indexer.query.QueryResult;
import com.llm.indexer.query.QueryService;
import com.llm.indexer.viz.GraphVisualizer;
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

    /**
     * Registers a job backed by config (llm-index.startup.*) rather than a user request.
     * Runs on the same executor as regular jobs, but is marked permanent immediately so
     * the TTL sweep and shutdown cleanup never touch it or delete its directory — root
     * may be the caller's own real repo, not a disposable clone.
     */
    public void registerAndRunPermanentJob(String id, String label, Path root, boolean full,
                                            boolean reindexOnRestart, boolean generateGraphHtml) {
        IndexJob job = new IndexJob(id, label, root);
        job.setPermanent(true);
        jobs.put(id, job);
        executor.submit(() -> runPermanentJob(job, full, reindexOnRestart, generateGraphHtml));
    }

    private void runPermanentJob(IndexJob job, boolean full, boolean reindexOnRestart, boolean generateGraphHtml) {
        job.setStatus(IndexJob.Status.RUNNING);
        try {
            Path outDir = job.getTempDir().resolve(".llm-index");
            boolean alreadyIndexed = Files.exists(outDir.resolve("graph.db"))
                    && Files.exists(outDir.resolve("01-tree.md"));

            IndexResult result = (alreadyIndexed && !reindexOnRestart)
                    ? IndexService.loadExisting(outDir)
                    : IndexService.build(job.getTempDir(), outDir, full);

            if (generateGraphHtml) {
                Path graphHtml = outDir.resolve("graph.html");
                Files.writeString(graphHtml, GraphVisualizer.render(result.dbPath(), null, null));
                log.info("Wrote graph visualization to {}", graphHtml);
            }

            job.setResult(result);
            job.setStatus(IndexJob.Status.DONE);
        } catch (Exception e) {
            log.error("Startup job {} failed: {}", job.getId(), e.getMessage(), e);
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
            if (job.isPermanent()) return false;
            boolean expired = job.getCreatedAt().isBefore(cutoff);
            if (expired) deleteQuietly(job.getTempDir());
            return expired;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        jobs.values().stream().filter(job -> !job.isPermanent()).forEach(job -> deleteQuietly(job.getTempDir()));
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
