package com.llm.indexer.cli;

import com.llm.indexer.core.IndexResult;
import com.llm.indexer.core.IndexService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "build", description = "Index the project: tree, skeletons, dependencies, graph.")
public class BuildCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Project root (default: current dir)")
    Path root;

    @Option(names = {"-o", "--out"}, defaultValue = ".llm-index", description = "Output directory")
    String outDir;

    @Option(names = {"--full"}, description = "Force full re-index (ignore hash cache)")
    boolean full;

    @Override
    public Integer call() throws Exception {
        Path root = this.root.toAbsolutePath().normalize();
        Path out = root.resolve(outDir);

        IndexResult result = IndexService.build(root, out, full);

        System.out.println("Files: " + result.filesTotal() + " total, " + result.filesChanged() + " changed");
        System.out.println("Index written to " + out);
        return 0;
    }
}
