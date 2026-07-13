# trace_callers line 修复 + 性能优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `trace_callers` so (1) the `line` field points at the method signature, not the preceding Javadoc, and (2) BFS on widely-called methods completes in seconds rather than 10+ minutes.

**Architecture:** Three independent code changes plus their tests. (1) `JdtIndexer` records the method name's source position (a 1-line change to `cuLine(node)` → `cuLine(node.getName())`), matching the existing pattern in `recordTypeSymbol`. (2) `CallNode` gains two new marker fields (`fanoutTruncated`, `hiddenCallerCount`) with a factory; `CallChainAnalyzer`'s BFS inner loop is rewritten to collect callers into a `LinkedHashSet`, stop at `MAX_CALLERS_PER_FRAME = 500`, and attach a marker child. (3) `ProjectIndex` gains `callersOfSet(MethodKey)` returning the live index set; the BFS switches to it. `callersOf` keeps its `List` return — `EdgeCaseTest.java:968` explicitly tests that contract.

**Tech Stack:** Java 17, JUnit 5 (`@TempDir`, `@Test`), Eclipse JDT (AST parsing). Build: `mvn test`.

## Global Constraints

- All Java code follows the existing style: 4-space indent, package `com.codescope`, javadoc on every public/protected method that is part of the API surface.
- New tests use `@TempDir Path tmp` and write inline sources via `Files.writeString` + `Files.createDirectories`, matching `EdgeCaseTest`'s style.
- Do not break any of the 181 existing tests.
- `EdgeCaseTest.java:968` asserts `callersOf` returns a `List` (defensive copy). Preserve that contract. New method `callersOfSet` returns the live `Set`.
- Commit messages: `type(scope): subject` (e.g. `fix(jdtindexer): record method line at name token`).

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/codescope/JdtIndexer.java` | Fix #1: use `cuLine(node.getName())` for method declaration line. |
| `src/main/java/com/codescope/CallNode.java` | Fix #2: add `fanoutTruncated`, `hiddenCallerCount` fields; add `fanoutMarker` factory; extend `toJson()` to emit `truncatedCallers`. |
| `src/main/java/com/codescope/CallChainAnalyzer.java` | Fix #2: BFS loop refactor + `MAX_CALLERS_PER_FRAME` constant. |
| `src/main/java/com/codescope/ProjectIndex.java` | Fix #3: add `callersOfSet(MethodKey)` returning the live `Set`. |
| `src/test/java/com/codescope/MethodLineFixTest.java` | NEW — Fix #1 test. |
| `src/test/java/com/codescope/CallNodeTruncationTest.java` | NEW — Fix #2 `CallNode` serialization test. |
| `src/test/java/com/codescope/CallChainFanoutCapTest.java` | NEW — Fix #2 BFS behavior test. |
| `src/test/java/com/codescope/ProjectIndexCallersOfSetTest.java` | NEW — Fix #3 test. |

---

## Task 1: Method declaration line points at name token (Fix #1)

**Files:**
- Modify: `src/main/java/com/codescope/JdtIndexer.java:555`
- Test: `src/test/java/com/codescope/MethodLineFixTest.java` (new)

**Interfaces:**
- Consumes: existing `private static int cuLine(ASTNode n)` helper in `JdtIndexer.java` (maps `n.getStartPosition()` via `cu.getLineNumber(pos)`).
- Produces: callers unchanged — `index.putDeclaration(callerKey, new ProjectIndex.SourceLoc(file, line))` and `recordMethodSymbol(callerClass, methodName, paramTypes, line, kind)` both consume the fixed `line` variable.

### Step 1: Write the failing test

Create `src/test/java/com/codescope/MethodLineFixTest.java`:

```java
package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #5: trace_callers `line` pointed at the Javadoc/annotation
 * preamble instead of the method signature, so the user couldn't
 * jump-to-definition reliably. After the fix, line is the source
 * position of the method name token.
 */
class MethodLineFixTest {

