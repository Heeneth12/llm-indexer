package com.llm.indexer.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class DepsBuilder {

    private static final Set<String> IGNORE = Set.of(
        "String", "Long", "Integer", "Boolean", "Double", "List", "Map",
        "Set", "Optional", "LocalDate", "LocalDateTime", "BigDecimal");

    public static String buildMarkdown(List<Path> files, Path root) {
        StringBuilder sb = new StringBuilder("# Dependencies\n\n");
        JavaParser parser = new JavaParser();
        for (Path file : files) {
            try {
                parser.parse(file.toFile()).getResult().ifPresent(cu ->
                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                        List<String> deps = fieldDeps(cls);
                        if (!deps.isEmpty())
                            sb.append("- ").append(cls.getNameAsString())
                              .append(" uses ").append(String.join(", ", deps)).append("\n");
                    }));
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    public static void buildGraph(List<Path> files, Path root, GraphStore graph) {
        JavaParser parser = new JavaParser();
        for (Path file : files) {
            String rel = root.relativize(file).toString();
            try {
                parser.parse(file.toFile()).getResult().ifPresent(cu ->
                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                        String className = cls.getNameAsString();
                        int classLine = cls.getBegin().map(p -> p.line).orElse(0);
                        graph.upsertNode(className, "Class", rel, classLine, "");

                        // USES edges from fields
                        for (String dep : fieldDeps(cls)) {
                            graph.upsertNode(dep, "Class", "", 0, "");
                            graph.addEdge(className, "USES", dep, rel);
                        }

                        // Endpoint nodes from Spring mappings
                        cls.getMethods().forEach(m -> {
                            String mName = className + "." + m.getNameAsString();
                            int line = m.getBegin().map(p -> p.line).orElse(0);
                            graph.upsertNode(mName, "Method", rel, line,
                                m.getType() + " " + m.getSignature());
                            graph.addEdge(className, "CONTAINS", mName, rel);

                            m.getAnnotations().forEach(an -> {
                                String a = an.getNameAsString();
                                if (a.endsWith("Mapping")) {
                                    String path = an.toString().replaceAll(".*\\(\"?([^\")]*)\"?\\).*", "$1");
                                    String ep = a.replace("Mapping", "").toUpperCase() + " " + path;
                                    graph.upsertNode(ep, "Endpoint", rel, line, "");
                                    graph.addEdge(mName, "EXPOSES", ep, rel);
                                }
                            });

                            // naive CALLS edges (unresolved scope -> best effort)
                            m.findAll(MethodCallExpr.class).forEach(call ->
                                call.getScope().ifPresent(scope ->
                                    graph.addEdge(mName, "CALLS",
                                        scope + "." + call.getNameAsString(), rel)));
                        });
                    }));
            } catch (Exception ignored) {}
        }
    }

    private static List<String> fieldDeps(ClassOrInterfaceDeclaration cls) {
        return cls.getFields().stream()
            .map(f -> f.getElementType().asString().replaceAll("<.*>", ""))
            .filter(t -> !t.isEmpty() && Character.isUpperCase(t.charAt(0)))
            .filter(t -> !IGNORE.contains(t))
            .distinct()
            .toList();
    }
}
