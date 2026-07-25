package com.codescope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests for {@link FindCallSitesService}. The
 * data-model-level guarantees (multi-site preservation, ordering) live
 * in {@link EdgeCaseTest#callSitesRecordMultipleSitesForSameCaller};
 * this class covers the service's JSON envelope, library-target
 * resolution, cache/refresh behavior, and error paths.
 */
class FindCallSitesServiceTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixture-project");

    @Test
    void singleCallSiteIsReported() throws Exception {
        // Fixture: com.example.Mid#callsLeaf (declared at Mid.java:6)
        // invokes com.example.Target#leaf at Mid.java:7. This is the
        // simplest one-caller-one-site case — verifies the envelope
        // shape and that caller_file / caller_line are populated from
        // the declaration while call_site.line is the actual call
        // position.
        FindCallSitesService svc = new FindCallSitesService();
        String json = svc.findCallSitesJson("com.example.Target", "leaf",
                null, null, FIXTURE, false, false);
        JsonNode tree = new ObjectMapper().readTree(json);

        JsonNode target = tree.path("target");
        assertEquals("com.example.Target", target.path("class").asText());
        assertEquals("leaf", target.path("method").asText());
        assertEquals(0, target.path("arity").asInt());
        assertEquals("com.example.Target#leaf/0", target.path("signature").asText());
        assertEquals("ok", tree.path("status").asText());

        JsonNode sites = tree.path("call_sites");
        assertTrue(sites.isArray());
        assertEquals(1, sites.size(), "expected 1 call site, got: " + sites);

        JsonNode entry = sites.get(0);
        assertEquals("com.example.Mid#callsLeaf/0", entry.path("caller").asText());
        assertEquals("src/main/java/com/example/Mid.java", entry.path("caller_file").asText());
        assertEquals(6, entry.path("caller_line").asInt(), "caller_line is the method declaration");
        JsonNode callSite = entry.path("call_site");
        assertEquals("src/main/java/com/example/Mid.java", callSite.path("file").asText());
        assertEquals(7, callSite.path("line").asInt(), "call_site.line is the call expression, not the declaration");
    }

    @Test
    void multipleSitesInSameCallerAreAllReported() throws Exception {
        // Copy the fixture to a tmp dir, add a class that calls
        // Target#leaf from three different lines. Combined with the
        // existing Mid#callsLeaf call site, we expect 4 call sites
        // total — and the caller's sites must all be present (not
        // deduped) and in source order.
        Path tmp = Files.createTempDirectory("codescope-fcs-multisite-");
        try {
            copyDir(FIXTURE, tmp);
            String hot = """
                    package com.example;
                    public class HotCaller {
                        public void go() {
                            new com.example.Target().leaf();
                            new com.example.Target().leaf();
                            new com.example.Target().leaf();
                        }
                    }
                    """;
            Files.writeString(tmp.resolve("src/main/java/com/example/HotCaller.java"), hot);

            FindCallSitesService svc = new FindCallSitesService();
            // refresh=true so the indexer sees the new file.
            String json = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, true, false);
            JsonNode tree = new ObjectMapper().readTree(json);
            JsonNode sites = tree.path("call_sites");
            assertEquals(4, sites.size(),
                    "expected 4 call sites (1 from Mid + 3 from HotCaller), got: " + sites);

            // The first 3 entries should all be from HotCaller (sorted by
            // caller_file "HotCaller.java" < "Mid.java"). Verify the
            // call_site lines are 4, 5, 6 — the three .leaf() calls in
            // HotCaller.go() (declared at line 3).
            for (int i = 0; i < 3; i++) {
                JsonNode s = sites.get(i);
                assertEquals("com.example.HotCaller#go/0", s.path("caller").asText());
                assertEquals("src/main/java/com/example/HotCaller.java",
                        s.path("call_site").path("file").asText());
                assertEquals(4 + i, s.path("call_site").path("line").asInt(),
                        "site " + i + " should be at line " + (4 + i) + ", got: " + s);
            }
            // Caller declaration line is 3 (where `go()` is declared).
            assertEquals(3, sites.get(0).path("caller_line").asInt());
            // The Mid entry should be the 4th (alphabetically later
            // file name). It has 1 call site at line 7.
            JsonNode mid = sites.get(3);
            assertEquals("com.example.Mid#callsLeaf/0", mid.path("caller").asText());
            assertEquals(7, mid.path("call_site").path("line").asInt());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void libraryTargetReturnsCallSites() throws Exception {
        // Same shape as trace_callers' library-target test: querying
        // PrintStream#println by (class, name) only, with no arity,
        // must still return the call sites recorded by the indexer
        // (the indexer captures each println overload as a separate
        // binding, and the service unions them).
        FindCallSitesService svc = new FindCallSitesService();
        String json = svc.findCallSitesJson("java.io.PrintStream", "println",
                null, null, FIXTURE, false, false);
        JsonNode tree = new ObjectMapper().readTree(json);
        JsonNode sites = tree.path("call_sites");
        assertTrue(sites.isArray() && sites.size() > 0,
                "expected >=1 call site for PrintStream#println, got: " + json);
        // Every entry should have a non-empty caller signature from
        // project code (Target.leaf and Target.leafWithArg both call
        // System.out.println at their respective lines).
        for (JsonNode s : sites) {
            String caller = s.path("caller").asText();
            assertTrue(caller.startsWith("com.example."),
                    "expected project caller, got: " + caller);
            assertTrue(s.path("call_site").path("line").asInt() > 0,
                    "call_site.line should be > 0, got: " + s);
        }
        // Message should mention "combined callers across N library
        // overloads" — the multi-seed union fired. (Wording unified
        // with trace_callers via MethodResolver.overloadUnionSuffix.)
        String message = tree.path("message").asText();
        assertTrue(message.contains("combined callers across"),
                "expected overload-union hint in message, got: " + message);
    }

    @Test
    void noCallersReturnsEmptyArrayWithDiagnostic() throws Exception {
        // com.example.Mid#unrelated is declared in the fixture but
        // never called. The service must return an empty array (not
        // null), a status of "ok", and a message that names the
        // diagnostic reason.
        FindCallSitesService svc = new FindCallSitesService();
        String json = svc.findCallSitesJson("com.example.Mid", "unrelated",
                null, null, FIXTURE, false, false);
        JsonNode tree = new ObjectMapper().readTree(json);
        assertEquals("ok", tree.path("status").asText());
        JsonNode sites = tree.path("call_sites");
        assertTrue(sites.isArray());
        assertEquals(0, sites.size(), "expected empty call_sites, got: " + sites);
        String message = tree.path("message").asText();
        assertTrue(message.contains("No call sites"),
                "expected 'No call sites' diagnostic, got: " + message);
    }

    @Test
    void unknownTargetReturnsEmptyArrayWithDiagnostic() throws Exception {
        // A class that doesn't exist at all is the same code path as
        // "no callers" — the synthesized display key has no recorded
        // call sites, so the result is empty (not an error). Same
        // shape as trace_callers' unknown target.
        FindCallSitesService svc = new FindCallSitesService();
        String json = svc.findCallSitesJson("com.example.NoSuch", "missing",
                null, null, FIXTURE, false, false);
        JsonNode tree = new ObjectMapper().readTree(json);
        assertEquals("ok", tree.path("status").asText());
        assertEquals(0, tree.path("call_sites").size());
        assertTrue(tree.path("message").asText().contains("No call sites"),
                "expected 'No call sites' diagnostic, got: " + json);
    }

    @Test
    void ambiguousOverloadReturnsFindCallSitesException() throws Exception {
        // com.example.Target has two `process` methods, arity 1, with
        // different param types. Without paramTypes, resolveTarget
        // throws AmbiguousMethodException, which the service must
        // rewrap as FindCallSitesException with the overloads hint —
        // same contract as TraceCallersService.
        FindCallSitesService svc = new FindCallSitesService();
        FindCallSitesService.FindCallSitesException ex = assertThrows(
                FindCallSitesService.FindCallSitesException.class,
                () -> svc.findCallSitesJson("com.example.Target", "process",
                        null, null, FIXTURE, false, false));
        String msg = ex.getMessage();
        assertTrue(msg.contains("process"), "should name the method: " + msg);
        assertTrue(msg.contains("overloads") || msg.contains("paramTypes"),
                "should hint at overloads or paramTypes, got: " + msg);
    }

    @Test
    void crossesInterfaceBoundaryForCallSites() throws Exception {
        // Regression for issue #3: when a caller invokes a method
        // through an interface (e.g. `ifaceImpl.findById(id)` where
        // `ifaceImpl`'s static type is the interface), JDT binds the
        // call to the interface MethodKey. If the user targets the
        // concrete implementation (IfaceRepositoryImpl#findById), the
        // recorded call edge is under IfaceRepository#findById, not
        // IfaceRepositoryImpl#findById — naive lookup misses it.
        //
        // The service must walk relatedMethods (overrides +
        // implementors + self) and union the call-site maps so the
        // interface-typed call site shows up under the impl-targeted
        // query.
        FindCallSitesService svc = new FindCallSitesService();
        // Target the impl. IfaceDomainImpl#findById also receives a
        // call through the IfaceRepository interface, so we expect
        // 1 call site from IfaceDomainImpl.
        String json = svc.findCallSitesJson(
                "com.example.IfaceRepositoryImpl", "findById",
                null, null, FIXTURE, false, false);
        JsonNode tree = new ObjectMapper().readTree(json);

        JsonNode sites = tree.path("call_sites");
        assertTrue(sites.isArray());
        // The impl's own callers (none in the fixture) plus the
        // interface-typed caller IfaceDomainImpl#findById. The
        // interface-typed call must surface.
        boolean foundIfaceDomain = false;
        for (JsonNode s : sites) {
            if (s.path("caller").asText().contains("IfaceDomainImpl#findById")) {
                foundIfaceDomain = true;
                // call_site.file/line should point at the call inside
                // IfaceDomainImpl#findById (line 8 of the impl body).
                assertEquals("src/main/java/com/example/IfaceDomainImpl.java",
                        s.path("call_site").path("file").asText());
                assertEquals(8, s.path("call_site").path("line").asInt());
                break;
            }
        }
        assertTrue(foundIfaceDomain,
                "expected IfaceDomainImpl to surface as a caller of "
                        + "IfaceRepositoryImpl#findById via the interface; got: "
                        + sites);
    }

    @Test
    void refreshAfterFileEditPicksUpNewCallSites() throws Exception {
        // Same shape as TraceCallersServiceTest.refreshAfterFileEdit:
        // edit a source file, query without refresh (cached, stale),
        // then with refresh (sees the new file). Verifies the cache +
        // refresh wiring on the new service.
        Path tmp = Files.createTempDirectory("codescope-fcs-refresh-");
        try {
            copyDir(FIXTURE, tmp);
            FindCallSitesService svc = new FindCallSitesService();

            // 1) Initial: Target#leaf has 1 call site (from Mid).
            String first = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, false, false);
            JsonNode t1 = new ObjectMapper().readTree(first);
            assertEquals(1, t1.path("call_sites").size(),
                    "expected 1 call site initially, got: " + t1);

            // 2) Add a new caller.
            String newCaller = """
                    package com.example;
                    public class NewCaller {
                        public void go() {
                            new com.example.Target().leaf();
                            new com.example.Target().leaf();
                        }
                    }
                    """;
            Files.writeString(tmp.resolve("src/main/java/com/example/NewCaller.java"), newCaller);

            // 3) Without refresh, the cached index does NOT see the new
            //    caller. Stale result.
            String stale = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, false, false);
            JsonNode t2 = new ObjectMapper().readTree(stale);
            assertEquals(1, t2.path("call_sites").size(),
                    "cached result must NOT pick up the new file, got: " + t2);

            // 4) With refresh=true, the new caller shows up.
            String fresh = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, true, false);
            JsonNode t3 = new ObjectMapper().readTree(fresh);
            assertEquals(3, t3.path("call_sites").size(),
                    "refresh should pick up the 2 new sites, got: " + t3);
        } finally {
            deleteRecursively(tmp);
        }
    }

    // --- helpers (mirror TraceCallersServiceTest) ---

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