    @Test
    void methodLinePointsAtSignatureNotJavadoc(@TempDir Path tmp) throws IOException {
        // Source has:
        //   line 1: package
        //   line 2: public class ...
        //   line 3: blank
        //   line 4: /** Javadoc ... */
        //   line 5:  * more ... */
        //   line 6: @SuppressWarnings("all")
        //   line 7: public int compute() { return 1; }   <-- name token
        //   line 8: }
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Foo.java"),
                "package com.example;\n"
              + "public class Foo {\n"
              + "\n"
              + "/** Javadoc for compute. */\n"
              + " * more lines\n"
              + "@SuppressWarnings(\"all\")\n"
              + "    public int compute() { return 1; }\n"
              + "}\n");
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Foo.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey compute = new MethodKey("com.example.Foo", "compute", 0, List.of());
        ProjectIndex.SourceLoc loc = index.declarationOf(compute);
        assertEquals(7, loc.line(),
                "declaration line should be the method signature line (the line containing 'compute'), "
              + "not the Javadoc start. Got: " + loc);
    }
}
```

### Step 2: Run test to verify it fails

Run: `mvn test -Dtest=MethodLineFixTest -q`
Expected: FAIL with message containing "should be the method signature line" and an actual value of `4` (the Javadoc `/**` line).

### Step 3: Fix the bug

In `src/main/java/com/codescope/JdtIndexer.java`, change line 555 from:

```java
            int line = cuLine(node);
```

to:

```java
            int line = cuLine(node.getName());
```

`MethodDeclaration.getName()` returns the `SimpleName` node for the method identifier. Its `getStartPosition()` is the offset of the `compute` token — past the Javadoc, past the annotation, past the modifiers. `cuLine(SimpleName)` then maps that offset to a 1-based line number via the existing `CompilationUnit` line table.

This is the same pattern already used for type declarations at lines 1030-1032 (`cuLine(atd.getName())`).

### Step 4: Run test to verify it passes

Run: `mvn test -Dtest=MethodLineFixTest -q`
Expected: PASS

### Step 5: Commit

```bash
git add src/main/java/com/codescope/JdtIndexer.java src/test/java/com/codescope/MethodLineFixTest.java
git commit -m "fix(jdtindexer): record method declaration line at name token"
```

---

## Task 2: Add fan-out marker fields to CallNode (Fix #2 part A)

**Files:**
- Modify: `src/main/java/com/codescope/CallNode.java`
- Test: `src/test/java/com/codescope/CallNodeTruncationTest.java` (new)

**Interfaces:**
- Consumes: existing `cycle`, `truncated` (depth-cap) marker fields and `cycleMarker`/`depthMarker` factories.
- Produces: new `fanoutTruncated` (boolean, default `false`) and `hiddenCallerCount` (int, default `0`) fields. New `fanoutMarker(className, methodName, arity, hiddenCount)` factory. `toJson()` emits `truncatedCallers: N` when `fanoutTruncated` is true.

### Step 1: Write the failing test

Create `src/test/java/com/codescope/CallNodeTruncationTest.java`:

```java
package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the new fan-out marker fields on {@link CallNode}: a marker
 * carries `truncatedCallers: N` in its JSON and the boolean flag round-trips
 * to JSON for a regular node that exceeded the fan-out cap.
 */
class CallNodeTruncationTest {

    @Test
    void fanoutMarkerJsonIncludesTruncatedCallers() {
        CallNode marker = CallNode.fanoutMarker("com.example.Wide", "hot", 0, 1234);
        Map<String, Object> json = marker.toJson();
        assertEquals("com.example.Wide", json.get("class"));
        assertEquals("hot", json.get("method"));
        assertEquals(0, json.get("arity"));
        assertEquals(1234, json.get("truncatedCallers"),
                "fan-out marker must report how many callers were dropped");
        // Sanity: it does not also emit a depth-truncation flag.
        assertFalse(json.containsKey("truncated"));
        assertFalse(json.containsKey("cycle"));
    }

    @Test
    void regularNodeOmitsTruncatedCallers() {
        // A regular node (no marker) should not emit `truncatedCallers` at all.
        CallNode node = new CallNode("a.B", "m", 0, "B.java", 10);
        Map<String, Object> json = node.toJson();
        assertFalse(json.containsKey("truncatedCallers"));
    }
}
```

### Step 2: Run test to verify it fails

Run: `mvn test -Dtest=CallNodeTruncationTest -q`
Expected: FAIL with "cannot find symbol: method fanoutMarker".

### Step 3: Extend CallNode

In `src/main/java/com/codescope/CallNode.java`, make the following changes:

1. Add two `public final` fields after `truncated`:

```java
    public final boolean fanoutTruncated;  // true if this branch was cut at MAX_CALLERS_PER_FRAME
    public final int hiddenCallerCount;    // number of callers omitted due to fan-out cap
