package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that when {@link CallChainAnalyzer} bails out at the node cap
 * mid-BFS, the partial tree returned via {@link CallNode#toJson()} carries
 * a structured {@code nodeCapTruncated: true} flag on the root. Without
 * this flag, consumers that ignore the analyzer's textual message would
 * silently treat a partial tree as the complete answer — a correctness
 * trap that was easy to miss in code review.
 *
 * <p>Uses the package-private {@code CallChainAnalyzer(int)} constructor
 * to dial the cap to a small number so the fixture doesn't need 50k+
 * real method declarations.
 */
class CallChainNodeCapTruncationTest {

    @Test
    void rootCarriesNodeCapTruncatedWhenBfsHitsTheCap(@TempDir Path tmp) throws IOException {
        // Linear chain of 7 methods where each calls the one BEFORE
        // it in the list: G calls F, F calls E, ..., B calls A. A is
        // the trace target and has no callers-of-callers — it sits at
        // the bottom of the chain. Trace from A expands through B..G.
        // With maxNodes=5 the BFS reaches A, B, C, D, E (5 nodes) and
        // bails out the instant it tries to add the 6th (F). The
        // returned tree is therefore partial but visibly looks
        // complete to anything reading just `root.toJson()` — so the
        // analyzer MUST mark the root so consumers can tell.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        String[] methodNames = {"a", "b", "c", "d", "e", "f", "g"};
        for (int i = 0; i < methodNames.length; i++) {
            String myName = methodNames[i];
            StringBuilder body = new StringBuilder("package com.example;\n"
                    + "public class " + Character.toUpperCase(myName.charAt(0)) + myName.substring(1) + " {\n"
                    + "    public void entry() {\n");
            // methodNames[i] invokes methodNames[i-1]: G->F, F->E, ..., B->A.
            // methodNames[0]=A is the trace target with no outgoing call.
            if (i > 0) {
                String calleeName = methodNames[i - 1];
                body.append("        new ")
                        .append(Character.toUpperCase(calleeName.charAt(0)))
                        .append(calleeName.substring(1))
                        .append("().entry();\n");
            }
            body.append("    }\n}\n");
            String fileName = Character.toUpperCase(myName.charAt(0))
                    + myName.substring(1) + ".java";
            Files.writeString(srcDir.resolve(fileName), body.toString());
        }

        List<Path> sources = IntStream.range(0, methodNames.length)
                .mapToObj(i -> srcDir.resolve(
                        Character.toUpperCase(methodNames[i].charAt(0))
                                + methodNames[i].substring(1) + ".java"))
                .toList();
        ProjectIndex index = new JdtIndexer().build(
                sources, List.of(), List.of(srcDir.getParent().getParent().toString()), tmp);

        // maxNodes=5: a chain of 7 will hit the cap after expanding
        // through the fifth caller (E). The BFS adds F as the sixth
        // node and bails immediately, leaving tree A→B→C→D→E→F behind.
        MethodKey a = new MethodKey("com.example.A", "entry", 0, List.of());
        CallChainAnalyzer.Result r = new CallChainAnalyzer(5).traceCallers(index, a);

        Map<String, Object> rootJson = r.root().toJson();
        assertEquals(Boolean.TRUE, rootJson.get("nodeCapTruncated"),
                "root must carry nodeCapTruncated=true after a MAX_NODES bailout so a "
                        + "consumer reading only the tree (and ignoring the textual message) "
                        + "sees that the tree is partial");

        // The textual message must also reflect the cap so anyone
        // reading `message` (CLI logs, debug tooling) still has a
        // human-readable signal.
        assertTrue(r.message().contains("Truncated"),
                "message should still surface the truncation reason: " + r.message());
    }

    @Test
    void rootOmitsNodeCapTruncatedWhenBfsCompletes(@TempDir Path tmp) throws IOException {
        // Same chain as above, but maxNodes=50_000 — the BFS completes
        // the whole graph and the marker must NOT be set.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        String[] methodNames = {"a", "b", "c", "d", "e", "f", "g"};
        for (int i = 0; i < methodNames.length; i++) {
            String myName = methodNames[i];
            StringBuilder body = new StringBuilder("package com.example;\n"
                    + "public class " + Character.toUpperCase(myName.charAt(0)) + myName.substring(1) + " {\n"
                    + "    public void entry() {\n");
            if (i > 0) {
                String calleeName = methodNames[i - 1];
                body.append("        new ")
                        .append(Character.toUpperCase(calleeName.charAt(0)))
                        .append(calleeName.substring(1))
                        .append("().entry();\n");
            }
            body.append("    }\n}\n");
            String fileName = Character.toUpperCase(myName.charAt(0))
                    + myName.substring(1) + ".java";
            Files.writeString(srcDir.resolve(fileName), body.toString());
        }

        List<Path> sources = IntStream.range(0, methodNames.length)
                .mapToObj(i -> srcDir.resolve(
                        Character.toUpperCase(methodNames[i].charAt(0))
                                + methodNames[i].substring(1) + ".java"))
                .toList();
        ProjectIndex index = new JdtIndexer().build(
                sources, List.of(), List.of(srcDir.getParent().getParent().toString()), tmp);

        MethodKey a = new MethodKey("com.example.A", "entry", 0, List.of());
        CallChainAnalyzer.Result r = new CallChainAnalyzer(50_000).traceCallers(index, a);

        Map<String, Object> rootJson = r.root().toJson();
        assertFalse(rootJson.containsKey("nodeCapTruncated"),
                "root must omit nodeCapTruncated on a complete tree");
        assertFalse(r.message().contains("Truncated"),
                "message should not mention truncation when BFS completes");
    }
}
