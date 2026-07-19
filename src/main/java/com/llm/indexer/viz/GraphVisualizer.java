package com.llm.indexer.viz;

import com.llm.indexer.core.GraphStore;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Renders graph.db as a single self-contained interactive HTML page (vis-network,
 * loaded from a CDN, no server needed to view it). Shared by the CLI `visualize`
 * command and the web app's /jobs/{id}/graph route -- same query, two front doors.
 */
public class GraphVisualizer {

    public static String render(Path dbPath, String filter, Integer hops) throws SQLException, IOException {
        try (GraphStore graph = new GraphStore(dbPath)) {
            Set<String> allowed = (filter == null || filter.isBlank())
                    ? null
                    : resolveAllowedNodes(graph, filter, hops);

            String nodesJson = buildNodesJson(graph, allowed);
            String edgesJson = buildEdgesJson(graph, allowed);

            return TEMPLATE.replace("__NODES__", nodesJson).replace("__EDGES__", edgesJson);
        }
    }

    /** Matching nodes, plus (if hops given) everything reachable within N hops via CALLS/USES, plus direct callers. */
    private static Set<String> resolveAllowedNodes(GraphStore graph, String filter, Integer hops) throws SQLException {
        Set<String> allowed = new HashSet<>();
        String like = "%" + filter + "%";

        try (ResultSet rs = graph.query("SELECT name FROM nodes WHERE name LIKE ?", like)) {
            while (rs.next()) allowed.add(rs.getString(1));
        }

        if (hops != null && hops > 0) {
            try (ResultSet rs = graph.query("""
                    WITH RECURSIVE chain(name, depth) AS (
                      SELECT name, 0 FROM nodes WHERE name LIKE ?
                      UNION
                      SELECT e.dst, c.depth + 1 FROM edges e
                      JOIN chain c ON e.src = c.name
                      WHERE e.predicate IN ('CALLS','USES') AND c.depth < """ + hops + """
                    ) SELECT DISTINCT name FROM chain""", like)) {
                while (rs.next()) allowed.add(rs.getString(1));
            }
            try (ResultSet rs = graph.query(
                    "SELECT DISTINCT src FROM edges WHERE dst LIKE ? AND predicate IN ('USES','CALLS')", like)) {
                while (rs.next()) allowed.add(rs.getString(1));
            }
        }

        return allowed;
    }