```

2. Update the private 7-arg constructor to take the two new fields at the end:

```java
    private CallNode(String className, String methodName, int arity,
                     String file, int line, boolean cycle, boolean truncated,
                     boolean fanoutTruncated, int hiddenCallerCount) {
        this.className = className;
        this.methodName = methodName;
        this.arity = arity;
        this.signature = className + "#" + methodName + "/" + arity;
        this.file = file;
        this.line = line;
        this.cycle = cycle;
        this.truncated = truncated;
        this.fanoutTruncated = fanoutTruncated;
        this.hiddenCallerCount = hiddenCallerCount;
    }
```

3. Update both public constructors to forward `false, 0` for the new fields:

```java
    public CallNode(String className, String methodName, int arity, String file, int line) {
        this(className, methodName, arity, file, line, false, false, false, 0);
    }

    public CallNode(String className, String methodName, int arity,
                    String file, int line, boolean cycle) {
        this(className, methodName, arity, file, line, cycle, false, false, 0);
    }
```

4. Update the existing marker factories to pass `false, 0`:

```java
    public static CallNode cycleMarker(String className, String methodName, int arity) {
        return new CallNode(className, methodName, arity, null, 0, true, false, false, 0);
    }

    public static CallNode depthMarker(String className, String methodName, int arity) {
        return new CallNode(className, methodName, arity, null, 0, false, true, false, 0);
    }
```

5. Add the new factory:

```java
    /**
     * Builds a fan-out cap marker for a method whose caller set was
     * truncated at {@code MAX_CALLERS_PER_FRAME}. Distinct from
     * {@link #depthMarker} so callers can tell "we stopped at the
     * depth limit" from "we stopped because this method has too many
     * direct callers to enumerate in full".
     *
     * @param hiddenCount number of callers that were discovered but
     *                    omitted from the tree
     */
    public static CallNode fanoutMarker(String className, String methodName,
                                        int arity, int hiddenCount) {
        return new CallNode(className, methodName, arity, null, 0, false, false, true, hiddenCount);
    }
```

6. Extend `toJson()` so it emits `truncatedCallers: N` when `fanoutTruncated` is true. Add immediately after the existing `if (f.node.truncated) f.map.put("truncated", true);` line (line 88):

```java
            if (f.node.fanoutTruncated) f.map.put("truncatedCallers", f.node.hiddenCallerCount);
```

### Step 4: Run test to verify it passes

Run: `mvn test -Dtest=CallNodeTruncationTest -q`
Expected: PASS

### Step 5: Run existing CallNode-related tests for regression

Run: `mvn test -Dtest='CallChainAnalyzerTest,TraceCallersServiceTest,EdgeCaseTest' -q`
Expected: PASS for all (the new fields default to `false, 0`, so existing `CallNode(...)` callsites are unaffected).

### Step 6: Commit

```bash
git add src/main/java/com/codescope/CallNode.java src/test/java/com/codescope/CallNodeTruncationTest.java
git commit -m "feat(callnode): add fan-out marker fields and factory"
```

---

## Task 3: Add `callersOfSet` to ProjectIndex (Fix #3)

**Files:**
- Modify: `src/main/java/com/codescope/ProjectIndex.java` (after line 360, the existing `callersOf` method)
- Test: `src/test/java/com/codescope/ProjectIndexCallersOfSetTest.java` (new)

**Interfaces:**
- Consumes: existing `private final Map<MethodKey, Set<MethodKey>> calls` (each value is a `LinkedHashSet` populated by `recordInvocation`).
- Produces: `public Set<MethodKey> callersOfSet(MethodKey target)` — returns the live `Set` from the `calls` map, or `Set.of()` if absent. No copy.

### Step 1: Write the failing test

Create `src/test/java/com/codescope/ProjectIndexCallersOfSetTest.java`:

```java
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
```

### Step 2: Run test to verify it fails

Run: `mvn test -Dtest=ProjectIndexCallersOfSetTest -q`
Expected: FAIL with "cannot find symbol: method callersOfSet".

### Step 3: Add the method to ProjectIndex

In `src/main/java/com/codescope/ProjectIndex.java`, immediately after the existing `callersOf` method (line 360) and before the `callSitesOf` javadoc, insert:

```java
    /**
     * Returns the raw set of callers for {@code target} without copying.
     *
     * <p>Intended for single-threaded BFS in {@link CallChainAnalyzer}
     * after indexing is complete — the returned set is the live index
     * entry, not a defensive copy. NOT a substitute for
     * {@link #callersOf} in concurrent contexts: callers that mutate
     * the returned set will corrupt the index. The BFS path is safe
     * because {@code recordInvocation} only runs during
     * {@link JdtIndexer#parseFile} on virtual threads, and the BFS
     * entry point in {@code TraceCallersService.traceCallersJson}
     * runs serially after indexing finishes.
     */
    public Set<MethodKey> callersOfSet(MethodKey target) {
        Set<MethodKey> set = calls.get(target);
        return set == null ? Set.of() : set;
    }
