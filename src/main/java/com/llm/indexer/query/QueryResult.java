package com.llm.indexer.query;

import java.util.List;

public record QueryResult(
    String term,
    List<Match> matches,
    List<String> usedBy,
    List<CallChainEntry> callChain,
    String markdown
) {
    public record Match(String name, String type, String filePath, int line) {}

    public record CallChainEntry(String name, int depth) {}
}
