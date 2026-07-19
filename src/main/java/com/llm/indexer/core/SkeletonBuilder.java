package com.llm.indexer.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class SkeletonBuilder {

    public static String build(List<Path> files, Path root) {
        StringBuilder sb = new StringBuilder("# Code skeletons\n\n");
        JavaParser parser = new JavaParser();
        for (Path file : files) {
            try {
                parser.parse(file.toFile()).getResult().ifPresent(cu -> {
                    var classes = cu.findAll(ClassOrInterfaceDeclaration.class);
                    if (classes.isEmpty()) return;
                    sb.append("## ").append(root.relativize(file)).append("\n```java\n");
                    for (var cls : classes) {
                        cls.getAnnotations().forEach(an ->
                            sb.append("@").append(an.getNameAsString()).append("\n"));
                        sb.append(cls.isInterface() ? "interface " : "class ")
                          .append(cls.getNameAsString()).append(" {\n");
                        cls.getFields().forEach(f -> sb.append("    ")
                            .append(f.getElementType()).append(" ")
                            .append(f.getVariable(0).getName()).append(";\n"));
                        cls.getMethods().forEach(m -> {
                            int line = m.getBegin().map(p -> p.line).orElse(0);
                            sb.append("    ").append(m.getType()).append(" ")
                              .append(m.getName()).append("(")
                              .append(m.getParameters().stream()
                                      .map(p -> p.getType() + " " + p.getName())
                                      .collect(Collectors.joining(", ")))
                              .append(");  // L").append(line).append("\n");
                        });
                        sb.append("}\n");
                    }
                    sb.append("```\n\n");
                });
            } catch (Exception e) {
                sb.append("<!-- parse failed: ").append(root.relativize(file)).append(" -->\n");
            }
        }
        return sb.toString();
    }
}
