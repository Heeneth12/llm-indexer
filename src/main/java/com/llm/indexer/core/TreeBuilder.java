package com.llm.indexer.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

public class TreeBuilder {

    private static final Set<String> SKIP = Set.of(
        "target", "build", ".git", "node_modules", ".idea", "dist", ".llm-index");

    public static String build(Path root, String outDir) throws IOException {
        StringBuilder sb = new StringBuilder("# Directory tree\n```\n");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                String name = dir.getFileName().toString();
                if (SKIP.contains(name) || name.equals(outDir)) return FileVisitResult.SKIP_SUBTREE;
                int depth = root.relativize(dir).getNameCount();
                if (!dir.equals(root))
                    sb.append("  ".repeat(depth - 1)).append(name).append("/\n");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                String n = f.toString();
                if (n.endsWith(".java") || n.endsWith(".ts") || n.endsWith(".yml")
                        || n.endsWith(".properties")) {
                    int depth = root.relativize(f).getNameCount();
                    sb.append("  ".repeat(depth - 1)).append(f.getFileName()).append("\n");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sb.append("```\n").toString();
    }
}