```

### Step 4: Run test to verify it passes

Run: `mvn test -Dtest=ProjectIndexCallersOfSetTest -q`
Expected: PASS

### Step 5: Commit

```bash
git add src/main/java/com/codescope/ProjectIndex.java src/test/java/com/codescope/ProjectIndexCallersOfSetTest.java
git commit -m "feat(projectindex): add callersOfSet for direct BFS iteration"
```

---

## Task 4: Refactor BFS to use callersOfSet + per-frame fan-out cap (Fix #2 part B)

**Files:**
- Modify: `src/main/java/com/codescope/CallChainAnalyzer.java` (lines 47-152, the `traceCallers` method body and surrounding field block)
- Test: `src/test/java/com/codescope/CallChainFanoutCapTest.java` (new)

**Interfaces:**
- Consumes: `MAX_NODES` (existing, `50_000`), `MAX_DEPTH` (existing, `500`). `index.callersOfSet(MethodKey)` from Task 3. `CallNode.fanoutMarker(...)` from Task 2. `LinkedHashSet<MethodKey>` (preserves insertion order so the tree output is stable).
- Produces: the per-frame `MAX_CALLERS_PER_FRAME` constant (default `500`). A `truncatedCallers: N` marker child on any frame that exceeded the cap.

### Step 1: Write the failing test

Create `src/test/java/com/codescope/CallChainFanoutCapTest.java`:

```java
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
        // Direct caller cap is MAX_CALLERS_PER_FRAME (500).
        assertTrue(callers.size() <= 500,
                "expected at most 500 caller children after fan-out cap, got " + callers.size());
        // Last child should be the fan-out marker.
        Map<String, Object> tail = callers.get(callers.size() - 1);
        assertTrue(tail.containsKey("truncatedCallers"),
                "fan-out marker should be the last child; got keys: " + tail.keySet());
        int hidden = (int) tail.get("truncatedCallers");
        assertEquals(1200 - (callers.size() - 1), hidden,
                "truncatedCallers should equal the number of callers omitted from the visible list");
    }
}
```

### Step 2: Run test to verify it fails

Run: `mvn test -Dtest=CallChainFanoutCapTest -q`
Expected: FAIL with `expected at most 500 caller children after fan-out cap, got 1200` (the BFS still enumerates every caller because the cap isn't wired in yet). May also take noticeably long to fail, but the assertion fires immediately after BFS completes.

### Step 3: Add the MAX_CALLERS_PER_FRAME constant

In `src/main/java/com/codescope/CallChainAnalyzer.java`, immediately after the existing `private static final int MAX_DEPTH = 500;` (line 164), add:

```java
    /**
     * Safety cap on the number of direct callers expanded per BFS frame.
     * For methods on widely-implemented interfaces, {@code relatedMethods}
     * can return 100+ keys and each can have 1000+ callers, giving
     * 10^5-10^6 iterations per frame — multiply by MAX_NODES frames and
     * the search takes 10+ minutes. Capping at 500 keeps a single frame
     * bounded and the total BFS in seconds; the dropped callers are
     * reported via a {@code fanoutMarker} child carrying the count.
     */
    private static final int MAX_CALLERS_PER_FRAME = 500;
