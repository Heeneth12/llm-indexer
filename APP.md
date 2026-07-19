Here's the complete, installable package. I'll build it as a proper CLI with picocli (subcommands, help, flags), packaged as a fat jar with a one-line installer — the standard way Java CLI tools ship.
Project structure
llm-index/
├── pom.xml
├── install.sh
├── README.md
└── src/main/java/dev/gamechanger/llmindex/
    ├── Main.java            → CLI entry (picocli)
    ├── BuildCommand.java    → llm-index build
    ├── QueryCommand.java    → llm-index query "..."
    ├── TreeBuilder.java
    ├── SkeletonBuilder.java
    ├── DepsBuilder.java
    ├── GraphStore.java      → SQLite triples
    └── Hashes.java          → incremental state

    <?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.gamechanger</groupId>
  <artifactId>llm-index</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>4.7.6</version>
    </dependency>
    <dependency>
      <groupId>com.github.javaparser</groupId>
      <artifactId>javaparser-core</artifactId>
      <version>3.26.2</version>
    </dependency>
    <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.46.1.0</version>
    </dependency>
  </dependencies>

  <build>
    <finalName>llm-index</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.6.0</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>dev.gamechanger.llmindex.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>

package dev.gamechanger.llmindex;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "llm-index",
    version = "llm-index 0.1.0",
    mixinStandardHelpOptions = true,
    description = "Generates an LLM-friendly index of your codebase (tree, skeletons, deps, knowledge graph).",
    subcommands = { BuildCommand.class, QueryCommand.class }
)
public class Main implements Runnable {

    @Override
    public void run() {
        // no subcommand given -> show help
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }
}

package dev.gamechanger.llmindex;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

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
        root = root.toAbsolutePath().normalize();
        Path out = root.resolve(outDir);
        Files.createDirectories(out);

        List<Path> javaFiles;
        try (Stream<Path> s = Files.walk(root)) {
            javaFiles = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("build" + java.io.File.separator))
                    .filter(p -> !p.toString().contains(outDir))
                    .sorted()
                    .toList();
        }

        Hashes hashes = new Hashes(out.resolve("state.json"));
        List<Path> changed = full ? javaFiles : hashes.changedFiles(javaFiles);
        System.out.println("Files: " + javaFiles.size() + " total, " + changed.size() + " changed");

        // Markdown views (regenerated fully - they're cheap)
        Files.writeString(out.resolve("01-tree.md"), TreeBuilder.build(root, outDir));
        Files.writeString(out.resolve("02-skeleton.md"), SkeletonBuilder.build(javaFiles, root));
        Files.writeString(out.resolve("03-dependencies.md"), DepsBuilder.buildMarkdown(javaFiles, root));
        Files.writeString(out.resolve("INSTRUCTIONS.md"), instructions());

        // Knowledge graph (incremental: only changed files re-inserted)
        try (GraphStore graph = new GraphStore(out.resolve("graph.db"))) {
            graph.init();
            for (Path f : changed) graph.deleteFile(root.relativize(f).toString());
            DepsBuilder.buildGraph(changed, root, graph);
        }

        hashes.save(javaFiles);
        System.out.println("Index written to " + out);
        return 0;
    }

    private String instructions() {
        return """
            # For the LLM: how to use this index
            1. Read 01-tree.md to understand the project layout.
            2. Read 02-skeleton.md to find which class/method relates to the task.
            3. Read 03-dependencies.md to see what a class touches.
            4. Only then open the actual source files you identified.
            Do NOT read files unrelated to the task.
            """;
    }
}

package dev.gamechanger.llmindex;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

public class TreeBuilder {

    private static final Set<String> SKIP = Set.of(
        "target", "build", ".git", "node_modules", ".idea", "dist", ".llm-index");

