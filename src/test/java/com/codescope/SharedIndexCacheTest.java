package com.codescope;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}