```

### Step 4: Add the new import

At the top of `CallChainAnalyzer.java`, add `LinkedHashSet` to the imports (the existing imports are `ArrayDeque, Deque, HashSet, List, Set`). The `java.util` line becomes:

```java
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
```

### Step 5: Refactor the BFS inner loop

Replace the entire body of the `while (!queue.isEmpty())` loop's inner block (lines 92-132 in the current file) with the version below. Concretely, replace:

```java
            for (MethodKey relatedKey : index.relatedMethods(f.key)) {
                for (MethodKey caller : index.callersOf(relatedKey)) {
                    if (f.ancestors.contains(caller)) {
                        // True back-edge on the current path -> cycle marker.
                        f.node.addChild(CallNode.cycleMarker(
                                caller.declaringClass, caller.methodName, caller.arity));
                        continue;
                    }
                    // Direct-caller dedup ONLY applies at the root level
                    // (depth 1, isRoot=true) when multiple seeds share the
                    // same caller. Below the root each path has its own
                    // ancestors and we want the full subtree for diamonds.
                    if (f.isRoot && !directCallersSeen.add(caller)) continue;
                    // Per-frame dedup so the same caller discovered via
                    // multiple related keys (e.g. M1 and M2 in
                    // relatedMethods) doesn't show up twice as a child
                    // of this frame.
                    if (!f.localCallersSeen.add(caller)) continue;

                    ProjectIndex.SourceLoc loc = index.declarationOf(caller);
                    CallNode child = new CallNode(
                            caller.declaringClass, caller.methodName, caller.arity,
                            loc != null ? loc.file() : null,
                            loc != null ? loc.line() : 0);
                    f.node.addChild(child);
                    // Diamond: same method reached via another path is a real
                    // caller on this branch. We add `caller` to the ancestors of
                    // *its* descendants, not to its own ancestors.
                    Set<MethodKey> childAncestors = new HashSet<>(f.ancestors.size() + 1);
                    childAncestors.add(caller);
                    childAncestors.addAll(f.ancestors);
                    queue.addLast(new PathFrame(caller, child, childAncestors, f.depth + 1, false));
                    nodes++;
                    callerCount++;
                    if (nodes > MAX_NODES) {
                        return new Result(root, true,
                                "Truncated at " + MAX_NODES + " nodes to prevent runaway expansion. "
                                        + "There may be a deeply-recursive or hot method in the chain.");
                    }
                }
            }
```

with:

```java
            // Per-frame fan-out: collect into a single ordered set so the
            // visible children are stable and the cap is enforced on the
            // union, not per relatedKey.
            LinkedHashSet<MethodKey> collected = new LinkedHashSet<>();
            int hidden = 0;
            outer:
            for (MethodKey relatedKey : index.relatedMethods(f.key)) {
                for (MethodKey caller : index.callersOfSet(relatedKey)) {
                    // Cycle / dedup decisions only count toward `collected`
                    // when they would have produced a visible child. A
                    // cycle marker or a duplicate at this frame should
                    // still consume a slot from the cap — otherwise a
                    // pathological frame could loop indefinitely on
                    // duplicates without ever hitting the cap.
                    if (collected.size() >= MAX_CALLERS_PER_FRAME) {
                        hidden++;
                        continue;
                    }
                    if (f.ancestors.contains(caller)) {
                        // True back-edge on the current path -> cycle marker.
                        f.node.addChild(CallNode.cycleMarker(
                                caller.declaringClass, caller.methodName, caller.arity));
                        continue;
                    }
                    // Direct-caller dedup ONLY applies at the root level
                    // (depth 1, isRoot=true) when multiple seeds share the
                    // same caller. Below the root each path has its own
                    // ancestors and we want the full subtree for diamonds.
                    if (f.isRoot && !directCallersSeen.add(caller)) continue;
                    // Per-frame dedup so the same caller discovered via
                    // multiple related keys (e.g. M1 and M2 in
                    // relatedMethods) doesn't show up twice as a child
                    // of this frame.
                    if (!f.localCallersSeen.add(caller)) continue;
                    if (!collected.add(caller)) continue;

                    ProjectIndex.SourceLoc loc = index.declarationOf(caller);
                    CallNode child = new CallNode(
                            caller.declaringClass, caller.methodName, caller.arity,
                            loc != null ? loc.file() : null,
                            loc != null ? loc.line() : 0);
                    f.node.addChild(child);
                    // Diamond: same method reached via another path is a real
                    // caller on this branch. We add `caller` to the ancestors of
                    // *its* descendants, not to its own ancestors.
                    Set<MethodKey> childAncestors = new HashSet<>(f.ancestors.size() + 1);
                    childAncestors.add(caller);
                    childAncestors.addAll(f.ancestors);
                    queue.addLast(new PathFrame(caller, child, childAncestors, f.depth + 1, false));
                    nodes++;
                    callerCount++;
                    if (nodes > MAX_NODES) {
                        return new Result(root, true,
                                "Truncated at " + MAX_NODES + " nodes to prevent runaway expansion. "
                                        + "There may be a deeply-recursive or hot method in the chain.");
                    }
                }
            }
            if (hidden > 0) {
                f.node.addChild(CallNode.fanoutMarker(
                        f.key.declaringClass, f.key.methodName, f.key.arity, hidden));
            }
