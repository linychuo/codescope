package com.codescope.service;

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
        // Issue #9: the call-site line text, so the actual call expression
        // is visible without re-opening the file.
        assertEquals("        target.leaf();", callSite.path("snippet").asText(),
                "call_site.snippet should be the call expression's line, got: " + callSite);
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
    void offsetAndLimitPageTheFlatList() throws Exception {
        // Issue #9: find_call_sites had no pagination — a target with many
        // callers returned one unbounded array. The window applies after the
        // stable sort, and `total` lets the caller see the full size.
        Path tmp = Files.createTempDirectory("codescope-fcs-paging-");
        try {
            copyDir(FIXTURE, tmp);
            Files.writeString(tmp.resolve("src/main/java/com/example/PagerCaller.java"), """
                    package com.example;
                    public class PagerCaller {
                        public void go() {
                            new com.example.Target().leaf();
                            new com.example.Target().leaf();
                            new com.example.Target().leaf();
                        }
                    }
                    """);
            FindCallSitesService svc = new FindCallSitesService();
            // 1 existing site (Mid#callsLeaf) + 3 new = 4 total.
            String all = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, true, false);
            JsonNode allTree = new ObjectMapper().readTree(all);
            assertEquals(4, allTree.path("total").asInt(),
                    "total must count every site before the window, got: " + allTree);
            assertEquals(4, allTree.path("call_sites").size());

            String page1 = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, false, false, 0, 2);
            JsonNode p1 = new ObjectMapper().readTree(page1);
            assertEquals(4, p1.path("total").asInt());
            assertEquals(2, p1.path("call_sites").size());
            assertTrue(p1.path("message").asText().contains("of 4"),
                    "a truncated window must say so, got: " + p1.path("message").asText());

            String page2 = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, false, false, 2, 2);
            assertEquals(2, new ObjectMapper().readTree(page2).path("call_sites").size());

            String page3 = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, false, false, 3, 2);
            assertEquals(1, new ObjectMapper().readTree(page3).path("call_sites").size());

            String beyond = svc.findCallSitesJson("com.example.Target", "leaf",
                    null, null, tmp, false, false, 10, 2);
            JsonNode beyondTree = new ObjectMapper().readTree(beyond);
            assertEquals(0, beyondTree.path("call_sites").size());
            assertEquals(4, beyondTree.path("total").asInt());
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void unresolvedInterfaceCallSurfacesAsCallSite() throws Exception {
        // Issue #9: a call whose receiver is a project interface inheriting
        // the method from a class supertype is not bound by JDT. The service
        // must still report it (attributed by receiver type + signature).
        Path tmp = Files.createTempDirectory("codescope-fcs-unresolved-");
        try {
            copyDir(FIXTURE, tmp);
            Files.writeString(tmp.resolve("src/main/java/com/example/UnresAbs.java"), """
                    package com.example;
                    public abstract class UnresAbs { public abstract void m(); }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/UnresI.java"), """
                    package com.example;
                    public interface UnresI extends UnresAbs { }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/UnresImpl.java"), """
                    package com.example;
                    public class UnresImpl implements UnresI { public void m() {} }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/UnresCaller.java"), """
                    package com.example;
                    public class UnresCaller { void go(UnresI i) { i.m(); } }
                    """);

            FindCallSitesService svc = new FindCallSitesService();
            String json = svc.findCallSitesJson("com.example.UnresImpl", "m",
                    null, null, tmp, true, false);
            JsonNode sites = new ObjectMapper().readTree(json).path("call_sites");
            assertTrue(sites.size() >= 1, "expected the unresolved call site, got: " + sites);
            boolean found = false;
            for (JsonNode s : sites) {
                if ("com.example.UnresCaller#go/1".equals(s.path("caller").asText())) {
                    found = true;
                    assertEquals("src/main/java/com/example/UnresCaller.java",
                            s.path("call_site").path("file").asText());
                }
            }
            assertTrue(found, "UnresCaller must surface, got: " + sites);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void inheritedMethodQueryFiltersSiblingOverrideCallSites() throws Exception {
        // Issue #8 at the service layer: InhChildA inherits save(), so the
        // query resolves to InhParent#save, but the receiver context stays
        // InhChildA — a call on an InhChildB receiver must not surface.
        Path tmp = Files.createTempDirectory("codescope-fcs-inherited-");
        try {
            copyDir(FIXTURE, tmp);
            Files.writeString(tmp.resolve("src/main/java/com/example/InhParent.java"), """
                    package com.example;
                    public class InhParent { public void save(String s) {} }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/InhChildA.java"), """
                    package com.example;
                    public class InhChildA extends InhParent { }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/InhChildB.java"), """
                    package com.example;
                    public class InhChildB extends InhParent {
                        @Override public void save(String s) {}
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/InhCallerA.java"), """
                    package com.example;
                    public class InhCallerA { void go() { new InhChildA().save("x"); } }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/InhCallerB.java"), """
                    package com.example;
                    public class InhCallerB { void go() { new InhChildB().save("x"); } }
                    """);

            FindCallSitesService svc = new FindCallSitesService();
            String json = svc.findCallSitesJson("com.example.InhChildA", "save",
                    null, null, tmp, true, false);
            JsonNode sites = new ObjectMapper().readTree(json).path("call_sites");
            java.util.Set<String> callers = new java.util.HashSet<>();
            for (JsonNode s : sites) callers.add(s.path("caller").asText());

            assertTrue(callers.contains("com.example.InhCallerA#go/0"),
                    "InhCallerA invokes the inherited save on a ChildA receiver, got: " + callers);
            assertFalse(callers.contains("com.example.InhCallerB#go/0"),
                    "issue #8: InhCallerB's receiver is InhChildB, which cannot hold an "
                            + "InhChildA, got: " + callers);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void invalidPagingWindowIsRejected() {
        FindCallSitesService svc = new FindCallSitesService();
        assertThrows(FindCallSitesService.FindCallSitesException.class,
                () -> svc.findCallSitesJson("com.example.Target", "leaf", null, null,
                        FIXTURE, false, false, -1, 10));
        assertThrows(FindCallSitesService.FindCallSitesException.class,
                () -> svc.findCallSitesJson("com.example.Target", "leaf", null, null,
                        FIXTURE, false, false, 0, 0));
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

    @Test
    void projectTargetWithInterfaceHierarchyDoesNotReportLibraryOverloads() throws Exception {
        // Regression: IfaceRepositoryImpl#findById is related to
        // IfaceRepository#findById via the method hierarchy, so the
        // service unions their call sites. That union is NOT a set of
        // library overloads — both keys are project types — so the
        // "(combined callers across N library overloads)" suffix must
        // not be appended. Only genuine library targets (no project
        // declaration) get that wording.
        FindCallSitesService svc = new FindCallSitesService();
        String json = svc.findCallSitesJson(
                "com.example.IfaceRepositoryImpl", "findById",
                null, null, FIXTURE, false, false);
        String message = new ObjectMapper().readTree(json).path("message").asText();
        assertFalse(message.contains("library overloads"),
                "project-target hierarchy union must not be labeled library overloads, got: "
                        + message);
    }

    @Test
    void receiverStaticTypeFiltersSiblingAndInheritedCallSites() throws Exception {
        // Issues #7 / #8 at the service layer: find_call_sites must apply
        // the same receiver-static-type filter as the caller BFS.
        // ReceiverChildA inherits save() (no override), so CallerA's
        // `new ReceiverChildA().save("x")` binds to ReceiverParent#save on
        // a ReceiverChildA receiver — it cannot dispatch to
        // ReceiverChildB#save and must not be reported for it.
        Path tmp = Files.createTempDirectory("codescope-fcs-receiver-");
        try {
            copyDir(FIXTURE, tmp);
            Files.writeString(tmp.resolve("src/main/java/com/example/ReceiverParent.java"), """
                    package com.example;
                    public class ReceiverParent {
                        public void save(String s) {}
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/ReceiverChildA.java"), """
                    package com.example;
                    public class ReceiverChildA extends ReceiverParent { }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/ReceiverChildB.java"), """
                    package com.example;
                    public class ReceiverChildB extends ReceiverParent {
                        @Override public void save(String s) {}
                    }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/ReceiverCallerA.java"), """
                    package com.example;
                    public class ReceiverCallerA { void go() { new ReceiverChildA().save("x"); } }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/ReceiverCallerB.java"), """
                    package com.example;
                    public class ReceiverCallerB { void go() { new ReceiverChildB().save("x"); } }
                    """);
            Files.writeString(tmp.resolve("src/main/java/com/example/ReceiverCallerP.java"), """
                    package com.example;
                    public class ReceiverCallerP { void go(ReceiverParent p) { p.save("x"); } }
                    """);

            FindCallSitesService svc = new FindCallSitesService();
            String json = svc.findCallSitesJson("com.example.ReceiverChildB", "save",
                    null, null, tmp, true, false);
            JsonNode sites = new ObjectMapper().readTree(json).path("call_sites");
            java.util.Set<String> callers = new java.util.HashSet<>();
            for (JsonNode s : sites) callers.add(s.path("caller").asText());

            assertTrue(callers.contains("com.example.ReceiverCallerB#go/0"),
                    "direct caller must be reported, got: " + callers);
            assertTrue(callers.contains("com.example.ReceiverCallerP#go/1"),
                    "a ReceiverParent-typed receiver can hold a ReceiverChildB, so CallerP "
                            + "must be reported, got: " + callers);
            assertFalse(callers.contains("com.example.ReceiverCallerA#go/0"),
                    "issue #8: CallerA's receiver is statically ReceiverChildA, which cannot "
                            + "dispatch to ReceiverChildB#save, got: " + callers);
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
