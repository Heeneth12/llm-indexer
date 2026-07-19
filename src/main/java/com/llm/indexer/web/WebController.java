package com.llm.indexer.web;

import com.llm.indexer.viz.GraphVisualizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Controller
@RequestMapping(WebController.BASE)
public class WebController {

    /** Every route this controller exposes lives under this prefix -- see also
     *  WebMvcConfig (static resources) and StartupIndexRunner's log messages. */
    public static final String BASE = "/llm-indexer";

    private final IndexJobService jobService;

    public WebController(IndexJobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/")
    public String index(Model model) {
        jobService.getJob(StartupIndexRunner.STARTUP_JOB_ID).ifPresent(job -> model.addAttribute("startupJob", job));
        return "landing";
    }

    @PostMapping("/index")
    public String createJob(@RequestParam String repoUrl, RedirectAttributes redirectAttributes) {
        String url = repoUrl == null ? "" : repoUrl.trim();
        if (url.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please paste a git repository URL.");
            return "redirect:" + BASE + "/";
        }
        try {
            GitCloner.validate(url);
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:" + BASE + "/";
        }
        String id = jobService.createJob(url);
        return "redirect:" + BASE + "/jobs/" + id;
    }

    @GetMapping("/docs")
    public String docs() {
        return "docs";
    }

    @GetMapping("/jobs/{id}")
    public String viewJob(@PathVariable String id, Model model) {
        IndexJob job = jobOrNotFound(id);
        model.addAttribute("job", job);
        return "job";
    }

    @PostMapping("/jobs/{id}/query")
    public String queryJob(@PathVariable String id, @RequestParam String term,
            @RequestParam(defaultValue = "3") int hops, Model model) {
        IndexJob job = jobOrNotFound(id);
        model.addAttribute("job", job);
        model.addAttribute("term", term);
        if (job.getStatus() == IndexJob.Status.DONE && term != null && !term.isBlank()) {
            try {
                model.addAttribute("queryResult", jobService.query(job, term.trim(), Math.max(1, Math.min(hops, 8))));
            } catch (Exception e) {
                model.addAttribute("queryError", e.getMessage());
            }
        }
        return "job";
    }

    @GetMapping("/jobs/{id}/graph")
    public ResponseEntity<String> viewGraph(@PathVariable String id, @RequestParam(required = false) String filter,
            @RequestParam(required = false) Integer hops) {
        IndexJob job = jobOrNotFound(id);
        if (job.getStatus() != IndexJob.Status.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not indexed yet");
        }
        try {
            String html = GraphVisualizer.render(job.graphDb(), filter, hops);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not render graph");
        }
    }

    @GetMapping("/jobs/{id}/file")
    public String viewFile(@PathVariable String id, @RequestParam String path,
            @RequestParam(required = false) Integer line, Model model) {
        IndexJob job = jobOrNotFound(id);

        Path file;
        try {
            file = jobService.resolveSourceFile(job, path);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file");
        }

        try {
            model.addAttribute("job", job);
            model.addAttribute("path", path);
            model.addAttribute("lines", Files.readString(file).lines().toList());
            model.addAttribute("highlightLine", line == null ? -1 : line);
            return "file";
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file");
        }
    }

    private IndexJob jobOrNotFound(String id) {
        return jobService.getJob(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job: " + id));
    }
}
