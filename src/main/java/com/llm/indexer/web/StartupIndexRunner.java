package com.llm.indexer.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config-driven auto-indexing (llm-index.startup.*): fires once the app is up,
 * so no CLI command and no pasting a URL into the form is needed. Reachable
 * afterwards at /jobs/startup, and linked from the landing page.
 */
@Component
public class StartupIndexRunner {

    public static final String STARTUP_JOB_ID = "startup";

    private static final Logger log = LoggerFactory.getLogger(StartupIndexRunner.class);

    private final StartupIndexProperties props;
    private final IndexJobService jobService;

    public StartupIndexRunner(StartupIndexProperties props, IndexJobService jobService) {
        this.props = props;
        this.jobService = jobService;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!props.isEnabled()) return;

        boolean hasPath = props.getPath() != null && !props.getPath().isBlank();
        boolean hasRepoUrl = props.getRepoUrl() != null && !props.getRepoUrl().isBlank();

        if (!hasPath && !hasRepoUrl) {
            log.warn("llm-index.startup.enabled=true but neither 'path' nor 'repo-url' is set; skipping startup index");
            return;
        }

        try {
            Path root;
            String label;

            if (hasPath) {
                if (hasRepoUrl) log.info("Both llm-index.startup.path and repo-url are set; using path");
                root = Path.of(props.getPath()).toAbsolutePath().normalize();
                if (!Files.isDirectory(root)) {
                    log.warn("llm-index.startup.path '{}' does not exist or is not a directory; skipping startup index", root);
                    return;
                }
                label = root.toString();
            } else {
                Path workDir = (props.getWorkDir() != null && !props.getWorkDir().isBlank())
                        ? Path.of(props.getWorkDir())
                        : Path.of(System.getProperty("java.io.tmpdir"), "llm-index-startup");
                GitCloner.cloneOrUpdate(props.getRepoUrl(), workDir);
                root = workDir;
                label = props.getRepoUrl();
            }

            log.info("Starting config-driven index of {} (full={}, reindexOnRestart={}, graphVisualization={})",
                    label, props.isFull(), props.isReindexOnRestart(), props.isGraphVisualizationEnabled());
            jobService.registerAndRunPermanentJob(STARTUP_JOB_ID, label, root, props.isFull(),
                    props.isReindexOnRestart(), props.isGraphVisualizationEnabled());
        } catch (Exception e) {
            log.error("Startup indexing failed", e);
        }
    }
}
