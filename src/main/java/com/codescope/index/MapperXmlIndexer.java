package com.codescope.index;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Indexes MyBatis mapper XML files (issue #9 item 4: the tool used to
 * index only {@code .java} sources, so a mapper interface method's SQL —
 * the part that actually touches the database — was invisible).
 *
 * <p>Scans the project for {@code *.xml} files outside build output and
 * hidden directories, parses each with StAX (chosen over DOM because it
 * exposes the start tag's line number), and records every
 * {@code <select>} / {@code <insert>} / {@code <update>} / {@code <delete>}
 * (plus reusable {@code <sql>} fragments) whose parent is the root
 * {@code <mapper>} element. Each statement becomes a
 * {@link ProjectIndex.MapperStatement} and a {@code mapper-statement}
 * symbol so {@code find_symbols} can find it.
 *
 * <p>The statement body is captured as normalized text with nested
 * dynamic-SQL tags ({@code <if>}, {@code <foreach>}, {@code <include>},
 * …) preserved as tag text, so the snippet still shows the shape of the
 * query. Whitespace is collapsed to a single line for display.
 *
 * <p>Parsed after the Java sources in {@link JdtIndexer#build} — mapper
 * statements are looked up by {@code (namespace, id)} at query time, so
 * they do not need the Java bindings.
 */
public final class MapperXmlIndexer {

    /** Same directory-depth bound as {@code ProjectLoader}'s source walk. */
    private static final int MAX_DIRECTORY_DEPTH = 12;

    /** Statement element names that map to a mapper interface method. */
    private static final Set<String> STATEMENT_TYPES =
            Set.of("select", "insert", "update", "delete", "sql");

    private final ProjectIndex index;
    private final Path projectRoot;

    public MapperXmlIndexer(ProjectIndex index, Path projectRoot) {
        this.index = index;
        this.projectRoot = projectRoot;
    }

    /** Scans the project and records every mapper statement found. */
    public void index() {
        index(false);
    }

    /**
     * As {@link #index()}, honoring the {@code includeTests} flag the Java
     * source walk already obeys: mapper XML under {@code src/<...>/test/…}
     * is skipped unless it is true (issue #9 follow-up).
     */
    public void index(boolean includeTests) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return;
        try (Stream<Path> walk = Files.walk(projectRoot, MAX_DIRECTORY_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".xml"))
                    .filter(p -> isScanCandidate(p, includeTests))
                    .forEach(this::parseFile);
        } catch (Exception e) {
            // A walk failure (permissions, a disappearing directory) must not
            // abort the build: the Java index is the primary product.
            index.recordSkippedFile(projectRoot.toString(),
                    "mapper xml scan failed: " + e.getMessage());
        }
    }

    /**
     * Excludes build output, VCS, hidden directories, and — when
     * {@code includeTests} is false — test trees. Evaluated on the path
     * <em>relative to the project root</em> so a project that lives under a
     * hidden directory (e.g. {@code ~/.cache/proj}) is still scanned.
     *
     * <p>A test tree is recognized by a {@code test} segment immediately
     * followed by {@code java} or {@code resources} (the Maven layout the
     * Java source walk uses), so a main resource folder that merely
     * contains a {@code test} subdirectory is not skipped.
     */
    private boolean isScanCandidate(Path p, boolean includeTests) {
        Path rel;
        try {
            rel = projectRoot.relativize(p);
        } catch (RuntimeException e) {
            rel = p;
        }
        Path prev = null;
        for (Path part : rel) {
            String name = part.toString();
            if (name.startsWith(".") || name.equals("target") || name.equals("node_modules")) {
                return false;
            }
            if (!includeTests && prev != null && prev.toString().equals("test")
                    && (name.equals("java") || name.equals("resources"))) {
                return false;
            }
            prev = part;
        }
        return true;
    }

    private void parseFile(Path xml) {
        String relPath;
        try {
            relPath = projectRoot.relativize(xml).toString();
        } catch (RuntimeException e) {
            relPath = xml.toString();
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // XXE hardening: mapper XML has no DTD and must not pull external
        // entities. Without these a crafted mapper could read local files.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        try (InputStream in = Files.newInputStream(xml)) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            String namespace = null;
            boolean rootSeen = false;
            String stmtId = null;
            String stmtType = null;
            int stmtLine = 0;
            int stmtDepth = 0;
            StringBuilder sql = null;

            while (r.hasNext()) {
                int event = r.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = r.getLocalName();
                        if (!rootSeen) {
                            if (!"mapper".equals(name)) {
                                // Not a MyBatis mapper file; stop early.
                                r.close();
                                return;
                            }
                            rootSeen = true;
                            namespace = attr(r, "namespace");
                            if (namespace == null || namespace.isEmpty()) {
                                r.close();
                                return;
                            }
                        } else if (stmtId == null && stmtDepth == 0
                                && STATEMENT_TYPES.contains(name)) {
                            stmtId = attr(r, "id");
                            if (stmtId == null || stmtId.isEmpty()) {
                                // A statement without an id cannot be mapped to
                                // an interface method; skip it.
                                break;
                            }
                            stmtType = name;
                            stmtLine = r.getLocation().getLineNumber();
                            stmtDepth = 0;
                            sql = new StringBuilder();
                        } else if (stmtId != null) {
                            // Nested dynamic-SQL element: keep the tag text so
                            // the snippet shows the query shape.
                            stmtDepth++;
                            appendTag(sql, r, name, false);
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (stmtId != null && sql != null && !r.isWhiteSpace()) {
                            sql.append(r.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (stmtId == null) continue;
                        String name = r.getLocalName();
                        if (stmtDepth == 0 && STATEMENT_TYPES.contains(name)) {
                            record(namespace, stmtId, stmtType, stmtLine,
                                    normalize(sql), relPath);
                            stmtId = null;
                            stmtType = null;
                            sql = null;
                        } else if (stmtDepth > 0) {
                            stmtDepth--;
                            appendTag(sql, r, name, true);
                        }
                    }
                    default -> { /* ignored */ }
                }
            }
        } catch (Exception e) {
            // An unparseable XML file is reported but does not abort the build.
            index.recordSkippedFile(relPath, "mapper xml parse error: " + e.getMessage());
        }
    }

    private static String attr(XMLStreamReader r, String name) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (name.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i);
        }
        return null;
    }

    /** Appends {@code <name k="v">} or {@code </name>} to the SQL builder. */
    private static void appendTag(StringBuilder sb, XMLStreamReader r, String name,
                                  boolean end) {
        if (sb == null) return;
        sb.append('<');
        if (end) sb.append('/');
        sb.append(name);
        if (!end) {
            for (int i = 0; i < r.getAttributeCount(); i++) {
                sb.append(' ').append(r.getAttributeLocalName(i))
                        .append("=\"").append(r.getAttributeValue(i)).append('"');
            }
        }
        sb.append('>');
    }

    private static String normalize(StringBuilder raw) {
        if (raw == null) return "";
        return raw.toString().replaceAll("\\s+", " ").trim();
    }

    private void record(String namespace, String id, String type, int line,
                        String sql, String relPath) {
        ProjectIndex.MapperStatement statement =
                new ProjectIndex.MapperStatement(namespace, id, type, sql, relPath, line);
        index.recordMapperStatement(statement);
        // Also surface the statement in find_symbols: searchable by id
        // (the name) and by SQL content (the detail payload).
        index.recordSymbol(new ProjectIndex.Symbol(
                id, "mapper-statement", namespace + "#" + id, namespace, relPath, line, type, sql));
    }
}