    public static String build(Path root, String outDir) throws IOException {
        StringBuilder sb = new StringBuilder("# Directory tree\n```\n");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                String name = dir.getFileName().toString();
                if (SKIP.contains(name) || name.equals(outDir)) return FileVisitResult.SKIP_SUBTREE;
                int depth = root.relativize(dir).getNameCount();
                if (!dir.equals(root))
                    sb.append("  ".repeat(depth - 1)).append(name).append("/\n");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                String n = f.toString();
                if (n.endsWith(".java") || n.endsWith(".ts") || n.endsWith(".yml")
                        || n.endsWith(".properties")) {
                    int depth = root.relativize(f).getNameCount();
                    sb.append("  ".repeat(depth - 1)).append(f.getFileName()).append("\n");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sb.append("```\n").toString();
    }
}


package dev.gamechanger.llmindex;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class SkeletonBuilder {

    public static String build(List<Path> files, Path root) {
        StringBuilder sb = new StringBuilder("# Code skeletons\n\n");
        JavaParser parser = new JavaParser();
        for (Path file : files) {
            try {
                parser.parse(file.toFile()).getResult().ifPresent(cu -> {
                    var classes = cu.findAll(ClassOrInterfaceDeclaration.class);
                    if (classes.isEmpty()) return;
                    sb.append("## ").append(root.relativize(file)).append("\n```java\n");
                    for (var cls : classes) {
                        cls.getAnnotations().forEach(an ->
                            sb.append("@").append(an.getNameAsString()).append("\n"));
                        sb.append(cls.isInterface() ? "interface " : "class ")
                          .append(cls.getNameAsString()).append(" {\n");
                        cls.getFields().forEach(f -> sb.append("    ")
                            .append(f.getElementType()).append(" ")
                            .append(f.getVariable(0).getName()).append(";\n"));
                        cls.getMethods().forEach(m -> {
                            int line = m.getBegin().map(p -> p.line).orElse(0);
                            sb.append("    ").append(m.getType()).append(" ")
                              .append(m.getName()).append("(")
                              .append(m.getParameters().stream()
                                      .map(p -> p.getType() + " " + p.getName())
                                      .collect(Collectors.joining(", ")))
                              .append(");  // L").append(line).append("\n");
                        });
                        sb.append("}\n");
                    }
                    sb.append("```\n\n");
                });
            } catch (Exception e) {
                sb.append("<!-- parse failed: ").append(root.relativize(file)).append(" -->\n");
            }
        }
        return sb.toString();
    }
}


package dev.gamechanger.llmindex;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class DepsBuilder {

    private static final Set<String> IGNORE = Set.of(
        "String", "Long", "Integer", "Boolean", "Double", "List", "Map",
        "Set", "Optional", "LocalDate", "LocalDateTime", "BigDecimal");

    public static String buildMarkdown(List<Path> files, Path root) {
        StringBuilder sb = new StringBuilder("# Dependencies\n\n");
        JavaParser parser = new JavaParser();
        for (Path file : files) {
            try {
                parser.parse(file.toFile()).getResult().ifPresent(cu ->
                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                        List<String> deps = fieldDeps(cls);
                        if (!deps.isEmpty())
                            sb.append("- ").append(cls.getNameAsString())
                              .append(" uses ").append(String.join(", ", deps)).append("\n");
                    }));
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    public static void buildGraph(List<Path> files, Path root, GraphStore graph) {
        JavaParser parser = new JavaParser();
        for (Path file : files) {
            String rel = root.relativize(file).toString();
            try {
                parser.parse(file.toFile()).getResult().ifPresent(cu ->
                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                        String className = cls.getNameAsString();
                        int classLine = cls.getBegin().map(p -> p.line).orElse(0);
                        graph.upsertNode(className, "Class", rel, classLine, "");

                        // USES edges from fields
                        for (String dep : fieldDeps(cls)) {
                            graph.upsertNode(dep, "Class", "", 0, "");
                            graph.addEdge(className, "USES", dep, rel);
                        }

                        // Endpoint nodes from Spring mappings
                        cls.getMethods().forEach(m -> {
                            String mName = className + "." + m.getNameAsString();
                            int line = m.getBegin().map(p -> p.line).orElse(0);
                            graph.upsertNode(mName, "Method", rel, line,
                                m.getType() + " " + m.getSignature());
                            graph.addEdge(className, "CONTAINS", mName, rel);

                            m.getAnnotations().forEach(an -> {
                                String a = an.getNameAsString();
                                if (a.endsWith("Mapping")) {
                                    String path = an.toString().replaceAll(".*\\(\"?([^\")]*)\"?\\).*", "$1");
                                    String ep = a.replace("Mapping", "").toUpperCase() + " " + path;
                                    graph.upsertNode(ep, "Endpoint", rel, line, "");
                                    graph.addEdge(mName, "EXPOSES", ep, rel);
                                }
                            });

                            // naive CALLS edges (unresolved scope -> best effort)
                            m.findAll(MethodCallExpr.class).forEach(call ->
                                call.getScope().ifPresent(scope ->
                                    graph.addEdge(mName, "CALLS",
                                        scope + "." + call.getNameAsString(), rel)));
                        });
                    }));
            } catch (Exception ignored) {}
        }
    }

    private static List<String> fieldDeps(ClassOrInterfaceDeclaration cls) {
        return cls.getFields().stream()
            .map(f -> f.getElementType().asString().replaceAll("<.*>", ""))
            .filter(t -> !t.isEmpty() && Character.isUpperCase(t.charAt(0)))
            .filter(t -> !IGNORE.contains(t))
            .distinct()
            .toList();
    }
}


package dev.gamechanger.llmindex;

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


package dev.gamechanger.llmindex;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.*;
import java.sql.ResultSet;
import java.util.concurrent.Callable;

