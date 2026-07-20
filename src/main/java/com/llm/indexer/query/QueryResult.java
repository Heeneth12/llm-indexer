package com.llm.indexer.query;

import java.util.List;

public record QueryResult(
    String term,
    List<Match> matches,
    List<String> usedBy,
    List<CallChainEntry> callChain,
    List<String> suggestions,
    String markdown
) {
    public record Match(String name, String type, String filePath, int line, String signature) {}

    public record CallChainEntry(String name, int depth) {}
}
