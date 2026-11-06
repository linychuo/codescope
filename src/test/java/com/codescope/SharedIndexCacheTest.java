package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The LRU index cache MUST be shared across services in a single
 * session. The field comments claim cross-tool sharing but the
 * implementation held one cache per service — so a session with
 * trace_callers + find_symbols against the same project built the
 * index twice.
 */
class SharedIndexCacheTest {

    @Test
    void twoServicesShareOneCache(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // Build a minimal Maven project so ProjectLoader.load succeeds.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Foo.java"),
                "package com.example; public class Foo { public void m() {} }");
        Files.writeString(tmp.resolve("pom.xml"),
                "<project>"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId>"
                + "<artifactId>test-project</artifactId>"
                + "<version>0.0.1-SNAPSHOT</version>"
                + "</project>");

        // Construct the shared cache and two services sharing it.
        ProjectIndexCache cache = new ProjectIndexCache();
        TraceCallersService trace = new TraceCallersService(cache);
        FindSymbolsService symbols = new FindSymbolsService(cache);

        // Drive both services against the same project. Each service call
        // goes through validateAndLoad → loadOrRebuild → buildIndex.
        // With one shared cache the second call hits the cache entry
        // the first call populated. Use reflection to inspect the entry
        // count and assert exactly one build happened.
        trace.traceCallersJson("com.example.Foo", "m", null, null, tmp, false, false);
        symbols.findSymbolsJson("Foo", null, tmp, false, false, 100);

        // Verify exactly one cache entry was created for (tmp, includeTests=false).
        java.lang.reflect.Field f = ProjectIndexCache.class.getDeclaredField("entries");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, Object> entries = (java.util.Map<Object, Object>) f.get(cache);
        assertEquals(1, entries.size(),
                "expected one cache entry after multiple loadOrRebuild calls, got: "
                        + entries.keySet());
    }

    @Test
    void concurrentLoadsForDifferentProjectsBothSucceed(@TempDir Path tmp) throws Exception {
        Path a = newMinimalProject(tmp, "a");
        Path b = newMinimalProject(tmp, "b");
        ProjectIndexCache cache = new ProjectIndexCache();
        ProjectIndex[] ra = new ProjectIndex[1];
        ProjectIndex[] rb = new ProjectIndex[1];
        Thread t1 = new Thread(() -> ra[0] = cache.loadOrRebuild(a, false, false));
        Thread t2 = new Thread(() -> rb[0] = cache.loadOrRebuild(b, false, false));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertNotNull(ra[0], "project a should index");
        assertNotNull(rb[0], "project b should index");
        assertNotSame(ra[0], rb[0], "different projects must produce different indices");
    }

    @Test
    void concurrentSameKeyReturnsSameInstance(@TempDir Path tmp) throws Exception {
        Path a = newMinimalProject(tmp, "a");
        ProjectIndexCache cache = new ProjectIndexCache();
        ProjectIndex[] r = new ProjectIndex[2];
        Thread t1 = new Thread(() -> r[0] = cache.loadOrRebuild(a, false, false));
        Thread t2 = new Thread(() -> r[1] = cache.loadOrRebuild(a, false, false));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertSame(r[0], r[1], "the same project must share one index instance");
    }

    private static Path newMinimalProject(Path tmp, String name) throws Exception {
        Path root = Files.createDirectories(tmp.resolve(name));
        Path srcDir = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Foo.java"),
                "package com.example; public class Foo { public void m() {} }");
        Files.writeString(root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>com.example</groupId><artifactId>" + name + "</artifactId>"
                        + "<version>0.0.1-SNAPSHOT</version></project>");
        return root;
    }
}