```

The break label `outer:` is **not actually used** in this version (the inner `continue` is sufficient). It is included only to mark the boundary of the cap check. If the code style review prefers a label only when used, drop the `outer:` line; the logic is identical without it.

### Step 6: Run test to verify it passes

Run: `mvn test -Dtest=CallChainFanoutCapTest -q`
Expected: PASS (BFS completes well under 5s, ≤ 500 caller children, marker has correct `truncatedCallers`).

### Step 7: Run regression on related tests

Run: `mvn test -Dtest='CallChainAnalyzerTest,TraceCallersServiceTest,FindCallSitesServiceTest,EdgeCaseTest' -q`
Expected: PASS for all (cap doesn't fire for the smaller fixture trees).

### Step 8: Commit

```bash
git add src/main/java/com/codescope/CallChainAnalyzer.java src/test/java/com/codescope/CallChainFanoutCapTest.java
git commit -m "perf(tracecallers): cap per-frame fan-out and drop List.copyOf overhead"
```

---

## Task 5: Full regression

**Files:** none (validation only)

### Step 1: Run the full test suite

Run: `mvn test -q`
Expected: 185 tests pass (181 existing + 4 new: `MethodLineFixTest`, `CallNodeTruncationTest`, `ProjectIndexCallersOfSetTest`, `CallChainFanoutCapTest`).

If any existing test fails, the most likely culprit is:
- A test that asserts on the exact number of `callers` children for a target that was previously unbounded — re-check whether the test was relying on a non-capped expansion of a wide caller set (none observed in the existing fixture, but worth a glance at `CallChainAnalyzerTest` results).
- A test that asserts on `line` of a method declaration — the `MethodLineFixTest` is the only such test we just added; if an existing test starts failing on a `line` value, it's because the existing test was asserting the buggy value (Javadoc line) and the fix correctly moved it to the signature line. Update the assertion in that case.

### Step 2: Verify the count

Run: `mvn test -q 2>&1 | tail -5`
Expected: `Tests run: 185, Failures: 0, Errors: 0, Skipped: 0`.

### Step 3: Commit (only if any test was updated)

If no test files were modified, skip this commit. Otherwise:

```bash
git add -u src/test/
git commit -m "test: update assertions affected by line fix and fan-out cap"
```

---

## Self-Review Checklist

- [x] **Spec coverage:**
  - Issue #5 (line fix) → Task 1.
  - Issue #6 (performance) → Tasks 2, 3, 4 (CallNode fields, callersOfSet, BFS refactor).
  - Per-frame cap (500) → Task 4.
  - `callersOf` `List` contract preserved → Task 3 (adds new method, doesn't change existing).
- [x] **Placeholder scan:** Every step shows exact code, exact commands, exact expected output. No TBD/TODO.
- [x] **Type consistency:** `CallNode.fanoutMarker(String, String, int, int)` defined in Task 2, used in Task 4. `ProjectIndex.callersOfSet(MethodKey)` defined in Task 3, used in Task 4. `MAX_CALLERS_PER_FRAME` defined in Task 4, used in Task 4.
- [x] **Test scope:** 4 new test files, each test focuses on one fix. The fan-out test is intentionally large (1200 callers) so the assertion on `< 5000ms` is meaningful; the build is small enough that test runtime stays under 30s total.
