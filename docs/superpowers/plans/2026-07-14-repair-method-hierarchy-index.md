# Repair-Method-Hierarchy Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a (methodName, arity) secondary index in `ProjectIndex` and re-point `JdtIndexer.repairMethodHierarchyViaTypeHierarchy`'s inner loop to it, eliminating the O(M²×N) build-phase bottleneck in issue #6.

**Architecture:** Write-through maintenance at `putDeclaration` (mirrors existing `recordHierarchy` / `recordTypeHierarchy` patterns). A new public `methodsWithSignature(name, arity)` lookup method on `ProjectIndex` returns the indexed set in O(1) per signature. The repair loop in `JdtIndexer` swaps its inner `index.knownMethods()` scan for the new lookup; result set and gating logic unchanged.

**Tech Stack:** Java 11, JUnit 5, Maven (existing project). No new dependencies.

## Global Constraints

- Project uses JUnit 5 (`org.junit.jupiter.api.Test`), not JUnit 4
- Test fixture for end-to-end tests: `src/test/resources/fixture-project` (Maven fixture loaded via `ProjectLoader`)
- In-memory ProjectIndex tests use plain `new ProjectIndex()` and call `putDeclaration` / `recordMethodDeclarationModifiers` directly (see `ProjectIndexSubInterfaceTest` / `ProjectIndexCallersOfSetTest` for the pattern)
- Thread-safety pattern for index structures: outer `ConcurrentHashMap`, inner `ConcurrentHashMap.newKeySet()` (see `hierarchy` / `typeHierarchy` in `ProjectIndex.java:75,89`)
- Public API additions must carry javadoc; methods that return live index sets must state "callers must not mutate" and explain why iteration is safe under concurrent writes
- `putDeclaration` uses `putIfAbsent` for first-writer-wins on the declarations map; the new `bySignature` add is naturally idempotent (Set semantics)
- All commits in this plan go to branch `dev`

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/main/java/com/codescope/ProjectIndex.java` | Modify | Add `NameArity` record, `bySignature` field, `methodsWithSignature()` method, extend `putDeclaration` to maintain the index |
| `src/main/java/com/codescope/JdtIndexer.java` | Modify | Repair-loop inner scan → `methodsWithSignature` lookup (1 line of effective change) |
| `src/test/java/com/codescope/ProjectIndexSignatureIndexTest.java` | Create | Direct in-memory tests of `methodsWithSignature` and write-through semantics |

No other files touched. `knownMethods()` / `hierarchy` / `typeHierarchy` / `calls` / `callSites` / `symbols` all stay as-is. `CallChainAnalyzer` and downstream services untouched.

---

## Task 1: ProjectIndex 写时维护 bySignature 索引

**Files:**
- Modify: `src/main/java/com/codescope/ProjectIndex.java:158-160` (extend `putDeclaration`)
- Modify: `src/main/java/com/codescope/ProjectIndex.java:75-89` (add new field in the index field cluster)
- Create: `src/test/java/com/codescope/ProjectIndexSignatureIndexTest.java`

**Interfaces:**
- Consumes: existing `putDeclaration(MethodKey, SourceLoc)` API
- Produces: `public Set<MethodKey> methodsWithSignature(String methodName, int arity)` returning the live indexed set (empty set if no match or null name). New package-private `record NameArity(String name, int arity)`. New private field `Map<NameArity, Set<MethodKey>> bySignature`.

- [ ] **Step 1: 写失败测试**

Create `src/test/java/com/codescope/ProjectIndexSignatureIndexTest.java`:

```java
package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIndexSignatureIndexTest {

    private static MethodKey mk(String cls, String name, int arity) {
        return new MethodKey(cls, name, arity, null);
    }

    @Test
    void methodsWithSignatureReturnsAllDeclarationsWithSameNameAndArity() {
        ProjectIndex idx = new ProjectIndex();
        MethodKey a = mk("com.example.A", "save", 1);
        MethodKey b = mk("com.example.B", "save", 1);
        MethodKey c = mk("com.example.C", "save", 1);
        idx.putDeclaration(a, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(b, new ProjectIndex.SourceLoc("B.java", 20));
        idx.putDeclaration(c, new ProjectIndex.SourceLoc("C.java", 30));

        Set<MethodKey> matches = idx.methodsWithSignature("save", 1);
        assertEquals(3, matches.size());
        assertTrue(matches.contains(a));
        assertTrue(matches.contains(b));
        assertTrue(matches.contains(c));
    }

    @Test
    void methodsWithSignatureDistinguishesByArity() {
        ProjectIndex idx = new ProjectIndex();
        MethodKey zero = mk("com.example.A", "load", 0);
        MethodKey one = mk("com.example.A", "load", 1);
        idx.putDeclaration(zero, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(one, new ProjectIndex.SourceLoc("A.java", 20));

        assertEquals(1, idx.methodsWithSignature("load", 0).size());
        assertEquals(1, idx.methodsWithSignature("load", 1).size());
    }

    @Test
    void methodsWithSignatureOnMissingNameReturnsEmpty() {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        Set<MethodKey> matches = idx.methodsWithSignature("delete", 1);
        assertNotNull(matches, "must return empty set, not null");
        assertTrue(matches.isEmpty());
    }

    @Test
    void methodsWithSignatureOnMissingArityReturnsEmpty() {
        ProjectIndex idx = new ProjectIndex();
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertTrue(idx.methodsWithSignature("save", 2).isEmpty());
    }

    @Test
    void methodsWithSignatureOnNullNameReturnsEmpty() {
        ProjectIndex idx = new ProjectIndex();
        assertNotNull(idx.methodsWithSignature(null, 1));
        assertTrue(idx.methodsWithSignature(null, 1).isEmpty());
    }

    @Test
    void indexIsVisibleImmediatelyAfterPutDeclaration() {
        // Write-through: no separate "build" step required.
        ProjectIndex idx = new ProjectIndex();
        assertTrue(idx.methodsWithSignature("save", 1).isEmpty());
        idx.putDeclaration(mk("com.example.A", "save", 1),
                new ProjectIndex.SourceLoc("A.java", 10));
        assertEquals(1, idx.methodsWithSignature("save", 1).size());
    }

    @Test
    void indexHandlesRedundantPutDeclaration() {
        // putDeclaration uses putIfAbsent; bySignature add is idempotent
        // (Set semantics). Calling putDeclaration twice with the same key
        // must not produce duplicates in the indexed set.
        ProjectIndex idx = new ProjectIndex();
        MethodKey m = mk("com.example.A", "save", 1);
        idx.putDeclaration(m, new ProjectIndex.SourceLoc("A.java", 10));
        idx.putDeclaration(m, new ProjectIndex.SourceLoc("A.java", 99));
        assertEquals(1, idx.methodsWithSignature("save", 1).size());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -q -Dtest=ProjectIndexSignatureIndexTest test`
Expected: COMPILATION FAILURE — `methodsWithSignature` and `NameArity` do not exist.

- [ ] **Step 3: 在 ProjectIndex 加 NameArity record 和 bySignature 字段**

In `src/main/java/com/codescope/ProjectIndex.java`, after the existing index fields block (right after the `typeHierarchy` declaration around line 89), add:

```java
// Signature index: for each (methodName, arity) pair, the set of
// declared MethodKeys in the project with that signature. Lets the
// post-build reverse-hierarchy repair pass find same-named,
// same-arity candidates in O(1) per signature lookup instead of an
// O(N) full-scan of {@link #knownMethods()} per subtype, which was
// the build-phase bottleneck for large projects (issue #6).
// Populated write-through at {@link #putDeclaration}.
private final Map<NameArity, Set<MethodKey>> bySignature = new ConcurrentHashMap<>();

// Composite key for {@link #bySignature}. Package-private so the
// test suite can construct it directly if ever needed; not part of
// the public API.
record NameArity(String name, int arity) {}
```

- [ ] **Step 4: 在 putDeclaration 末尾追加写时维护**

In `src/main/java/com/codescope/ProjectIndex.java`, change the `putDeclaration` method body (currently at line 158-160) to:

```java
public void putDeclaration(MethodKey method, SourceLoc loc) {
    declarations.putIfAbsent(method, loc);
    bySignature
            .computeIfAbsent(new NameArity(method.methodName, method.arity),
                    k -> ConcurrentHashMap.newKeySet())
            .add(method);
}
```

- [ ] **Step 5: 加 methodsWithSignature 公共方法**

In `src/main/java/com/codescope/ProjectIndex.java`, immediately after the `knownMethods()` method (currently at line 403-405), add:

```java
/**
 * Returns the set of declared MethodKeys whose {@code methodName}
 * and {@code arity} match the given signature, across all declaring
 * classes in the project. Returns an empty set if no project
 * declaration matches or {@code methodName} is null.
 *
 * <p>Used by the post-build reverse-hierarchy repair pass to find
 * same-named, same-arity candidates in O(1) per signature lookup
 * (replaces an O(N) full-scan of {@link #knownMethods()} per
 * subtype, which was the build-phase bottleneck for large projects
 * — see issue #6).
 *
 * <p>The returned set is the live index set, not a defensive
 * snapshot. Read-only iteration is safe under the indexer's
 * concurrent writes because the underlying map is a
 * {@link ConcurrentHashMap}. Callers must not mutate the returned
 * set.
 */
public Set<MethodKey> methodsWithSignature(String methodName, int arity) {
    if (methodName == null) return Set.of();
    Set<MethodKey> set = bySignature.get(new NameArity(methodName, arity));
    return set == null ? Set.of() : set;
}
```

- [ ] **Step 6: 跑测试，确认全过**

Run: `mvn -q -Dtest=ProjectIndexSignatureIndexTest test`
Expected: 7 tests pass.

- [ ] **Step 7: 跑回归套件，确保没破坏 putDeclaration 的现有调用方**

Run: `mvn -q test`
Expected: All 181+ existing tests still pass (plus 7 from the new test file).

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/codescope/ProjectIndex.java \
        src/test/java/com/codescope/ProjectIndexSignatureIndexTest.java
git commit -m "$(cat <<'EOF'
feat(projectindex): add (name, arity) signature index via putDeclaration

Adds a write-through secondary index, keyed by (methodName, arity),
populated at putDeclaration. Exposes methodsWithSignature(name, arity)
for O(1) per-signature lookups, which the post-build reverse-hierarchy
repair pass needs to avoid its O(M²×N) full-scan over knownMethods
in large projects (issue #6).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 修复 pass 内层循环改用新 API

**Files:**
- Modify: `src/main/java/com/codescope/JdtIndexer.java:173-182` (replace inner scan with `methodsWithSignature` lookup)

**Interfaces:**
- Consumes: `ProjectIndex.methodsWithSignature(String, int)` from Task 1
- Produces: no new interfaces — repair loop semantics unchanged; same gating, same result set, lower complexity

- [ ] **Step 1: 改 JdtIndexer 的 repair loop 内层循环**

In `src/main/java/com/codescope/JdtIndexer.java`, find the inner loop inside `repairMethodHierarchyViaTypeHierarchy` (the one starting `for (MethodKey candidate : index.knownMethods())`, around line 173-182). Replace it with:

```java
                            for (MethodKey candidate : index.methodsWithSignature(m.methodName, m.arity)) {
                                if (!candidate.declaringClass.equals(sub)) continue;
                                int cMods = index.modifiersOf(candidate);
                                if (cMods == 0) continue;
                                if (org.eclipse.jdt.core.dom.Modifier.isPrivate(cMods)) continue;
                                if (org.eclipse.jdt.core.dom.Modifier.isStatic(cMods)) continue;
                                index.recordHierarchy(m, candidate);
                            }
```

The only change: `index.knownMethods()` → `index.methodsWithSignature(m.methodName, m.arity)`. The two redundant filters (`methodName` and `arity`) drop away because the index already keyed on them. `declaringClass` and the modifier gates stay (per the spec's behavior-equivalence requirement).

- [ ] **Step 2: 跑修复 pass 的行为级 regression 测试**

Run: `mvn -q -Dtest='ProjectIndexInterfaceExtendsAbstractTest,CallChainAnalyzerTest#privateMethodsAreNotCrossClassHierarchy,ProjectIndexSubInterfaceTest,CallChainAnalyzerTest' test`
Expected: All behavior tests pass. These tests prove the repair pass still produces the same hierarchy edges as before.

- [ ] **Step 3: 跑全量回归**

Run: `mvn -q test`
Expected: All 188+ tests pass (181 prior + 7 from Task 1). No new failures.

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/codescope/JdtIndexer.java
git commit -m "$(cat <<'EOF'
perf(jdtindexer): route repair pass inner scan through signature index

RepairMethodHierarchyViaTypeHierarchy previously iterated the full
knownMethods() set for every reachable subtype, costing O(M²×N) on
projects with thousands of declarations and a non-trivial type
hierarchy. With ProjectIndex.methodsWithSignature the inner loop
is bounded by the (typically single-digit) number of methods sharing
(m.methodName, m.arity), bringing the pass to O(M×N×K).

Behavior is unchanged: declaringClass and the private/static
modifier gates are preserved. Verified by the existing
abstractMethodRelatedMethodsIncludesImplementor and
privateMethodsAreNotCrossClassHierarchy tests, plus the full suite.

Closes the build-phase half of issue #6.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 收尾

**Files:** none

- [ ] **Step 1: 跑最终全量测试**

Run: `mvn -q test`
Expected: All tests pass. If any fail, investigate before claiming done — a behavior regression means the spec's "行为等价" requirement was violated.

- [ ] **Step 2: 检查 git 状态**

Run: `git status && git log --oneline -5`
Expected: Clean working tree, two new commits on top of `6c4d4db` (the spec commit).

- [ ] **Step 3: 在 issue #6 留完成评论（可选）**

If the user wants the issue closed, post a comment via `gh issue comment 6 --body "..."`. The plan does not include this step automatically because closing / commenting on issues is a shared-state action and warrants an explicit user request. Skip unless asked.
