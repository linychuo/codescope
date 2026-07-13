package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fix #2: BFS over a method that has 1000+ direct callers must
 * complete in seconds and produce a tree with at most
 * {@code MAX_CALLERS_PER_FRAME} caller children per frame, plus a
 * fan-out marker carrying the dropped count.
 */
class CallChainFanoutCapTest {

    @Test
    void bfsTruncatesAtFanOutCapAndReportsHiddenCount(@TempDir Path tmp) throws IOException {
        // One target method, called from 1200 distinct synthetic callers.
        // Source layout under src/main/java/com/example/:
        //   Target.java
        //   Caller1.java .. Caller1200.java
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Target.java"),
                "package com.example;\n"
              + "public class Target {\n"
              + "    public void hot() {}\n"
              + "}\n");
        // 1200 callers each call Target#hot once.
        IntStream.rangeClosed(1, 1200).forEach(i -> {
            try {
                Files.writeString(srcDir.resolve("Caller" + i + ".java"),
                        "package com.example;\n"
                      + "public class Caller" + i + " {\n"
                      + "    void use() { new Target().hot(); }\n"
                      + "}\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        List<Path> sources = Files.list(srcDir).sorted().collect(Collectors.toList());
        long startMs = System.currentTimeMillis();
        ProjectIndex index = new JdtIndexer().build(
                sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp);
        long indexMs = System.currentTimeMillis() - startMs;

        MethodKey hot = new MethodKey("com.example.Target", "hot", 0, List.of());
        startMs = System.currentTimeMillis();
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(index, hot);
        long bfsMs = System.currentTimeMillis() - startMs;

        // Hard bound on BFS time. The old code was O(R*C) = 10+ minutes.
        // After the fix, BFS on a 1200-caller target should be well under 5s.
        assertTrue(bfsMs < 5_000,
                "BFS on a 1200-caller method took " + bfsMs + "ms; expected < 5000ms");
        // Sanity: indexing itself shouldn't blow up either.
        assertTrue(indexMs < 60_000, "indexing took " + indexMs + "ms");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers =
                (List<Map<String, Object>>) r.root().toJson().get("callers");
        assertNotNull(callers);
        // Visible (non-marker) caller children must be at most MAX_CALLERS_PER_FRAME.
        long markerCount = callers.stream().filter(m -> m.containsKey("truncatedCallers")).count();
        assertEquals(1, markerCount,
                "expected exactly one fan-out marker child; got " + markerCount);
        assertTrue(callers.size() <= 501,
                "expected at most 500 callers + 1 marker = 501 children, got " + callers.size());
        // Last child should be the fan-out marker.
        Map<String, Object> tail = callers.get(callers.size() - 1);
        assertTrue(tail.containsKey("truncatedCallers"),
                "fan-out marker should be the last child; got keys: " + tail.keySet());
        int hidden = (int) tail.get("truncatedCallers");
        assertEquals(1200 - (callers.size() - 1), hidden,
                "truncatedCallers should equal the number of callers omitted from the visible list");
    }
}
