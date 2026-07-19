package com.llm.indexer.core;

import java.nio.file.Path;
import java.sql.*;

public class GraphStore implements AutoCloseable {

    private final Connection conn;

    public GraphStore(Path dbFile) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        conn.setAutoCommit(false);
    }

    public void init() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS nodes(
                  id INTEGER PRIMARY KEY,
                  name TEXT UNIQUE, type TEXT,
                  file_path TEXT, line_no INTEGER, signature TEXT)""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS edges(
                  src TEXT, predicate TEXT, dst TEXT, file_path TEXT,
                  UNIQUE(src, predicate, dst))""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_e_src ON edges(src)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_e_dst ON edges(dst)");
        }
        conn.commit();
    }

    public void upsertNode(String name, String type, String file, int line, String sig) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO nodes(name, type, file_path, line_no, signature)
                VALUES(?,?,?,?,?)
                ON CONFLICT(name) DO UPDATE SET
                  file_path = CASE WHEN excluded.file_path != '' THEN excluded.file_path ELSE nodes.file_path END,
                  line_no   = CASE WHEN excluded.line_no != 0 THEN excluded.line_no ELSE nodes.line_no END""")) {
            ps.setString(1, name); ps.setString(2, type);
            ps.setString(3, file); ps.setInt(4, line); ps.setString(5, sig);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void addEdge(String src, String pred, String dst, String file) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO edges(src, predicate, dst, file_path) VALUES(?,?,?,?)")) {
            ps.setString(1, src); ps.setString(2, pred);
            ps.setString(3, dst); ps.setString(4, file);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void deleteFile(String file) {
        try (PreparedStatement p1 = conn.prepareStatement("DELETE FROM edges WHERE file_path = ?");
             PreparedStatement p2 = conn.prepareStatement("DELETE FROM nodes WHERE file_path = ?")) {
            p1.setString(1, file); p1.executeUpdate();
            p2.setString(1, file); p2.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public ResultSet query(String sql, String... params) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
        return ps.executeQuery();
    }

    @Override
    public void close() throws SQLException {
        conn.commit();
        conn.close();
    }
}
