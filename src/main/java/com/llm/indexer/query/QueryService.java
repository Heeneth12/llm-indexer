package com.llm.indexer.query;

import com.llm.indexer.core.GraphStore;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Traverses the knowledge graph built by IndexService and returns both a
 * structured result (for the web UI) and a rendered context.md (for the CLI
 * and for download from the web UI) — same traversal, two renderings.
 */
public class QueryService {

    public static QueryResult query(Path dbPath, String term, int hops) throws SQLException {
        List<QueryResult.Match> matches = new ArrayList<>();
        List<String> usedBy = new ArrayList<>();
        List<QueryResult.CallChainEntry> callChain = new ArrayList<>();

        try (GraphStore graph = new GraphStore(dbPath)) {
            try (ResultSet rs = graph.query("""
                    SELECT name, type, file_path, line_no FROM nodes
                    WHERE name LIKE ? AND file_path != '' LIMIT 10""",
                    "%" + term + "%")) {
                while (rs.next())
                    matches.add(new QueryResult.Match(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
            }

            try (ResultSet rs = graph.query("""
                    SELECT DISTINCT src FROM edges
                    WHERE dst LIKE ? AND predicate IN ('USES','CALLS') LIMIT 15""",
                    "%" + term + "%")) {
                while (rs.next()) usedBy.add(rs.getString(1));
            }

            try (ResultSet rs = graph.query("""
                    WITH RECURSIVE chain(name, depth) AS (
                      SELECT name, 0 FROM nodes WHERE name LIKE ?
                      UNION
                      SELECT e.dst, c.depth + 1 FROM edges e
                      JOIN chain c ON e.src = c.name
                      WHERE e.predicate IN ('CALLS','USES') AND c.depth < """ + hops + """
                    ) SELECT DISTINCT name, depth FROM chain
                      WHERE depth > 0 ORDER BY depth LIMIT 25""",
                    "%" + term + "%")) {
                while (rs.next())
                    callChain.add(new QueryResult.CallChainEntry(rs.getString(1), rs.getInt(2)));
            }
        }

        return new QueryResult(term, matches, usedBy, callChain, renderMarkdown(term, matches, usedBy, callChain));
    }

    private static String renderMarkdown(String term, List<QueryResult.Match> matches,
                                          List<String> usedBy, List<QueryResult.CallChainEntry> callChain) {
        StringBuilder ctx = new StringBuilder("# Context for: ").append(term).append("\n\n");

        ctx.append("## Matching code\n");
        for (var m : matches)
            ctx.append("- ").append(m.name()).append(" (").append(m.filePath())
               .append(":").append(m.line()).append(")\n");

        ctx.append("\n## Used by (impact if changed)\n");
        for (String s : usedBy) ctx.append("- ").append(s).append("\n");

        ctx.append("\n## Calls outward from here\n");
        for (var c : callChain)
            ctx.append("- ").append("  ".repeat(c.depth())).append(c.name()).append("\n");

        ctx.append("\n## Instructions\nRead only the files listed above. Start at the file:line refs.\n");
        return ctx.toString();
    }
}