@Command(name = "query", description = "Query the knowledge graph and emit context.md for the LLM.")
public class QueryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Search term, e.g. a class name or keyword")
    String term;

    @Option(names = {"-d", "--dir"}, defaultValue = ".llm-index", description = "Index directory")
    String dir;

    @Option(names = {"--hops"}, defaultValue = "3", description = "Traversal depth")
    int hops;

    @Override
    public Integer call() throws Exception {
        Path db = Path.of(dir, "graph.db");
        if (!Files.exists(db)) {
            System.err.println("No index found. Run: llm-index build");
            return 1;
        }

        StringBuilder ctx = new StringBuilder("# Context for: ").append(term).append("\n\n");

        try (GraphStore graph = new GraphStore(db)) {
            // 1. seed nodes: name match
            ctx.append("## Matching code\n");
            try (ResultSet rs = graph.query("""
                    SELECT name, type, file_path, line_no FROM nodes
                    WHERE name LIKE ? AND file_path != '' LIMIT 10""",
                    "%" + term + "%")) {
                while (rs.next())
                    ctx.append("- ").append(rs.getString(1))
                       .append(" (").append(rs.getString(3))
                       .append(":").append(rs.getInt(4)).append(")\n");
            }

            // 2. what uses it (impact)
            ctx.append("\n## Used by (impact if changed)\n");
            try (ResultSet rs = graph.query("""
                    SELECT DISTINCT src FROM edges
                    WHERE dst LIKE ? AND predicate IN ('USES','CALLS') LIMIT 15""",
                    "%" + term + "%")) {
                while (rs.next()) ctx.append("- ").append(rs.getString(1)).append("\n");
            }

            // 3. call chain outward (recursive CTE)
            ctx.append("\n## Calls outward from here\n");
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
                    ctx.append("- ").append("  ".repeat(rs.getInt(2)))
                       .append(rs.getString(1)).append("\n");
            }
        }

        ctx.append("\n## Instructions\nRead only the files listed above. Start at the file:line refs.\n");
        Path outFile = Path.of(dir, "context.md");
        Files.writeString(outFile, ctx.toString());
        System.out.println(ctx);
        System.out.println("Written to " + outFile);
        return 0;
    }
}


package dev.gamechanger.llmindex;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class Hashes {

    private final Path stateFile;
    private final Map<String, String> old = new HashMap<>();

    public Hashes(Path stateFile) {
        this.stateFile = stateFile;
        if (Files.exists(stateFile)) {
            try {
                for (String line : Files.readAllLines(stateFile)) {
                    int i = line.lastIndexOf('=');
                    if (i > 0) old.put(line.substring(0, i), line.substring(i + 1));
                }
            } catch (IOException ignored) {}
        }
    }

    public List<Path> changedFiles(List<Path> files) {
        List<Path> changed = new ArrayList<>();
        for (Path f : files)
            if (!hash(f).equals(old.get(f.toString()))) changed.add(f);
        return changed;
    }

    public void save(List<Path> files) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path f : files) sb.append(f).append('=').append(hash(f)).append('\n');
        Files.writeString(stateFile, sb.toString());
    }

    private String hash(Path f) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}

#!/usr/bin/env bash
set -e
VERSION="0.1.0"
URL="https://github.com/YOURUSER/llm-index/releases/download/v${VERSION}/llm-index.jar"
INSTALL_DIR="${HOME}/.llm-index"

mkdir -p "$INSTALL_DIR"
echo "Downloading llm-index ${VERSION}..."
curl -fsSL "$URL" -o "$INSTALL_DIR/llm-index.jar"

cat > "$INSTALL_DIR/llm-index" << 'EOF'
#!/usr/bin/env bash
exec java -jar "$HOME/.llm-index/llm-index.jar" "$@"
EOF
chmod +x "$INSTALL_DIR/llm-index"

# add to PATH if not present
SHELL_RC="$HOME/.bashrc"
[[ "$SHELL" == */zsh ]] && SHELL_RC="$HOME/.zshrc"
if ! grep -q '.llm-index' "$SHELL_RC" 2>/dev/null; then
    echo 'export PATH="$HOME/.llm-index:$PATH"' >> "$SHELL_RC"
    echo "Added to PATH in $SHELL_RC - restart your terminal."
fi
echo "Installed! Usage: llm-index build"

mvn clean package                 # produces target/llm-index.jar (fat jar)
java -jar target/llm-index.jar build     # test locally
Then on GitHub:

Push the repo, create a release v0.1.0, attach target/llm-index.jar
Users install with: curl -fsSL https://raw.githubusercontent.com/YOURUSER/llm-index/main/install.sh | bash
Usage anywhere: llm-index build, then llm-index query "OrderService"

Distribution upgrades (in order of effort)

JBang (easiest "proper" distribution for Java CLIs): add a jbang-catalog.json to your repo and users run jbang llm-index@yourusr. No install script needed, JBang handles Java too.
SDKMAN or Homebrew tap: brew install yourusr/tap/llm-index — just a formula file pointing at your release jar.
GraalVM native-image: compile to a single native binary (llm-index with no JVM needed, ~30ms startup). Picocli has first-class GraalVM support — add the picocli-codegen annotation processor and run native-image -jar llm-index.jar. This is the most professional feel but test SQLite JDBC carefully (it needs native library config).
Maven Central publish: lets other Java devs use your indexer as a library, not just a CLI.

A few honest notes on the code: the CALLS edges use unresolved scope names (orderService.createOrder rather than the fully-qualified class), which is fine for LIKE-based queries but you'll want JavaParser's SymbolSolver for precise resolution later; and the endpoint path regex is naive — it works for @GetMapping("/api/x") but not multi-attribute annotations. Both are cleanly upgradeable inside DepsBuilder without touching anything