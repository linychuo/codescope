package com.codescope.index;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * (plus reusable {@code <sql>} fragments and {@code <resultMap>}s) whose
 * parent is the root {@code <mapper>} element. Each becomes a
 * {@link ProjectIndex.MapperStatement} and a {@code mapper-statement}
 * symbol so {@code find_symbols} can find it by id or SQL body.
 *
 * <p>Statement bodies are captured as normalized text with nested
 * dynamic-SQL tags ({@code <if>}, {@code <foreach>}, …) preserved, and
 * {@code <include refid="…">} is expanded from the referenced
 * {@code <sql>} fragment (same or other namespace, issue #9 follow-up).
 * Whitespace is collapsed to a single line for display.
 *
 * <p>Parsed after the Java sources in {@link JdtIndexer#build} — mapper
 * statements are looked up by {@code (namespace, id)} at query time, so
 * they do not need the Java bindings.
 */
public final class MapperXmlIndexer {

    /** Same directory-depth bound as {@code ProjectLoader}'s source walk. */
    private static final int MAX_DIRECTORY_DEPTH = 12;

    /** Element types that implement a mapper interface method. */
    private static final Set<String> STATEMENT_TYPES =
            Set.of("select", "insert", "update", "delete", "sql");

    /** Everything indexed, including {@code <resultMap>} (issue #9). */
    private static final Set<String> INDEXED_TYPES =
            Set.of("select", "insert", "update", "delete", "sql", "resultMap");

    /** {@code <include refid="…"/>} (both the explicit-close and self-closing spellings). */
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("<include\\s+refid=\"([^\"]+)\"\\s*(?:></include>|/>)");

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
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectRoot, MAX_DIRECTORY_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".xml"))
                    .filter(p -> isScanCandidate(p, includeTests))
                    .forEach(files::add);
        } catch (Exception e) {
            // A walk failure (permissions, a disappearing directory) must not
            // abort the build: the Java index is the primary product.
            index.recordSkippedFile(projectRoot.toString(),
                    "mapper xml scan failed: " + e.getMessage());
            return;
        }

        // Single parse pass collecting <sql> fragments and raw statements;
        // includes are expanded afterwards so a fragment may be defined in
        // any file (or referenced across namespaces).
        Map<String, Map<String, Fragment>> fragments = new HashMap<>();
        List<RawStatement> raw = new ArrayList<>();
        for (Path file : files) {
            parseFile(file, fragments, raw);
        }
        // Fragments stay searchable (kind=mapper-statement, type=sql) even
        // though they are not method implementations.
        for (Map.Entry<String, Map<String, Fragment>> ns : fragments.entrySet()) {
            for (Map.Entry<String, Fragment> e : ns.getValue().entrySet()) {
                Fragment f = e.getValue();
                record(new RawStatement(ns.getKey(), e.getKey(), "sql", "sql",
                        f.text(), f.file(), f.line()), f.text());
            }
        }
        for (RawStatement stmt : raw) {
            String sql = normalize(expandIncludes(stmt.rawSql, stmt.namespace, fragments,
                    new HashSet<>()));
            record(stmt, sql);
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

    /** One indexed element before include expansion. */
    private record RawStatement(String namespace, String id, String type, String signature,
                                String rawSql, String file, int line) {}

    /** A reusable {@code <sql>} fragment, kept searchable as well as expandable. */
    private record Fragment(String text, String file, int line) {}

    private void parseFile(Path xml, Map<String, Map<String, Fragment>> fragments,
                           List<RawStatement> raw) {
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
            String stmtSignature = null;
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
                                && INDEXED_TYPES.contains(name)) {
                            stmtId = attr(r, "id");
                            if (stmtId == null || stmtId.isEmpty()) {
                                // An element without an id cannot be mapped to
                                // an interface method; skip it.
                                break;
                            }
                            stmtType = name;
                            // For a <resultMap> the mapped type is the useful
                            // identifier; statements use the element type.
                            stmtSignature = "resultMap".equals(name) ? attr(r, "type") : name;
                            if (stmtSignature == null || stmtSignature.isEmpty()) stmtSignature = name;
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
                        if (stmtDepth == 0 && INDEXED_TYPES.contains(name)) {
                            String key = namespace + "#" + stmtId;
                            if ("sql".equals(stmtType)) {
                                // A reusable fragment, not a method
                                // implementation: collected for expansion
                                // (and re-recorded as a searchable symbol).
                                fragments.computeIfAbsent(namespace, k -> new HashMap<>())
                                        .put(stmtId, new Fragment(normalize(sql.toString()),
                                                relPath, stmtLine));
                            } else {
                                raw.add(new RawStatement(namespace, stmtId, stmtType,
                                        stmtSignature, sql.toString(), relPath, stmtLine));
                            }
                            stmtId = null;
                            stmtType = null;
                            stmtSignature = null;
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

    /**
     * Expands every {@code <include refid="…">} in {@code sql} from the
     * referenced {@code <sql>} fragment, recursively and with a cycle
     * guard. A {@code refid} containing a dot is treated as
     * {@code namespace.id} (cross-namespace); otherwise it resolves in the
     * statement's own namespace (issue #9 follow-up).
     */
    private static String expandIncludes(String sql, String namespace,
                                         Map<String, Map<String, Fragment>> fragments,
                                         Set<String> visited) {
        Matcher m = INCLUDE_PATTERN.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String refid = m.group(1);
            String ns;
            String id;
            int dot = refid.lastIndexOf('.');
            if (dot >= 0) {
                ns = refid.substring(0, dot);
                id = refid.substring(dot + 1);
            } else {
                ns = namespace;
                id = refid;
            }
            String key = ns + "#" + id;
            Fragment frag = fragments.getOrDefault(ns, Map.of()).get(id);
            String replacement = (frag != null && visited.add(key))
                    ? expandIncludes(frag.text(), ns, fragments, visited)
                    : "";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
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

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", " ").trim();
    }

    private void record(RawStatement stmt, String sql) {
        ProjectIndex.MapperStatement statement = new ProjectIndex.MapperStatement(
                stmt.namespace, stmt.id, stmt.type, sql, stmt.file, stmt.line);
        index.recordMapperStatement(statement);
        // Also surface it in find_symbols: searchable by id (the name) and
        // by SQL body (the detail payload).
        index.recordSymbol(new ProjectIndex.Symbol(
                stmt.id, "mapper-statement", stmt.namespace + "#" + stmt.id,
                stmt.namespace, stmt.file, stmt.line, stmt.signature, sql));
    }
}
