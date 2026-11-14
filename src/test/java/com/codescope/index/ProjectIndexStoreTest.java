package com.codescope.index;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.model.MethodKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The disk cache behind the CLI's one-shot invocations (issue #9
 * follow-up): a built index round-trips through {@link ProjectIndexStore},
 * and the {@link IndexFingerprint} changes when a source changes so a stale
 * cache is never served.
 */
class ProjectIndexStoreTest {

    private static ProjectIndex build(Path srcDir, Path tmp, String... files) throws Exception {
        List<Path> sources = new java.util.ArrayList<>();
        for (String f : files) sources.add(srcDir.resolve(f));
        return new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp);
    }

    @Test
    void indexRoundTripsThroughDisk(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Target.java"), """
                package com.example;
                public class Target { public void leaf() {} }
                """);
        Files.writeString(srcDir.resolve("Caller.java"), """
                package com.example;
                public class Caller { void go() { new Target().leaf(); } }
                """);
        ProjectIndex index = build(srcDir, tmp, "Target.java", "Caller.java");

        Path cacheFile = tmp.resolve("cache/index.ser");
        ProjectIndexStore.save(index, cacheFile);
        ProjectIndex loaded = ProjectIndexStore.load(cacheFile);
        assertNotNull(loaded, "a saved index must load back");

        MethodKey leaf = loaded.resolveTarget("com.example.Target", "leaf");
        assertNotNull(leaf, "the loaded index must still resolve declarations");
        assertFalse(loaded.callersOf(leaf).isEmpty(),
                "the loaded index must still answer caller queries");
        assertEquals(index.callersOf(leaf), loaded.callersOf(leaf));
    }

    @Test
    void missingOrCorruptCacheIsNotServed(@TempDir Path tmp) throws Exception {
        assertNull(ProjectIndexStore.load(tmp.resolve("absent.ser")),
                "a missing cache file must not be served");
        Path corrupt = tmp.resolve("corrupt.ser");
        Files.writeString(corrupt, "not a serialized index");
        assertNull(ProjectIndexStore.load(corrupt),
                "a corrupt cache file must not be served");
    }

    @Test
    void fingerprintChangesWithSourceEdits(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Path target = srcDir.resolve("Target.java");
        Files.writeString(target, """
                package com.example;
                public class Target { public void leaf() {} }
                """);
        String before = IndexFingerprint.compute(tmp, false);
        assertNotNull(before);

        Files.writeString(target, """
                package com.example;
                public class Target { public void leaf() {} public void other() {} }
                """);
        String after = IndexFingerprint.compute(tmp, false);
        assertNotNull(after);
        assertNotEquals(before, after, "a source edit must change the fingerprint");

        assertNotEquals(IndexFingerprint.compute(tmp, false), IndexFingerprint.compute(tmp, true),
                "includeTests must be part of the fingerprint");
    }
}
