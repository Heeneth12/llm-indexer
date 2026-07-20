package com.llm.indexer.query;

import com.llm.indexer.core.GraphStore;
import com.llm.indexer.core.Stemmer;
import com.llm.indexer.core.Tokenizer;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Traverses the knowledge graph built by IndexService and returns both a
 * structured result (for the web UI) and a rendered context.md (for the CLI
 * and for download from the web UI) — same traversal, two renderings.
 *
 * Matching is tiered, precision first:
 *   1. Exact/substring match against the identifier (the original behavior) --
 *      zero false positives when the caller already knows the name.
 *   2. Only if that finds nothing: the FTS5 index (GraphStore.nodes_fts), with
 *      the term tokenized the same way identifiers are (camelCase/snake_case
 *      split), expanded through a small synonym table and Porter-stemmed
 *      (Stemmer), ranked by bm25. This is what turns "email notification"
 *      into a hit on GmailService/NotificationService even though "email"
 *      isn't a substring of either, and what makes "notifying"/"notifier"
 *      match an indexed "notification" without a hand-curated synonym entry.
 *   3. Only if both find nothing: edit-distance suggestions, so a miss reads
 *      as "did you mean X" instead of a silent empty result.
 * Tier 2 alone (skipping tier 1) tends to surface unrelated nodes that merely
 * share a generic token like "service" -- tier 1 first keeps a query for an
 * exact class name precise, and tier 2 only kicks in exactly where it's
 * needed: when tier 1 comes up empty.
 */
public class QueryService {

    private static final int MAX_MATCHES = 10;
    private static final int MAX_SUGGESTIONS = 5;
    private static final int MAX_USED_BY = 15;
    private static final int MAX_CALL_CHAIN = 25;

    public static QueryResult query(Path dbPath, String term, int hops) throws SQLException {
        return query(dbPath, term, hops, false);
    }

    /** includeBody: also slice each match's exact source text off disk (SourceExtractor).
     *  Costs one JavaParser pass per match, so it's opt-in -- the CLI's --body flag. */
    public static QueryResult query(Path dbPath, String term, int hops, boolean includeBody) throws SQLException {
        List<QueryResult.Match> matches = new ArrayList<>();
        List<String> usedBy = new ArrayList<>();
        List<QueryResult.CallChainEntry> callChain = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        try (GraphStore graph = new GraphStore(dbPath)) {
            matches.addAll(exactMatches(graph, term));
            if (matches.isEmpty()) matches.addAll(fuzzyTokenMatches(graph, term));

            if (includeBody && !matches.isEmpty()) {
                Path root = dbPath.toAbsolutePath().getParent().getParent();
                matches = matches.stream()
                        .map(m -> new QueryResult.Match(m.name(), m.type(), m.filePath(), m.line(), m.signature(),
                                SourceExtractor.extractBody(root, m.filePath(), m.name(), m.line())))
                        .toList();
            }

            if (matches.isEmpty()) {
                suggestions.addAll(fuzzySuggestions(graph, term));
            } else {
                LinkedHashSet<String> matchedNames = matches.stream()
                        .map(QueryResult.Match::name)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                for (String name : matchedNames) {
                    try (ResultSet rs = graph.query("""
                            SELECT DISTINCT src FROM edges
                            WHERE dst = ? AND predicate IN ('USES','CALLS') LIMIT ?""",
                            name, String.valueOf(MAX_USED_BY))) {
                        while (rs.next()) {
                            String s = rs.getString(1);
                            if (!usedBy.contains(s)) usedBy.add(s);
                        }
                    }
                }

                // depth -> (name -> inbound edge count), so results stay ordered by hop
                // distance first and, within a hop, by how "load-bearing" a node is
                // (how many things call/use it) rather than raw traversal order.
                Map<Integer, Map<String, Integer>> byDepth = new TreeMap<>();
                for (String name : matchedNames) {
                    try (ResultSet rs = graph.query("""
                            WITH RECURSIVE chain(name, depth) AS (
                              SELECT ?, 0
                              UNION
                              SELECT e.dst, c.depth + 1 FROM edges e
                              JOIN chain c ON e.src = c.name
                              WHERE e.predicate IN ('CALLS','USES') AND c.depth < """ + hops + """
                            )
                            SELECT DISTINCT c.name, c.depth,
                              (SELECT COUNT(*) FROM edges e2 WHERE e2.dst = c.name) AS inbound
                            FROM chain c
                            WHERE c.depth > 0""",
                            name)) {
                        while (rs.next()) {
                            byDepth.computeIfAbsent(rs.getInt(2), d -> new TreeMap<>())
                                    .putIfAbsent(rs.getString(1), rs.getInt(3));
                        }
                    }
                }
                for (var depthEntry : byDepth.entrySet()) {
                    depthEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                            .limit(MAX_CALL_CHAIN)
                            .forEach(e -> callChain.add(new QueryResult.CallChainEntry(e.getKey(), depthEntry.getKey())));
                }
            }
        }

        return new QueryResult(term, matches, usedBy, callChain, suggestions,
                renderMarkdown(term, matches, usedBy, callChain, suggestions));
    }

