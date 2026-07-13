package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ProjectIndex#callersOfSet} returns the same set of
 * callers as {@link ProjectIndex#callersOf}, just without the defensive
 * copy. The BFS in {@link CallChainAnalyzer} uses the new method after
 * indexing is complete (single-threaded read path), so it can iterate
 * the live index set directly.
 */
class ProjectIndexCallersOfSetTest {

    @Test
    void callersOfSetReturnsSameContentAsCallersOf(@TempDir Path tmp) throws IOException {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Callee.java"),
                "package com.example;\n"
              + "public class Callee { public void target() {} }\n");
        Files.writeString(srcDir.resolve("CallerA.java"),
                "package com.example;\n"
              + "public class CallerA { void use() { new Callee().target(); } }\n");
        Files.writeString(srcDir.resolve("CallerB.java"),
                "package com.example;\n"
              + "public class CallerB { void use() { new Callee().target(); } }\n");

        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Callee.java"),
                        srcDir.resolve("CallerA.java"),
                        srcDir.resolve("CallerB.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey target = new MethodKey("com.example.Callee", "target", 0, List.of());
        List<MethodKey> fromList = index.callersOf(target);
        Set<MethodKey> fromSet = index.callersOfSet(target);

        assertEquals(new HashSet<>(fromList), fromSet,
                "callersOfSet should contain exactly the same MethodKeys as callersOf");
        assertEquals(2, fromSet.size(),
                "expected CallerA and CallerB as callers of Callee#target");
    }

    @Test
    void callersOfSetReturnsEmptySetForUnknownTarget() {
        // No indexing happened; the target key is not in the index.
        ProjectIndex index = new ProjectIndex();
        MethodKey ghost = new MethodKey("com.example.Ghost", "nope", 0, List.of());
        Set<MethodKey> result = index.callersOfSet(ghost);
        assertNotNull(result, "callersOfSet must never return null");
        assertTrue(result.isEmpty(), "unknown target should yield empty set");
    }
}
