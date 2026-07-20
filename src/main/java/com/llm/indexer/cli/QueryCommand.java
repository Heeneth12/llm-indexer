package com.llm.indexer.cli;

import com.llm.indexer.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "query", description = "Query the knowledge graph and emit context.md for the LLM.")
public class QueryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Search term, e.g. a class name or keyword")
    String term;

    @Option(names = {"-d", "--dir"}, defaultValue = ".llm-index", description = "Index directory")
    String dir;

    @Option(names = {"--hops"}, defaultValue = "3", description = "Traversal depth")
    int hops;

    @Option(names = {"--body"}, description = "Include each match's exact source text (skips the follow-up file Read)")
    boolean body;

    @Override
    public Integer call() throws Exception {
        Path db = Path.of(dir, "graph.db");
        if (!Files.exists(db)) {
            System.err.println("No index found. Run: indexer build");
            return 1;
        }

        var result = QueryService.query(db, term, hops, body);

        Path outFile = Path.of(dir, "context.md");
        Files.writeString(outFile, result.markdown());

        System.out.println(result.markdown());
        System.out.println("Written to " + outFile);
        return 0;
    }
}