    /** Tier 1: literal substring match against the identifier, same as before this
     *  file grew a full-text layer. Precise, cheap, and the common case. */
    private static List<QueryResult.Match> exactMatches(GraphStore graph, String term) throws SQLException {
        List<QueryResult.Match> out = new ArrayList<>();
        try (ResultSet rs = graph.query("""
                SELECT name, type, file_path, line_no, signature FROM nodes
                WHERE name LIKE ? AND file_path != '' LIMIT ?""",
                "%" + term + "%", String.valueOf(MAX_MATCHES))) {
            while (rs.next())
                out.add(new QueryResult.Match(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)));
        }
        return out;
    }

    /** Tier 2: only reached when tier 1 finds nothing. Tokenizes the term the same
     *  way identifiers are indexed, expands each token through the synonym table,
     *  and ranks by bm25 -- concept-level matching for when no identifier literally
     *  contains the search term. */
    private static List<QueryResult.Match> fuzzyTokenMatches(GraphStore graph, String term) throws SQLException {
        List<QueryResult.Match> out = new ArrayList<>();
        String matchExpr = buildMatchExpression(term);
        try (ResultSet rs = graph.query("""
                SELECT n.name, n.type, n.file_path, n.line_no, n.signature
                FROM nodes_fts f
                JOIN nodes n ON n.name = f.name
                WHERE nodes_fts MATCH ? AND n.file_path != ''
                ORDER BY bm25(nodes_fts)
                LIMIT ?""",
                matchExpr, String.valueOf(MAX_MATCHES))) {
            while (rs.next())
                out.add(new QueryResult.Match(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)));
        }
        return out;
    }

    /** Tokenizes the term the same way identifiers are indexed, expands each token
     *  through the synonym table, and builds an FTS5 prefix-OR match expression. */
    private static String buildMatchExpression(String term) {
        List<String> queryTokens = Tokenizer.tokens(term);
        if (queryTokens.isEmpty()) queryTokens = List.of(term.toLowerCase());

        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String t : queryTokens) {
            expanded.addAll(Synonyms.expand(t));
            expanded.add(Stemmer.stem(t));
        }

        return expanded.stream()
                .map(t -> "\"" + t.replace("\"", "") + "\"*")
                .collect(Collectors.joining(" OR "));
    }

    /** Zero FTS matches -> nearest node names by edit distance, so a miss reads as
     *  "did you mean X" instead of "nothing here". */
    private static List<String> fuzzySuggestions(GraphStore graph, String term) throws SQLException {
        String needle = term.toLowerCase();
        List<Map.Entry<String, Integer>> scored = new ArrayList<>();
        try (ResultSet rs = graph.query("SELECT DISTINCT name FROM nodes WHERE file_path != ''")) {
            while (rs.next()) {
                String name = rs.getString(1);
                scored.add(Map.entry(name, levenshtein(needle, name.toLowerCase())));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Map.Entry::getValue))
                .limit(MAX_SUGGESTIONS)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private static String renderMarkdown(String term, List<QueryResult.Match> matches, List<String> usedBy,
                                          List<QueryResult.CallChainEntry> callChain, List<String> suggestions) {
        StringBuilder ctx = new StringBuilder("# Context for: ").append(term).append("\n\n");

        if (matches.isEmpty() && !suggestions.isEmpty()) {
            ctx.append("## No direct matches\nDid you mean:\n");
            for (String s : suggestions) ctx.append("- ").append(s).append("\n");
            ctx.append("\n");
        }

        ctx.append("## Matching code\n");
        for (var m : matches) {
            ctx.append("- ").append(m.name()).append(" (").append(m.filePath())
               .append(":").append(m.line()).append(")");
            if (m.signature() != null && !m.signature().isBlank())
                ctx.append("  `").append(m.signature()).append("`");
            ctx.append("\n");
            if (m.body() != null && !m.body().isBlank())
                ctx.append("  ```java\n").append(m.body().indent(2)).append("  ```\n");
        }

        ctx.append("\n## Used by (impact if changed)\n");
        for (String s : usedBy) ctx.append("- ").append(s).append("\n");

        ctx.append("\n## Calls outward from here\n");
        for (var c : callChain)
            ctx.append("- ").append("  ".repeat(c.depth())).append(c.name()).append("\n");

        ctx.append("\n## Instructions\nRead only the files listed above. Start at the file:line refs.\n");
        return ctx.toString();
    }
}
