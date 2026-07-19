package com.llm.indexer.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "indexer",
    version = "indexer 0.1.0",
    mixinStandardHelpOptions = true,
    description = "Generates an LLM-friendly index of your codebase (tree, skeletons, deps, knowledge graph).",
    subcommands = { BuildCommand.class, QueryCommand.class }
)
public class CliRoot implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
