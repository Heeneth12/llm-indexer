package com.llm.indexer.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Controller
public class WebController {

    private final IndexJobService jobService;

    public WebController(IndexJobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/index")
    public String createJob(@RequestParam String repoUrl, RedirectAttributes redirectAttributes) {
        String url = repoUrl == null ? "" : repoUrl.trim();
        if (url.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please paste a git repository URL.");
            return "redirect:/";
        }
        try {
            GitCloner.validate(url);
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
        String id = jobService.createJob(url);
        return "redirect:/jobs/" + id;
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
        return jobService.getJob(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job: " + id));
    }
}
