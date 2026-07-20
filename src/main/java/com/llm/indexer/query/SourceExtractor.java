package com.llm.indexer.query;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Slices a match's exact source text off disk for --body queries, using
 * JavaParser's own begin/end line numbers for the matched node -- not a
 * heuristic like "next node's start line", which breaks on trailing
 * comments/annotations/blank lines. Costs one extra parse of one file per
 * match, so it's opt-in (QueryService.query's includeBody flag) rather than
 * always-on.
 */
public class SourceExtractor {

    public static String extractBody(Path root, String filePath, String matchName, int expectedLine) {
        if (filePath == null || filePath.isBlank()) return null;
        Path file = root.resolve(filePath);
        if (!Files.isRegularFile(file)) return null;

        String simpleName = matchName.contains(".")
                ? matchName.substring(matchName.lastIndexOf('.') + 1)
                : matchName;

        try {
            var parseResult = new JavaParser().parse(file);
            if (parseResult.getResult().isEmpty()) return null;
            var cu = parseResult.getResult().get();

            List<Node> candidates = new ArrayList<>();
            cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(simpleName)).forEach(candidates::add);
            cu.findAll(ConstructorDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(simpleName)).forEach(candidates::add);
            cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(simpleName)).forEach(candidates::add);
            if (candidates.isEmpty()) return null;

            Node node = candidates.stream()
                    .filter(n -> n.getBegin().map(p -> p.line).orElse(-1) == expectedLine)
                    .findFirst()
                    .orElse(candidates.get(0));

            if (node.getBegin().isEmpty() || node.getEnd().isEmpty()) return null;
            int startLine = node.getBegin().get().line;
            int endLine = node.getEnd().get().line;

            List<String> lines = Files.readAllLines(file);
            if (startLine < 1 || endLine > lines.size() || startLine > endLine) return null;
            return String.join("\n", lines.subList(startLine - 1, endLine));
        } catch (Exception e) {
            return null;
        }
    }
}
