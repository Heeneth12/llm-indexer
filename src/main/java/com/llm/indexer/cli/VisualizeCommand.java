package com.llm.indexer.cli;

import com.llm.indexer.viz.GraphVisualizer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "visualize", description = "Generate an interactive HTML visualization of graph.db")
public class VisualizeCommand implements Callable<Integer> {

    @Option(names = {"-d", "--dir"}, defaultValue = ".llm-index", description = "Index directory")
    String dir;

    @Option(names = {"--filter"}, description = "Only include nodes matching this name (LIKE)")
    String filter;

    @Option(names = {"--hops"}, description = "With --filter, also pull in nodes within N hops (CALLS/USES)")
    Integer hops;

    @Option(names = {"--no-open"}, description = "Don't auto-open the browser")
    boolean noOpen;

    @Override
    public Integer call() throws Exception {
        Path db = Path.of(dir, "graph.db");
        if (!Files.exists(db)) {
            System.err.println("No index found. Run: indexer build");
            return 1;
        }

        String html = GraphVisualizer.render(db, filter, hops);

        Path out = Path.of(dir, "graph.html");
        Files.writeString(out, html);
        System.out.println("Written " + out);

        if (!noOpen) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(out.toUri());
                }
            } catch (Exception e) {
                System.err.println("Could not auto-open a browser (" + e.getMessage() + "); open " + out + " manually.");
            }
        }
        return 0;
    }
}