    private static String buildNodesJson(GraphStore graph, Set<String> allowed) throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try (ResultSet rs = graph.query("SELECT name, type, file_path, line_no FROM nodes")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (allowed != null && !allowed.contains(name)) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append(String.format(
                        "{\"id\":%s,\"label\":%s,\"group\":\"%s\",\"title\":%s}",
                        json(name), json(shorten(name)),
                        rs.getString(2),
                        json(rs.getString(3) + ":" + rs.getInt(4))));
            }
        }
        return sb.append("]").toString();
    }

    private static String buildEdgesJson(GraphStore graph, Set<String> allowed) throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try (ResultSet rs = graph.query("SELECT src, predicate, dst FROM edges")) {
            while (rs.next()) {
                String src = rs.getString(1);
                String dst = rs.getString(3);
                if (allowed != null && (!allowed.contains(src) || !allowed.contains(dst))) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append(String.format(
                        "{\"from\":%s,\"to\":%s,\"label\":\"%s\"}",
                        json(src), json(dst), rs.getString(2).toLowerCase()));
            }
        }
        return sb.append("]").toString();
    }

    private static String shorten(String name) {
        return name.length() > 30 ? name.substring(0, 27) + "..." : name;
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static final String TEMPLATE = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <title>llm-index graph</title>
            <script src="https://unpkg.com/vis-network@9.1.9/standalone/umd/vis-network.min.js"></script>
            <style>
              * { box-sizing: border-box; }
              body { margin: 0; font-family: 'Inter', Arial, Helvetica, sans-serif; }
              #toolbar {
                background: #000; color: #fff;
                padding: 0.7rem 1rem; display: flex; gap: 1rem; align-items: center;
              }
              #toolbar strong { font-size: 0.95rem; }
              #net { width: 100vw; height: calc(100vh - 84px); }
              #info { padding: 0.5rem 1rem; font-size: 0.8rem; color: #757575; background: #f7f7f7; border-top: 1px solid #ccc; }
              input[type=text] {
                padding: 0.4rem 0.7rem; width: 240px; font-size: 0.85rem;
                border: 1px solid #ccc; border-radius: 2px; font-family: inherit;
              }
              label { font-size: 0.82rem; color: #fff; display: flex; align-items: center; gap: 0.3rem; }
              .legend { display: flex; gap: 0.8rem; margin-left: auto; font-size: 0.78rem; }
              .legend span { display: inline-flex; align-items: center; gap: 0.3rem; }
              .dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
            </style>
            </head>
            <body>
            <div id="toolbar">
              <strong>llm-index graph</strong>
              <input type="text" id="search" placeholder="Search node... (focuses match)">
              <label><input type="checkbox" id="onlyClasses"> Classes only</label>
              <div class="legend">
                <span><i class="dot" style="background:#76b900"></i>Class</span>
                <span><i class="dot" style="background:#0046a4"></i>Method</span>
                <span><i class="dot" style="background:#df6500"></i>Endpoint</span>
              </div>
            </div>
            <div id="net"></div>
            <div id="info">Click a node to see file:line. Double-click to focus its neighborhood.</div>
            <script>
            const COLORS = {
              Class:    { background: "#76b900", border: "#5a8d00" },
              Method:   { background: "#0046a4", border: "#002e6d" },
              Endpoint: { background: "#df6500", border: "#8f4200" }
            };
            const allNodes = __NODES__;
            const allEdges = __EDGES__;
            allNodes.forEach(n => {
              n.color = COLORS[n.group] || { background: "#a7a7a7", border: "#5e5e5e" };
              n.shape = "dot";
              n.size = n.group === "Class" ? 18 : n.group === "Endpoint" ? 13 : 9;
              n.font = { size: 12, color: "#1a1a1a" };
            });
            allEdges.forEach(e => {
              e.arrows = "to";
              e.width = 0.7;
              e.color = { color: "#ccc" };
              e.font = { size: 9, color: "#999", strokeWidth: 0 };
            });
            const nodes = new vis.DataSet(allNodes);
            const edges = new vis.DataSet(allEdges);
            const net = new vis.Network(document.getElementById("net"),
              { nodes, edges },
              { physics: { barnesHut: { gravitationalConstant: -4000, springLength: 130 },
                           stabilization: { iterations: 200 } },
                interaction: { hover: true } });

            net.on("click", p => {
              if (p.nodes.length) {
                const n = nodes.get(p.nodes[0]);
                document.getElementById("info").textContent =
                  n.id + "  |  " + n.group + "  |  " + (n.title || "no location");
              }
            });

            net.on("doubleClick", p => {
              if (!p.nodes.length) return;
              const focus = p.nodes[0];
              const keep = new Set([focus]);
              allEdges.forEach(e => {
                if (e.from === focus) keep.add(e.to);
                if (e.to === focus) keep.add(e.from);
              });
              nodes.forEach(n => nodes.update({ id: n.id, hidden: !keep.has(n.id) }));
            });

            document.getElementById("search").addEventListener("change", ev => {
              const q = ev.target.value.toLowerCase();
              const hit = allNodes.find(n => n.id.toLowerCase().includes(q));
              if (hit) { net.focus(hit.id, { scale: 1.2, animation: true }); net.selectNodes([hit.id]); }
            });

            document.getElementById("onlyClasses").addEventListener("change", ev => {
              const only = ev.target.checked;
              nodes.forEach(n => nodes.update({ id: n.id, hidden: only && n.group !== "Class" }));
            });
            </script>
            </body>
            </html>
            """;
}
