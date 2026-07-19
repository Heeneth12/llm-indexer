package com.llm.indexer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Single entry point for turning a source tree into an LLM-friendly index
 * (tree/skeleton/deps markdown + a SQLite knowledge graph). Used identically
 * by the CLI and the web job runner.
 */
public class IndexService {

    public static IndexResult build(Path root, Path outDir, boolean full) throws IOException, java.sql.SQLException {
        root = root.toAbsolutePath().normalize();
        Files.createDirectories(outDir);
        String outDirName = outDir.getFileName().toString();

        List<Path> javaFiles;
        try (Stream<Path> s = Files.walk(root)) {
            javaFiles = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("build" + java.io.File.separator))
                    .filter(p -> !p.startsWith(outDir))
                    .sorted()
                    .toList();
        }

        Hashes hashes = new Hashes(outDir.resolve("state.json"));
        List<Path> changed = full ? javaFiles : hashes.changedFiles(javaFiles);

        String treeMd = TreeBuilder.build(root, outDirName);
        String skeletonMd = SkeletonBuilder.build(javaFiles, root);
        String depsMd = DepsBuilder.buildMarkdown(javaFiles, root);

        Files.writeString(outDir.resolve("01-tree.md"), treeMd);
        Files.writeString(outDir.resolve("02-skeleton.md"), skeletonMd);
        Files.writeString(outDir.resolve("03-dependencies.md"), depsMd);
        Files.writeString(outDir.resolve("INSTRUCTIONS.md"), instructions());

        Path dbPath = outDir.resolve("graph.db");
        try (GraphStore graph = new GraphStore(dbPath)) {
            graph.init();
            for (Path f : changed) graph.deleteFile(root.relativize(f).toString());
            DepsBuilder.buildGraph(changed, root, graph);
        }

        hashes.save(javaFiles);

        return new IndexResult(treeMd, skeletonMd, depsMd, dbPath, javaFiles.size(), changed.size());
    }

    /** Reconstructs an IndexResult from a previous build's output, without re-parsing anything. */
    public static IndexResult loadExisting(Path outDir) throws IOException {
        String treeMd = Files.readString(outDir.resolve("01-tree.md"));
        String skeletonMd = Files.readString(outDir.resolve("02-skeleton.md"));
        String depsMd = Files.readString(outDir.resolve("03-dependencies.md"));
        Path dbPath = outDir.resolve("graph.db");

        int filesTotal = 0;
        Path stateFile = outDir.resolve("state.json");
        if (Files.exists(stateFile)) {
            try (Stream<String> lines = Files.lines(stateFile)) {
                filesTotal = (int) lines.count();
            }
        }

        return new IndexResult(treeMd, skeletonMd, depsMd, dbPath, filesTotal, 0);
    }

    private static String instructions() {
        return """
            # For the LLM: how to use this index
            1. Read 01-tree.md to understand the project layout.
            2. Read 02-skeleton.md to find which class/method relates to the task.
            3. Read 03-dependencies.md to see what a class touches.
            4. Only then open the actual source files you identified.
            Do NOT read files unrelated to the task.
            """;
    }
}
