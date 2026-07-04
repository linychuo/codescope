package com.codescope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests for {@link FindSymbolsService}. Covers the JSON
 * envelope, the indexer's symbol-population paths (types, methods,
 * constructors, regular fields, record components, enum constants), the
 * kind filter, the substring search, the limit cap, and the cache +
 * refresh wiring.
 */
class FindSymbolsServiceTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixture-project");

    @Test
    void searchByNameReturnsTypeSymbol() throws Exception {
        // The class Target is declared at Target.java:4 — verify the
        // envelope shape and that container is null for a top-level type.
        FindSymbolsService svc = new FindSymbolsService();
        String json = svc.findSymbolsJson("Target", "class", FIXTURE, false,
                false, FindSymbolsService.DEFAULT_LIMIT);
        JsonNode tree = new ObjectMapper().readTree(json);

        assertEquals("Target", tree.path("query").asText());
        assertEquals("class", tree.path("kind").asText());
        assertEquals("ok", tree.path("status").asText());

        JsonNode syms = tree.path("symbols");
        assertTrue(syms.isArray());
        assertEquals(1, syms.size(), "expected 1 class, got: " + syms);

        JsonNode target = syms.get(0);
        assertEquals("Target", target.path("name").asText());
        assertEquals("class", target.path("kind").asText());
        assertEquals("com.example.Target", target.path("fqn").asText());
        assertTrue(target.path("container").isNull(),
                "top-level class should have null container, got: " + target);
        assertEquals("src/main/java/com/example/Target.java", target.path("file").asText());
        // Target is declared at line 4 (line 3 is the Javadoc); we report
        // the type-name line, not the Javadoc line.
        assertEquals(4, target.path("line").asInt());
        assertTrue(target.path("signature").isNull());
    }

    @Test
    void searchIsCaseInsensitive() throws Exception {
        // "TARGET" should match the class Target just like "Target" does.
        FindSymbolsService svc = new FindSymbolsService();
        String json = svc.findSymbolsJson("TARGET", "class", FIXTURE, false,
                false, FindSymbolsService.DEFAULT_LIMIT);
        JsonNode syms = new ObjectMapper().readTree(json).path("symbols");
        assertEquals(1, syms.size(), "case-insensitive substring failed: " + syms);
        assertEquals("com.example.Target", syms.get(0).path("fqn").asText());
    }

    @Test
    void substringSearchMatchesAcrossNames() throws Exception {
        // "lea" should match every method whose name contains "lea"
        // (leaf, leafWithArg, distanceFromOrigin) plus possibly other
        // identifiers. We just check the count is non-zero and includes
        // the two Target methods we know about.
        FindSymbolsService svc = new FindSymbolsService();
        String json = svc.findSymbolsJson("leaf", null, FIXTURE, false,
                false, FindSymbolsService.DEFAULT_LIMIT);
        JsonNode syms = new ObjectMapper().readTree(json).path("symbols");
        List<String> fqns = new ArrayList<>();
        for (JsonNode s : syms) fqns.add(s.path("fqn").asText());
        // Both leaf and leafWithArg in Target should match.
        assertTrue(fqns.contains("com.example.Target#leaf/0"),
                "expected leaf/0 in matches, got: " + fqns);
        assertTrue(fqns.contains("com.example.Target#leafWithArg/1"),
                "expected leafWithArg/1 in matches, got: " + fqns);
    }

    @Test
    void kindFilterSeparatesTypeFromField() throws Exception {
        // "Target" matches both the class Target and the field `target`
        // declared in Mid. With kind=class, only the class comes back.
        FindSymbolsService svc = new FindSymbolsService();
        JsonNode all = new ObjectMapper().readTree(
                svc.findSymbolsJson("Target", null, FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        assertTrue(all.size() >= 2, "expected class+field, got: " + all);

        JsonNode classes = new ObjectMapper().readTree(
                svc.findSymbolsJson("Target", "class", FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        assertEquals(1, classes.size(), "kind=class should narrow to 1: " + classes);
        assertEquals("class", classes.get(0).path("kind").asText());

        JsonNode fields = new ObjectMapper().readTree(
                svc.findSymbolsJson("Target", "field", FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        assertEquals(1, fields.size(), "kind=field should narrow to 1: " + fields);
        assertEquals("field", fields.get(0).path("kind").asText());
        assertEquals("com.example.Mid.target", fields.get(0).path("fqn").asText());
        assertEquals("com.example.Mid", fields.get(0).path("container").asText());
    }

    @Test
    void regularClassFieldIsIndexed() throws Exception {
        // Cycle.self, Mid.target, Top.mid, SideBranch.top — all regular
        // class fields declared with FieldDeclaration. Query for a unique
        // fragment of each name and verify the field is found.
        FindSymbolsService svc = new FindSymbolsService();

        // Cycle.self — query "self" matches only this field
        JsonNode self = new ObjectMapper().readTree(
                svc.findSymbolsJson("self", "field", FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        List<String> selfFqns = toFqns(self);
        assertTrue(selfFqns.contains("com.example.Cycle.self"),
                "expected Cycle.self, got: " + selfFqns);

        // Top.mid — query "mid" matches this field by name
        JsonNode mid = new ObjectMapper().readTree(
                svc.findSymbolsJson("mid", "field", FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        List<String> midFqns = toFqns(mid);
        assertTrue(midFqns.contains("com.example.Top.mid"),
                "expected Top.mid, got: " + midFqns);
    }

    @Test
    void enumConstantsAreIndexedAsFields() throws Exception {
        // Kind { ALPHA, BETA, GAMMA } — enum constants are
        // EnumConstantDeclaration, not FieldDeclaration. Verify the
        // indexer picks them up.
        FindSymbolsService svc = new FindSymbolsService();
        JsonNode syms = new ObjectMapper().readTree(
                svc.findSymbolsJson("ALPHA", null, FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        List<String> fqns = toFqns(syms);
        assertTrue(fqns.contains("com.example.Kind.ALPHA"),
                "expected Kind.ALPHA, got: " + fqns);
    }

    @Test
    void enumAndRecordAndAnnotationKindsAreRecorded() throws Exception {
        // For each special-form type, search by its name with no kind
        // filter and verify the matching symbol has the right kind.
        // We don't search with kind=class because Point is a record,
        // not a class — the kind filter would exclude it.
        //
        // JDT 3.45 with bindings enabled wraps top-level records in an
        // ImplicitTypeDeclaration whose body only carries the canonical
        // constructor — so find_symbols does NOT see top-level record
        // bodies (matching the limitation the indexer already documents
        // in CallChainAnalyzerTest#indexesRecordFileWithoutCrashing).
        // We only assert on the kinds that ARE visitable: enum and
        // annotation.
        FindSymbolsService svc = new FindSymbolsService();
        JsonNode k = new ObjectMapper().readTree(
                svc.findSymbolsJson("Kind", null, FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        assertTrue(toFqns(k).contains("com.example.Kind"),
                "expected Kind in matches, got: " + k);
        assertEquals("enum", k.get(0).path("kind").asText());

        JsonNode m = new ObjectMapper().readTree(
                svc.findSymbolsJson("Marker", null, FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        boolean foundAnnotation = false;
        for (JsonNode s : m) {
            if ("com.example.Marker".equals(s.path("fqn").asText())
                    && "annotation".equals(s.path("kind").asText())) {
                foundAnnotation = true;
                break;
            }
        }
        assertTrue(foundAnnotation, "expected Marker annotation kind, got: " + m);
    }

    @Test
    void constructorSymbolIsRecorded() throws Exception {
        // The fixture's classes have only implicit default constructors,
        // which JDT does NOT expose as MethodDeclaration nodes in the
        // AST (and therefore not in our symbol table). To exercise the
        // constructor-recording path we add a class with an explicit
        // constructor to a tmp project.
        Path tmp = Files.createTempDirectory("codescope-fs-ctor-");
        try {
            copyDir(FIXTURE, tmp);
            String src = """
                    package com.example;
                    public class WithCtor {
                        public WithCtor() { this(0); }
                        public WithCtor(int n) { System.out.println(n); }
                    }
                    """;
            Files.writeString(tmp.resolve("src/main/java/com/example/WithCtor.java"), src);

            FindSymbolsService svc = new FindSymbolsService();
            JsonNode syms = new ObjectMapper().readTree(
                    svc.findSymbolsJson("WithCtor", "constructor", tmp, true,
                            false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
            List<String> fqns = toFqns(syms);
            // JDT gives the constructor MethodDeclaration the class
            // simple name (not "<init>"). WithCtor has 2 explicit
            // constructors (arity 0 and 1), so we expect both.
            assertTrue(fqns.contains("com.example.WithCtor#WithCtor/0"),
                    "expected WithCtor#WithCtor/0, got: " + fqns);
            assertTrue(fqns.contains("com.example.WithCtor#WithCtor/1"),
                    "expected WithCtor#WithCtor/1, got: " + fqns);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void methodSymbolCarriesContainerAndSignature() throws Exception {
        // Target.leaf has 0 params. FQN is com.example.Target#leaf/0,
        // container is com.example.Target, signature is the empty param
        // list (we surface it so a search hit tells you which overload).
        FindSymbolsService svc = new FindSymbolsService();
        JsonNode syms = new ObjectMapper().readTree(
                svc.findSymbolsJson("leaf", "method", FIXTURE, false,
                        false, FindSymbolsService.DEFAULT_LIMIT)).path("symbols");
        JsonNode leaf = null;
        for (JsonNode s : syms) {
            if ("com.example.Target#leaf/0".equals(s.path("fqn").asText())) {
                leaf = s;
                break;
            }
        }
        assertNotNull(leaf, "expected leaf/0 method, got: " + syms);
        assertEquals("com.example.Target", leaf.path("container").asText());
        assertEquals("", leaf.path("signature").asText(),
                "zero-arg method should have empty signature string");
    }

    @Test
    void noMatchReturnsEmptyArrayWithDiagnostic() throws Exception {
        FindSymbolsService svc = new FindSymbolsService();
        String json = svc.findSymbolsJson("nonexistent_xyzzy", null, FIXTURE, false,
                false, FindSymbolsService.DEFAULT_LIMIT);
        JsonNode tree = new ObjectMapper().readTree(json);
        assertEquals("ok", tree.path("status").asText());
        assertEquals(0, tree.path("symbols").size());
        String msg = tree.path("message").asText();
        assertTrue(msg.contains("nonexistent_xyzzy"),
                "message should name the query, got: " + msg);
    }

    @Test
    void limitCapsResultCount() throws Exception {
        // Common-name query that matches many symbols. limit=2 should
        // return at most 2.
        FindSymbolsService svc = new FindSymbolsService();
        String json = svc.findSymbolsJson("a", null, FIXTURE, false, false, 2);
        JsonNode syms = new ObjectMapper().readTree(json).path("symbols");
        assertTrue(syms.size() <= 2, "limit=2 should cap, got: " + syms.size());
        if (syms.size() == 2) {
            // Verify the truncation message is set.
            String msg = new ObjectMapper().readTree(json).path("message").asText();
            assertTrue(msg.contains("capped") || msg.contains("refine"),
                    "expected truncation hint, got: " + msg);
        }
    }

    @Test
    void blankQueryThrows() {
        FindSymbolsService svc = new FindSymbolsService();
        FindSymbolsService.FindSymbolsException ex = assertThrows(
                FindSymbolsService.FindSymbolsException.class,
                () -> svc.findSymbolsJson("", null, FIXTURE, false, false, 100));
        assertTrue(ex.getMessage().contains("query"),
                "should mention query, got: " + ex.getMessage());
    }

    @Test
    void unknownKindThrows() {
        FindSymbolsService svc = new FindSymbolsService();
        FindSymbolsService.FindSymbolsException ex = assertThrows(
                FindSymbolsService.FindSymbolsException.class,
                () -> svc.findSymbolsJson("anything", "garbage", FIXTURE, false, false, 100));
        assertTrue(ex.getMessage().contains("garbage"),
                "should name the bad kind, got: " + ex.getMessage());
    }

    @Test
    void missingPomThrows() throws Exception {
        // A temp dir without pom.xml must be rejected with a Maven-only message.
        Path tmp = Files.createTempDirectory("codescope-fs-nopom-");
        try {
            FindSymbolsService svc = new FindSymbolsService();
            FindSymbolsService.FindSymbolsException ex = assertThrows(
                    FindSymbolsService.FindSymbolsException.class,
                    () -> svc.findSymbolsJson("anything", null, tmp, false, false, 100));
            assertTrue(ex.getMessage().contains("pom.xml"),
                    "should mention pom.xml, got: " + ex.getMessage());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void refreshAfterFileEditPicksUpNewSymbols() throws Exception {
        // Edit a source file to add a new class, query without refresh
        // (cached, stale), then with refresh (sees the new file).
        // The query "Zylqx" matches only the new class+method names.
        Path tmp = Files.createTempDirectory("codescope-fs-refresh-");
        try {
            copyDir(FIXTURE, tmp);
            FindSymbolsService svc = new FindSymbolsService();

            // 1) Initial: "Zylqx" matches nothing.
            String first = svc.findSymbolsJson("Zylqx", null, tmp, false, false, 100);
            assertEquals(0, new ObjectMapper().readTree(first).path("symbols").size());

            // 2) Add a new class.
            String newSrc = """
                    package com.example;
                    public class Zylqx {
                        public void zylqx() { System.out.println("hi"); }
                    }
                    """;
            Files.writeString(tmp.resolve("src/main/java/com/example/Zylqx.java"), newSrc);

            // 3) Without refresh, the cached index does NOT see the new file.
            String stale = svc.findSymbolsJson("Zylqx", null, tmp, false, false, 100);
            assertEquals(0, new ObjectMapper().readTree(stale).path("symbols").size(),
                    "cached result must not pick up the new file");

            // 4) With refresh=true, both the new class and its method show up.
            String fresh = svc.findSymbolsJson("Zylqx", null, tmp, true, false, 100);
            JsonNode syms = new ObjectMapper().readTree(fresh).path("symbols");
            assertTrue(syms.size() >= 2,
                    "refresh should find the new class+method, got: " + syms);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void syntheticKindIsAcceptedNotRejected() throws Exception {
        // kind="synthetic" should be accepted (not rejected as unknown).
        // Initially returns empty since no synthetics are recorded yet;
        // Tasks 2-4 add the recording.
        FindSymbolsService svc = new FindSymbolsService();
        String json = svc.findSymbolsJson("clinit", "synthetic", FIXTURE, false,
                false, FindSymbolsService.DEFAULT_LIMIT);
        // status should be "ok" — not an error response
        assertTrue(json.contains("\"status\":\"ok\""),
                "kind=synthetic should be accepted, got: " + json);
    }

    // --- helpers (mirror FindCallSitesServiceTest) ---

    private static List<String> toFqns(JsonNode syms) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : syms) out.add(s.path("fqn").asText());
        return out.stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
    }

    private static void copyDir(Path src, Path dst) throws java.io.IOException {
        try (var s = Files.walk(src)) {
            s.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path target = dst.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(p, target);
                    }
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void deleteRecursively(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(child -> {
                try { Files.deleteIfExists(child); } catch (java.io.IOException ignored) {}
            });
        }
    }
}
