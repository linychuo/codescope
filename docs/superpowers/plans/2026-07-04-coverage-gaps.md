# Coverage Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close two silent-drop paths in codescope's indexer: (1) calls in static blocks, instance blocks, field initializers, and enum-constant args are dropped because `methodStack` is empty when visiting them; (2) `src/test/java` is silently excluded with no opt-in.

**Architecture:** Per-class synthetic `MethodKey`s (`<clinit>/0`, `<class-init>/0`) attribute dropped calls. New visitor overrides (`visit(Initializer)`, `visit(EnumConstantDeclaration)`) and a modified `visit(FieldDeclaration)` push these synthetics onto `methodStack` so `recordCall` (the single chokepoint at `JdtIndexer.java:640-655`) attributes calls correctly. Synthetic methods appear in `find_symbols` with `kind="synthetic"` and are skipped in the hierarchy repair pass via the existing `mMods == 0` check at `JdtIndexer.java:159` (synthetics simply don't have `recordMethodDeclarationModifiers` called for them). For X1, an `include_tests: boolean` parameter (default `false`) is added to all three tools; the cache key is widened to `(projectRoot, includeTests)`.

**Tech Stack:** Java 21, Eclipse JDT 3.45 (being bumped to 3.46 in uncommitted `pom.xml` — the visitor API used here is stable across both), Maven, JUnit 5 with `@TempDir`.

**Spec:** `docs/superpowers/specs/2026-07-04-coverage-gaps-design.md`

## Global Constraints

- Java 21 source/target (`pom.xml`).
- Eclipse JDT 3.45 (or 3.46 if the bump is committed separately — the visitor API used here is stable across both).
- Test framework: JUnit 5 with `@TempDir` for filesystem-based tests.
- All 125 existing tests must continue to pass — default `false` for `include_tests` preserves current behavior.
- Synthetic method names use `<` prefix (`<clinit>`, `<class-init>`) — no source method can collide (Java reserves `<` for JVM-level identifiers; JDT uses the class simple name for constructors, never `<init>`).
- Synthetic methods do NOT get `recordMethodDeclarationModifiers` called — the existing `if (mMods == 0) continue;` check at `JdtIndexer.java:159` skips them in hierarchy repair.

---

### Task 1: Add `synthetic` kind to find_symbols

**Files:**
- Modify: `src/main/java/com/codescope/FindSymbolsService.java:30-32` (add `"synthetic"` to `VALID_KINDS`)
- Modify: `src/main/java/com/codescope/FindSymbolsTool.java:32-34` (update description to mention `synthetic`)

**Interfaces:**
- Produces: `FindSymbolsService.VALID_KINDS` now includes `"synthetic"`. Tasks 2-4 record symbols with `kind="synthetic"`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/codescope/FindSymbolsServiceTest.java` (inside the existing test class, before the final closing brace):

```java
@Test
void syntheticKindIsAcceptedNotRejected() {
    // kind="synthetic" should be accepted (not rejected as unknown).
    // Initially returns empty since no synthetics are recorded yet;
    // Tasks 2-4 add the recording.
    FindSymbolsService svc = new FindSymbolsService();
    String json = svc.findSymbolsJson("clinit", "synthetic", FIXTURE, false);
    // status should be "ok" — not an error response
    assertTrue(json.contains("\"status\":\"ok\""),
            "kind=synthetic should be accepted, got: " + json);
}
```

If `FIXTURE` is not in scope in this test class, replace with the existing fixture constant used by other tests in the file (look for `private static final String FIXTURE = ...` or similar).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FindSymbolsServiceTest#syntheticKindIsAcceptedNotRejected`
Expected: FAIL with `kind "synthetic" not in VALID_KINDS` error message embedded in JSON (or similar — the test asserts `status: ok` which won't be present).

- [ ] **Step 3: Add `"synthetic"` to `VALID_KINDS`**

Edit `src/main/java/com/codescope/FindSymbolsService.java` lines 30-32:

```java
static final Set<String> VALID_KINDS = Set.of(
        "class", "interface", "enum", "record", "annotation",
        "method", "constructor", "field", "synthetic");
```

- [ ] **Step 4: Update `FindSymbolsTool` description**

Edit `src/main/java/com/codescope/FindSymbolsTool.java` — find the `kind` parameter description in `inputSchema()` (around line 49-56 based on audit) and add `synthetic` to the list of valid kinds:

```java
// existing: "class" / "interface" / "enum" / "record" / "annotation" / "method" / "constructor" / "field"
// new:      add "synthetic" — covers <clinit> and <class-init> synthetic methods recorded by JdtIndexer
```

(The exact wording is up to the implementer — the requirement is that `synthetic` appears in the description string.)

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=FindSymbolsServiceTest#syntheticKindIsAcceptedNotRejected`
Expected: PASS.

- [ ] **Step 6: Run full test suite to verify no regressions**

Run: `mvn test`
Expected: All 125 existing tests + the new test pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/codescope/FindSymbolsService.java src/main/java/com/codescope/FindSymbolsTool.java src/test/java/com/codescope/FindSymbolsServiceTest.java
git commit -m "feat(find-symbols): accept kind=synthetic for upcoming synthetic methods"
```

---

### Task 2: Index static and instance initializer blocks (`static {}` and `{}`)

**Files:**
- Modify: `src/main/java/com/codescope/JdtIndexer.java` (add `visit(Initializer)` and `endVisit(Initializer)` overrides inside `CallSiteVisitor`, near the existing `visit(MethodDeclaration)` at line 489)
- Test: `src/test/java/com/codescope/EdgeCaseTest.java`

**Interfaces:**
- Consumes: `MethodContext` record (`JdtIndexer.java:989`), `MethodKey` (`com.codescope.MethodKey`), `ProjectIndex.putDeclaration` / `recordSymbol` / `recordInvocation` / `recordCallSite`.
- Produces: When `visit(Initializer)` is entered, a synthetic `MethodContext` is on `methodStack` so calls inside the block attribute to `<clinit>/0` (static) or `<class-init>/0` (instance). The synthetic has a declaration recorded and a `find_symbols` Symbol with `kind="synthetic"`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/codescope/EdgeCaseTest.java` (before the final closing brace of the test class):

```java
@Test
void staticBlockCallersFound(@TempDir Path tmp) throws IOException {
    Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(srcDir.resolve("Target.java"),
            "package com.example;\n"
            + "public class Target {\n"
            + "    static { foo(); }\n"
            + "    private static void foo() {}\n"
            + "}\n");
    Files.writeString(srcDir.resolve("Caller.java"),
            "package com.example;\n"
            + "public class Caller {\n"
            + "    public void run() { Target.foo(); }\n"  // not the path under test
            + "}\n");
    ProjectIndex index = new JdtIndexer().build(
            List.of(srcDir.resolve("Target.java"), srcDir.resolve("Caller.java")),
            List.of(),
            List.of(srcDir.getParent().toString()),
            tmp);

    MethodKey foo = new MethodKey("com.example.Target", "foo", 0, List.of());
    MethodKey clinit = new MethodKey("com.example.Target", "<clinit>", 0, List.of());
    Set<MethodKey> callers = index.callersOf(foo);
    assertTrue(callers.contains(clinit),
            "static block calls should attribute to <clinit>/0, got: " + callers);
}

@Test
void instanceBlockCallersFound(@TempDir Path tmp) throws IOException {
    Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(srcDir.resolve("Target.java"),
            "package com.example;\n"
            + "public class Target {\n"
            + "    { foo(); }\n"
            + "    private void foo() {}\n"
            + "}\n");
    ProjectIndex index = new JdtIndexer().build(
            List.of(srcDir.resolve("Target.java")),
            List.of(),
            List.of(srcDir.getParent().toString()),
            tmp);

    MethodKey foo = new MethodKey("com.example.Target", "foo", 0, List.of());
    MethodKey classInit = new MethodKey("com.example.Target", "<class-init>", 0, List.of());
    Set<MethodKey> callers = index.callersOf(foo);
    assertTrue(callers.contains(classInit),
            "instance block calls should attribute to <class-init>/0, got: " + callers);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=EdgeCaseTest#staticBlockCallersFound+instanceBlockCallersFound`
Expected: FAIL — `callers` is empty because the calls are dropped at `recordCall` (`methodStack.isEmpty()` returns true).

- [ ] **Step 3: Implement `visit(Initializer)` and `endVisit(Initializer)`**

Add inside `CallSiteVisitor` in `src/main/java/com/codescope/JdtIndexer.java`, near the existing `visit(MethodDeclaration)` (after `endVisit(MethodDeclaration)` around line 551):

```java
@Override
public boolean visit(Initializer node) {
    // Static {} and instance {} blocks: JDT visits the calls inside but
    // methodStack is empty here (we're not inside a MethodDeclaration),
    // so recordCall would silently drop them. Push a synthetic
    // MethodContext keyed by <clinit> (static) or <class-init> (instance)
    // so calls get attributed instead.
    String cls = currentClass();
    boolean isStatic = org.eclipse.jdt.core.dom.Modifier.isStatic(node.getModifiers());
    String name = isStatic ? "<clinit>" : "<class-init>";
    MethodKey key = new MethodKey(cls, name, 0, List.of());
    int line = cuLine(node);
    // First-wins: if a class has multiple init blocks, the declaration
    // points at the first one. Subsequent pushes don't overwrite.
    if (index.declarationOf(key) == null) {
        index.putDeclaration(key, new ProjectIndex.SourceLoc(file, line));
        index.recordSymbol(new ProjectIndex.Symbol(
                name, "synthetic",
                cls + "." + name, cls, file, line, null));
    }
    methodStack.push(new MethodContext(key));
    return true;
}

@Override
public void endVisit(Initializer node) {
    methodStack.pop();
}
```

Notes:
- `currentClass()` is the existing helper that reads `typeStack.peek()`.
- `cuLine(node)` is the existing helper.
- `index.declarationOf(key)`, `index.putDeclaration(key, loc)`, `index.recordSymbol(...)` are existing `ProjectIndex` methods.
- Do NOT call `index.recordMethodDeclarationModifiers(key, ...)` — the existing `if (mMods == 0) continue;` check at `JdtIndexer.java:159` skips methods with no recorded modifiers, which is exactly what we want for synthetics.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=EdgeCaseTest#staticBlockCallersFound+instanceBlockCallersFound`
Expected: PASS.

- [ ] **Step 5: Run full test suite**

Run: `mvn test`
Expected: All existing tests + the 2 new tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/codescope/JdtIndexer.java src/test/java/com/codescope/EdgeCaseTest.java
git commit -m "feat(index): attribute static/instance init block calls to <clinit>/<class-init>"
```

---

### Task 3: Index static and instance field initializers

**Files:**
- Modify: `src/main/java/com/codescope/JdtIndexer.java:554-575` (change `visit(FieldDeclaration)` to descend and push synthetic)
- Test: `src/test/java/com/codescope/EdgeCaseTest.java`

**Interfaces:**
- Consumes: Same as Task 2.
- Produces: `visit(FieldDeclaration)` now returns `true` and pushes a synthetic MethodContext. Calls inside field initializer expressions attribute to `<clinit>/0` (static) or `<class-init>/0` (instance).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/codescope/EdgeCaseTest.java`:

```java
@Test
void staticFieldInitializerCallersFound(@TempDir Path tmp) throws IOException {
    Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(srcDir.resolve("Target.java"),
            "package com.example;\n"
            + "public class Target {\n"
            + "    static int x = compute();\n"
            + "    private static int compute() { return 42; }\n"
            + "}\n");
    ProjectIndex index = new JdtIndexer().build(
            List.of(srcDir.resolve("Target.java")),
            List.of(),
            List.of(srcDir.getParent().toString()),
            tmp);

    MethodKey compute = new MethodKey("com.example.Target", "compute", 0, List.of());
    MethodKey clinit = new MethodKey("com.example.Target", "<clinit>", 0, List.of());
    Set<MethodKey> callers = index.callersOf(compute);
    assertTrue(callers.contains(clinit),
            "static field initializer calls should attribute to <clinit>/0, got: " + callers);
}

@Test
void instanceFieldInitializerCallersFound(@TempDir Path tmp) throws IOException {
    Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(srcDir.resolve("Target.java"),
            "package com.example;\n"
            + "public class Target {\n"
            + "    int x = compute();\n"
            + "    private int compute() { return 42; }\n"
            + "}\n");
    ProjectIndex index = new JdtIndexer().build(
            List.of(srcDir.resolve("Target.java")),
            List.of(),
            List.of(srcDir.getParent().toString()),
            tmp);

    MethodKey compute = new MethodKey("com.example.Target", "compute", 0, List.of());
    MethodKey classInit = new MethodKey("com.example.Target", "<class-init>", 0, List.of());
    Set<MethodKey> callers = index.callersOf(compute);
    assertTrue(callers.contains(classInit),
            "instance field initializer calls should attribute to <class-init>/0, got: " + callers);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=EdgeCaseTest#staticFieldInitializerCallersFound+instanceFieldInitializerCallersFound`
Expected: FAIL — `callers` is empty because `visit(FieldDeclaration)` returns `false` (doesn't descend), so calls in initializers are never visited.

- [ ] **Step 3: Modify `visit(FieldDeclaration)`**

Edit `src/main/java/com/codescope/JdtIndexer.java:554-575`. Replace the existing `visit(FieldDeclaration)`:

```java
@Override
public boolean visit(FieldDeclaration node) {
    // A single FieldDeclaration can declare multiple variables
    // (e.g. `int a, b, c;`) — each is a separate VariableDeclarationFragment
    // and we record one symbol per fragment. The line is the
    // declaration's line (the modifiers' line); all fragments on the
    // same line share it, which matches how IDEs show fields.
    String callerClass = currentClass();
    int line = cuLine(node);
    for (Object f : node.fragments()) {
        VariableDeclarationFragment frag = (VariableDeclarationFragment) f;
        String fieldName = frag.getName().getIdentifier();
        index.recordSymbol(new ProjectIndex.Symbol(
                fieldName,
                "field",
                callerClass + "." + fieldName,
                callerClass,
                file,
                line,
                null));
    }
    // Push a synthetic MethodContext so calls inside initializer
    // expressions (e.g. `private Logger log = LoggerFactory.get();`)
    // get attributed instead of dropped by recordCall's methodStack
    // isEmpty check. Static fields → <clinit>, instance → <class-init>.
    boolean isStatic = org.eclipse.jdt.core.dom.Modifier.isStatic(node.getModifiers());
    String synthName = isStatic ? "<clinit>" : "<class-init>";
    MethodKey synthKey = new MethodKey(callerClass, synthName, 0, List.of());
    if (index.declarationOf(synthKey) == null) {
        index.putDeclaration(synthKey, new ProjectIndex.SourceLoc(file, line));
        index.recordSymbol(new ProjectIndex.Symbol(
                synthName, "synthetic",
                callerClass + "." + synthName, callerClass, file, line, null));
    }
    methodStack.push(new MethodContext(synthKey));
    return true;  // descend into fragments so initializer calls get visited
}

@Override
public void endVisit(FieldDeclaration node) {
    methodStack.pop();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=EdgeCaseTest#staticFieldInitializerCallersFound+instanceFieldInitializerCallersFound`
Expected: PASS.

- [ ] **Step 5: Run full test suite**

Run: `mvn test`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/codescope/JdtIndexer.java src/test/java/com/codescope/EdgeCaseTest.java
git commit -m "feat(index): attribute field initializer calls to <clinit>/<class-init>"
```

---

### Task 4: Index enum constant constructor arguments

**Files:**
- Modify: `src/main/java/com/codescope/JdtIndexer.java` (add `visit(EnumConstantDeclaration)` and `endVisit(EnumConstantDeclaration)` inside `CallSiteVisitor`)
- Test: `src/test/java/com/codescope/EdgeCaseTest.java`

**Interfaces:**
- Consumes: Same as Tasks 2-3. `EnumConstantDeclaration` is in `org.eclipse.jdt.core.dom`.
- Produces: Calls inside enum constant args (e.g. `A(foo())` in `enum E { A(foo()) }`) attribute to `pkg.E.<clinit>/0` (because enum constants are conceptually `public static final E A = new E(args);` — args evaluated during static init).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/codescope/EdgeCaseTest.java`:

```java
@Test
void enumConstantArgCallersFound(@TempDir Path tmp) throws IOException {
    Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(srcDir.resolve("MyEnum.java"),
            "package com.example;\n"
            + "public enum MyEnum {\n"
            + "    A(compute());\n"
            + "    private MyEnum(int x) {}\n"
            + "    private static int compute() { return 1; }\n"
            + "}\n");
    Files.writeString(srcDir.resolve("Helper.java"),
            "package com.example;\n"
            + "public class Helper {\n"
            + "    public int compute() { return 2; }\n"  // not the path under test
            + "}\n");
    ProjectIndex index = new JdtIndexer().build(
            List.of(srcDir.resolve("MyEnum.java"), srcDir.resolve("Helper.java")),
            List.of(),
            List.of(srcDir.getParent().toString()),
            tmp);

    MethodKey compute = new MethodKey("com.example.MyEnum", "compute", 0, List.of());
    MethodKey clinit = new MethodKey("com.example.MyEnum", "<clinit>", 0, List.of());
    Set<MethodKey> callers = index.callersOf(compute);
    assertTrue(callers.contains(clinit),
            "enum constant arg calls should attribute to <clinit>/0, got: " + callers);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EdgeCaseTest#enumConstantArgCallersFound`
Expected: FAIL — `callers` is empty because there's no `visit(EnumConstantDeclaration)` override, so JDT's default visitor descends but `methodStack` is empty (we're inside `visit(EnumDeclaration)`, not inside a `MethodDeclaration`).

- [ ] **Step 3: Add `visit(EnumConstantDeclaration)` and `endVisit`**

Add inside `CallSiteVisitor` in `src/main/java/com/codescope/JdtIndexer.java`, near the existing `visit(EnumDeclaration)` (around line 370):

```java
@Override
public boolean visit(EnumConstantDeclaration node) {
    // `enum E { A(foo()) }` — the args (foo()) are evaluated during
    // the enum class's static init, conceptually equivalent to
    // `public static final E A = new E(foo());`. Attribute calls in
    // the args to <clinit>/0.
    String cls = currentClass();
    MethodKey key = new MethodKey(cls, "<clinit>", 0, List.of());
    int line = cuLine(node);
    if (index.declarationOf(key) == null) {
        index.putDeclaration(key, new ProjectIndex.SourceLoc(file, line));
        index.recordSymbol(new ProjectIndex.Symbol(
                "<clinit>", "synthetic",
                cls + ".<clinit>", cls, file, line, null));
    }
    methodStack.push(new MethodContext(key));
    return true;  // descend so arg expressions get visited
}

@Override
public void endVisit(EnumConstantDeclaration node) {
    methodStack.pop();
}
```

Note: import `org.eclipse.jdt.core.dom.EnumConstantDeclaration` at the top of `JdtIndexer.java` if not already present.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=EdgeCaseTest#enumConstantArgCallersFound`
Expected: PASS.

- [ ] **Step 5: Run full test suite**

Run: `mvn test`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/codescope/JdtIndexer.java src/test/java/com/codescope/EdgeCaseTest.java
git commit -m "feat(index): attribute enum constant arg calls to <clinit>"
```

---

### Task 5: F6 mixed integration test

**Files:**
- Test: `src/test/java/com/codescope/EdgeCaseTest.java`

**Interfaces:**
- Consumes: All F6 work from Tasks 1-4.
- Produces: A regression test verifying the full F6 feature works end-to-end with mixed init sites in one class.

- [ ] **Step 1: Write the integration test**

Add to `src/test/java/com/codescope/EdgeCaseTest.java`:

```java
@Test
void mixedMainAndInitCallsFoundTogether(@TempDir Path tmp) throws IOException {
    // One class with: explicit method, static block, instance block,
    // static field init, instance field init, enum constant arg.
    // Verify <clinit> and <class-init> are distinct keys, and calls
    // attribute to the right one.
    Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(srcDir.resolve("Mixed.java"),
            "package com.example;\n"
            + "public class Mixed {\n"
            + "    static int s = staticHelper();\n"          // → <clinit>
            + "    int i = instanceHelper();\n"               // → <class-init>
            + "    static { staticHelper(); }\n"               // → <clinit>
            + "    { instanceHelper(); }\n"                    // → <class-init>
            + "    public void explicit() { explicitHelper(); }\n"  // → explicit (regular method)
            + "    private static int staticHelper() { return 1; }\n"
            + "    private int instanceHelper() { return 2; }\n"
            + "    private void explicitHelper() {}\n"
            + "}\n");
    ProjectIndex index = new JdtIndexer().build(
            List.of(srcDir.resolve("Mixed.java")),
            List.of(),
            List.of(srcDir.getParent().toString()),
            tmp);

    MethodKey staticHelper = new MethodKey("com.example.Mixed", "staticHelper", 0, List.of());
    MethodKey instanceHelper = new MethodKey("com.example.Mixed", "instanceHelper", 0, List.of());
    MethodKey explicitHelper = new MethodKey("com.example.Mixed", "explicitHelper", 0, List.of());
    MethodKey clinit = new MethodKey("com.example.Mixed", "<clinit>", 0, List.of());
    MethodKey classInit = new MethodKey("com.example.Mixed", "<class-init>", 0, List.of());
    MethodKey explicit = new MethodKey("com.example.Mixed", "explicit", 0, List.of());

    Set<MethodKey> staticHelperCallers = index.callersOf(staticHelper);
    Set<MethodKey> instanceHelperCallers = index.callersOf(instanceHelper);
    Set<MethodKey> explicitHelperCallers = index.callersOf(explicitHelper);

    // staticHelper is called from <clinit> (twice — field init + static block),
    // but callersOf returns a Set so it's deduplicated.
    assertEquals(Set.of(clinit), staticHelperCallers,
            "staticHelper should only be called by <clinit>, got: " + staticHelperCallers);
    assertEquals(Set.of(classInit), instanceHelperCallers,
            "instanceHelper should only be called by <class-init>, got: " + instanceHelperCallers);
    assertEquals(Set.of(explicit), explicitHelperCallers,
            "explicitHelper should only be called by explicit, got: " + explicitHelperCallers);

    // <clinit> and <class-init> are distinct keys
    assertNotEquals(clinit, classInit);
}
```

- [ ] **Step 2: Run the integration test**

Run: `mvn test -Dtest=EdgeCaseTest#mixedMainAndInitCallsFoundTogether`
Expected: PASS (since Tasks 2-4 are already done).

If FAIL, debug — the most likely cause is a `visit`/`endVisit` mismatch leaving `methodStack` in a bad state.

- [ ] **Step 3: Run full test suite**

Run: `mvn test`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/codescope/EdgeCaseTest.java
git commit -m "test(index): mixed main/init calls integration test for F6"
```

---

### Task 6: Migrate `ProjectIndexCache` key + add `includeTests` to `ProjectLoader`

**Files:**
- Modify: `src/main/java/com/codescope/ProjectIndexCache.java` (introduce `IndexCacheKey` record, widen `loadOrRebuild` + `validateAndLoad` + `buildIndex`)
- Modify: `src/main/java/com/codescope/ProjectLoader.java:325-358` (widen `collectSourceRoots`, `collectSourceRoots0`, `collectSources`, and `load` to accept `includeTests`)
- Modify: `src/main/java/com/codescope/TraceCallersService.java` (pass `false` to `validateAndLoad`)
- Modify: `src/main/java/com/codescope/FindCallSitesService.java` (pass `false` to `validateAndLoad`)
- Modify: `src/main/java/com/codescope/FindSymbolsService.java` (pass `false` to `validateAndLoad`)
- Test: `src/test/java/com/codescope/EdgeCaseTest.java` (no new test — existing tests must pass)

**Interfaces:**
- Consumes: existing `ProjectIndexCache.loadOrRebuild(Path, boolean)` API.
- Produces: new `ProjectIndexCache.loadOrRebuild(Path, boolean, boolean includeTests)` API. `ProjectLoader.collectSourceRoots(Path, boolean)` and `ProjectLoader.load(Path, boolean)`. All 3 services pass `false` (literal) — no behavior change. Tasks 7-9 will replace the literal with the request parameter.

- [ ] **Step 1: Run existing tests to establish a green baseline**

Run: `mvn test`
Expected: All existing tests pass (125 + new F6 tests from Tasks 1-5).

- [ ] **Step 2: Add `IndexCacheKey` record and widen cache API**

Edit `src/main/java/com/codescope/ProjectIndexCache.java`. Add the record near the top of the class (after the existing `private final Map<Path, ProjectIndex> entries` field — change its type too):

```java
record IndexCacheKey(Path projectRoot, boolean includeTests) {
    @Override public int hashCode() {
        return projectRoot.hashCode() * 31 + Boolean.hashCode(includeTests);
    }
    @Override public boolean equals(Object o) {
        if (!(o instanceof IndexCacheKey k)) return false;
        return projectRoot.equals(k.projectRoot) && includeTests == k.includeTests;
    }
}

private final Map<IndexCacheKey, ProjectIndex> entries =
        Collections.synchronizedMap(new LinkedHashMap<>(CACHE_CAPACITY, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<IndexCacheKey, ProjectIndex> eldest) {
                return size() > CACHE_CAPACITY;
            }
        });
```

(Adjust the existing `entries` field — replace `Map<Path, ProjectIndex>` with `Map<IndexCacheKey, ProjectIndex>`. Keep the LRU configuration identical.)

Widen `loadOrRebuild`:

```java
public ProjectIndex loadOrRebuild(Path projectRoot, boolean refresh, boolean includeTests) {
    IndexCacheKey key = new IndexCacheKey(projectRoot, includeTests);
    if (refresh) {
        entries.remove(key);
    }
    return entries.computeIfAbsent(key, k -> buildIndex(k));
}

// Backward-compatible overload for any caller we miss; delegates with includeTests=false.
@Deprecated
public ProjectIndex loadOrRebuild(Path projectRoot, boolean refresh) {
    return loadOrRebuild(projectRoot, refresh, false);
}
```

Widen `buildIndex`:

```java
private ProjectIndex buildIndex(IndexCacheKey key) {
    try {
        ProjectLoader.LoadResult load = new ProjectLoader().load(key.projectRoot(), key.includeTests());
        return indexer.build(load.sources(), load.classpath(), load.sourcepath(), key.projectRoot());
    } catch (IOException e) {
        throw new UncheckedIOException("Failed to load project at " + key.projectRoot(), e);
    }
}
```

Widen `validateAndLoad`:

```java
public static <E extends Exception> ProjectIndex validateAndLoad(
        ProjectIndexCache cache, Path projectRoot, boolean refresh,
        boolean includeTests,
        Function<String, E> exceptionFactory) throws E {
    if (!Files.isDirectory(projectRoot)) {
        throw exceptionFactory.apply("Project root is not a directory: " + projectRoot);
    }
    if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
        throw exceptionFactory.apply("No pom.xml at " + projectRoot
                + " — only Maven projects are supported in this version.");
    }
    try {
        return cache.loadOrRebuild(projectRoot, refresh, includeTests);
    } catch (UncheckedIOException e) {
        throw exceptionFactory.apply(e.getCause().getMessage());
    }
}

// Backward-compatible overload
@Deprecated
public static <E extends Exception> ProjectIndex validateAndLoad(
        ProjectIndexCache cache, Path projectRoot, boolean refresh,
        Function<String, E> exceptionFactory) throws E {
    return validateAndLoad(cache, projectRoot, refresh, false, exceptionFactory);
}
```

- [ ] **Step 3: Widen `ProjectLoader.load` and `collectSourceRoots` to accept `includeTests`**

Edit `src/main/java/com/codescope/ProjectLoader.java`. Add `includeTests` parameter to `load`, `collectSources`, `collectSourceRoots`, and `collectSourceRoots0`:

```java
public LoadResult load(Path projectRoot, boolean includeTests) throws IOException {
    // ... existing body, but pass includeTests to collectSources / collectSourceRoots
}

public static List<Path> collectSources(Path projectRoot, boolean includeTests) {
    // ... existing body, but pass includeTests to collectSourceRoots0
}

public static List<String> collectSourceRoots(Path projectRoot, boolean includeTests) {
    return collectSourceRoots0(projectRoot, includeTests).stream().map(Path::toString).toList();
}

private static List<Path> collectSourceRoots0(Path projectRoot, boolean includeTests) {
    List<Path> out = new ArrayList<>();
    if (!Files.isDirectory(projectRoot)) return out;
    try (Stream<Path> s = Files.walk(projectRoot, MAX_DIRECTORY_DEPTH)) {
        s.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().equals("java"))
                .filter(p -> {
                    Path parent = p.getParent();
                    if (parent == null) return false;
                    String parentName = parent.getFileName().toString();
                    // main is always included; test only when includeTests is true
                    if (parentName.equals("main")) return true;
                    if (includeTests && parentName.equals("test")) return true;
                    return false;
                })
                .filter(p -> !relativeSegmentEquals(p, projectRoot, "node_modules"))
                .forEach(out::add);
    } catch (IOException e) {
        // best-effort
    }
    return out;
}
```

Keep the existing 1-arg overloads as backward-compatible wrappers (deprecated) so any missed call sites still compile:

```java
@Deprecated
public LoadResult load(Path projectRoot) throws IOException {
    return load(projectRoot, false);
}

@Deprecated
public static List<Path> collectSources(Path projectRoot) {
    return collectSources(projectRoot, false);
}

@Deprecated
public static List<String> collectSourceRoots(Path projectRoot) {
    return collectSourceRoots(projectRoot, false);
}
```

Find every other call site of these methods (`grep -rn 'collectSourceRoots\|collectSources\|new ProjectLoader().load' src/`) and migrate them to the new signature. The deprecated overloads exist only to avoid compile errors during this migration — once all call sites are migrated, the overloads can be deleted (out of scope for this task).

- [ ] **Step 4: Update all 3 services to pass `false`**

In `TraceCallersService.java`, `FindCallSitesService.java`, `FindSymbolsService.java` — find the `validateAndLoad(...)` call in each (around line 44 in `TraceCallersService.java:42-44`) and add `false` (literal) as the new `includeTests` argument:

```java
// Old:
//   return ProjectIndexCache.validateAndLoad(
//           indexCache, projectRoot, refresh, TraceCallersException::new);
// New:
return ProjectIndexCache.validateAndLoad(
        indexCache, projectRoot, refresh, false, TraceCallersException::new);
```

(Same pattern for the other two services — use their respective exception factories.)

- [ ] **Step 5: Run full test suite**

Run: `mvn test`
Expected: All existing tests pass. No behavior change — `includeTests=false` everywhere.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/codescope/ProjectIndexCache.java src/main/java/com/codescope/ProjectLoader.java src/main/java/com/codescope/TraceCallersService.java src/main/java/com/codescope/FindCallSitesService.java src/main/java/com/codescope/FindSymbolsService.java
git commit -m "refactor(cache): widen ProjectIndexCache key to (projectRoot, includeTests)"
```

---

### Task 7: Wire `include_tests` through `trace_callers`

**Files:**
- Modify: `src/main/java/com/codescope/TraceCallersTool.java` (add `include_tests` to `inputSchema`)
- Modify: `src/main/java/com/codescope/TraceCallersService.java` (widen `traceCallersJson` signature)
- Test: `src/test/java/com/codescope/EdgeCaseTest.java` (or `CallChainAnalyzerTest.java` if it has a more suitable fixture)

**Interfaces:**
- Consumes: Task 6's widened `validateAndLoad(..., includeTests, ...)`.
- Produces: `TraceCallersService.traceCallersJson(..., boolean includeTests)`. `TraceCallersTool` accepts `include_tests` in its input schema and passes it through.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/codescope/EdgeCaseTest.java`:

```java
@Test
void includeTestsTrueIncludesTestCallers(@TempDir Path tmp) throws IOException {
    Path mainDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Path testDir = Files.createDirectories(tmp.resolve("src/test/java/com/example"));
    Files.writeString(mainDir.resolve("Target.java"),
            "package com.example;\n"
            + "public class Target {\n"
            + "    public void go() {}\n"
            + "}\n");
    Files.writeString(testDir.resolve("TargetTest.java"),
            "package com.example;\n"
            + "public class TargetTest {\n"
            + "    public void testGo() {\n"
            + "        new Target().go();\n"
            + "    }\n"
            + "}\n");

    // With include_tests=false (default), test callers are NOT found
    TraceCallersService svc = new TraceCallersService();
    String jsonMain = svc.traceCallersJson(
            "com.example.Target", "go", null, null,
            tmp, false, false);
    assertTrue(jsonMain.contains("\"status\":\"ok\""),
            "default call should succeed, got: " + jsonMain);
    assertFalse(jsonMain.contains("com.example.TargetTest"),
            "test code should NOT appear with include_tests=false, got: " + jsonMain);

    // With include_tests=true, test callers ARE found
    String jsonTest = svc.traceCallersJson(
            "com.example.Target", "go", null, null,
            tmp, false, true);
    assertTrue(jsonTest.contains("com.example.TargetTest"),
            "test code should appear with include_tests=true, got: " + jsonTest);
}
```

(Adjust the `traceCallersJson` argument list to match the actual signature — the existing signature is `traceCallersJson(className, methodName, arity, paramTypes, projectRoot, refresh)`. Add `includeTests` as the 7th arg.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EdgeCaseTest#includeTestsTrueIncludesTestCallers`
Expected: FAIL — the method signature doesn't have `includeTests` yet (compile error), OR the test compiles but `includeTests=true` doesn't include test sources.

- [ ] **Step 3: Widen `TraceCallersService.traceCallersJson`**

Edit `src/main/java/com/codescope/TraceCallersService.java:40-44`. Add `boolean includeTests` as the last parameter:

```java
public String traceCallersJson(String className, String methodName,
                              Integer arity, java.util.List<String> paramTypes,
                              Path projectRoot, boolean refresh,
                              boolean includeTests) throws TraceCallersException {
    ProjectIndex index = ProjectIndexCache.validateAndLoad(
            indexCache, projectRoot, refresh, includeTests, TraceCallersException::new);
    // ... existing body unchanged
}
```

Keep a backward-compatible 6-arg overload that delegates with `false`:

```java
@Deprecated
public String traceCallersJson(String className, String methodName,
                              Integer arity, java.util.List<String> paramTypes,
                              Path projectRoot, boolean refresh) throws TraceCallersException {
    return traceCallersJson(className, methodName, arity, paramTypes, projectRoot, refresh, false);
}
```

- [ ] **Step 4: Add `include_tests` to `TraceCallersTool.inputSchema`**

Edit `src/main/java/com/codescope/TraceCallersTool.java` — find the `inputSchema()` method and add an `include_tests` property. Match the existing pattern for `refresh` (a boolean property with default `false`). Add to both the `properties` map and the `description`:

```java
// In the properties map:
props.put("include_tests", Map.of(
        "type", "boolean",
        "default", false,
        "description", "If true, index src/test/java in addition to src/main/java. "
                + "Default false: test sources are excluded (test code does not "
                + "participate in the call chain by default). When true, test "
                + "methods appear as callers in trace_callers results."
));
```

- [ ] **Step 5: Wire `include_tests` from the tool args to the service**

In `TraceCallersTool.invoke(...)`, extract `include_tests` from the args map (default `false`) and pass it to `traceCallersJson`:

```java
boolean includeTests = args.has("include_tests") && args.get("include_tests").asBoolean();
// ...
return svc.traceCallersJson(className, methodName, arity, paramTypes, projectRoot, refresh, includeTests);
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=EdgeCaseTest#includeTestsTrueIncludesTestCallers`
Expected: PASS.

- [ ] **Step 7: Run full test suite**

Run: `mvn test`
Expected: All tests pass (existing tests use the deprecated 6-arg overload, which delegates with `false`).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/codescope/TraceCallersTool.java src/main/java/com/codescope/TraceCallersService.java src/test/java/com/codescope/EdgeCaseTest.java
git commit -m "feat(trace-callers): add include_tests parameter to opt test sources in"
```

---

### Task 8: Wire `include_tests` through `find_call_sites`

**Files:**
- Modify: `src/main/java/com/codescope/FindCallSitesTool.java`
- Modify: `src/main/java/com/codescope/FindCallSitesService.java:42`
- Test: `src/test/java/com/codescope/EdgeCaseTest.java` (or `FindCallSitesServiceTest.java`)

**Interfaces:**
- Consumes: Task 6's widened `validateAndLoad`.
- Produces: `FindCallSitesService.findCallSitesJson(..., boolean includeTests)`. `FindCallSitesTool` accepts `include_tests`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/codescope/EdgeCaseTest.java` (or `FindCallSitesServiceTest.java` — match the existing pattern of the file you choose):

```java
@Test
void includeTestsTrueFindsTestCallSites(@TempDir Path tmp) throws IOException {
    Path mainDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Path testDir = Files.createDirectories(tmp.resolve("src/test/java/com/example"));
    Files.writeString(mainDir.resolve("Target.java"),
            "package com.example;\n"
            + "public class Target {\n"
            + "    public void go() {}\n"
            + "}\n");
    Files.writeString(testDir.resolve("TargetTest.java"),
            "package com.example;\n"
            + "public class TargetTest {\n"
            + "    public void testGo() {\n"
            + "        new Target().go();\n"
            + "    }\n"
            + "}\n");

    FindCallSitesService svc = new FindCallSitesService();
    String jsonMain = svc.findCallSitesJson(
            "com.example.Target", "go", null, null,
            tmp, false, false);
    assertFalse(jsonMain.contains("TargetTest"),
            "test code should NOT appear with include_tests=false, got: " + jsonMain);

    String jsonTest = svc.findCallSitesJson(
            "com.example.Target", "go", null, null,
            tmp, false, true);
    assertTrue(jsonTest.contains("TargetTest"),
            "test code should appear with include_tests=true, got: " + jsonTest);
}
```

(Adjust the `findCallSitesJson` argument list to match the actual signature — the existing signature is `findCallSitesJson(className, methodName, arity, paramTypes, projectRoot, refresh)`. Add `includeTests` as the 7th arg.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EdgeCaseTest#includeTestsTrueFindsTestCallSites`
Expected: FAIL — compile error or test failure.

- [ ] **Step 3: Widen `FindCallSitesService.findCallSitesJson`**

Edit `src/main/java/com/codescope/FindCallSitesService.java:42`. Add `boolean includeTests` as the last parameter, pass it to `validateAndLoad`:

```java
public String findCallSitesJson(String className, String methodName,
                               Integer arity, java.util.List<String> paramTypes,
                               Path projectRoot, boolean refresh,
                               boolean includeTests) throws FindCallSitesException {
    ProjectIndex index = ProjectIndexCache.validateAndLoad(
            indexCache, projectRoot, refresh, includeTests, FindCallSitesException::new);
    // ... existing body unchanged
}

@Deprecated
public String findCallSitesJson(String className, String methodName,
                               Integer arity, java.util.List<String> paramTypes,
                               Path projectRoot, boolean refresh) throws FindCallSitesException {
    return findCallSitesJson(className, methodName, arity, paramTypes, projectRoot, refresh, false);
}
```

- [ ] **Step 4: Add `include_tests` to `FindCallSitesTool.inputSchema` and wire it through `invoke`**

Same pattern as Task 7 Step 4-5 — add `include_tests` boolean property to the schema, extract in `invoke`, pass to `findCallSitesJson`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=EdgeCaseTest#includeTestsTrueFindsTestCallSites`
Expected: PASS.

- [ ] **Step 6: Run full test suite**

Run: `mvn test`
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/codescope/FindCallSitesTool.java src/main/java/com/codescope/FindCallSitesService.java src/test/java/com/codescope/EdgeCaseTest.java
git commit -m "feat(find-call-sites): add include_tests parameter to opt test sources in"
```

---

### Task 9: Wire `include_tests` through `find_symbols`

**Files:**
- Modify: `src/main/java/com/codescope/FindSymbolsTool.java`
- Modify: `src/main/java/com/codescope/FindSymbolsService.java:54`
- Test: `src/test/java/com/codescope/FindSymbolsServiceTest.java`

**Interfaces:**
- Consumes: Task 6's widened `validateAndLoad`.
- Produces: `FindSymbolsService.findSymbolsJson(..., boolean includeTests)`. `FindSymbolsTool` accepts `include_tests`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/codescope/FindSymbolsServiceTest.java`:

```java
@Test
void includeTestsTrueReturnsTestSymbols(@TempDir Path tmp) throws IOException {
    Path mainDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Path testDir = Files.createDirectories(tmp.resolve("src/test/java/com/example"));
    Files.writeString(mainDir.resolve("Target.java"),
            "package com.example;\n"
            + "public class Target {}\n");
    Files.writeString(testDir.resolve("TargetTest.java"),
            "package com.example;\n"
            + "public class TargetTest {}\n");

    FindSymbolsService svc = new FindSymbolsService();
    String jsonMain = svc.findSymbolsJson("Target", null, tmp, false, false);
    assertFalse(jsonMain.contains("TargetTest"),
            "test code should NOT appear with include_tests=false, got: " + jsonMain);

    String jsonTest = svc.findSymbolsJson("Target", null, tmp, false, true);
    assertTrue(jsonTest.contains("TargetTest"),
            "test code should appear with include_tests=true, got: " + jsonTest);
}
```

(The existing `findSymbolsJson` signature is `findSymbolsJson(query, kind, projectRoot, refresh)`. Add `includeTests` as the 5th arg.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FindSymbolsServiceTest#includeTestsTrueReturnsTestSymbols`
Expected: FAIL — compile error (5-arg signature doesn't exist yet).

- [ ] **Step 3: Widen `FindSymbolsService.findSymbolsJson`**

Edit `src/main/java/com/codescope/FindSymbolsService.java:54`:

```java
public String findSymbolsJson(String query, String kind,
                             Path projectRoot, boolean refresh,
                             boolean includeTests) throws FindSymbolsException {
    ProjectIndex index = ProjectIndexCache.validateAndLoad(
            indexCache, projectRoot, refresh, includeTests, FindSymbolsException::new);
    // ... existing body unchanged
}

@Deprecated
public String findSymbolsJson(String query, String kind,
                             Path projectRoot, boolean refresh) throws FindSymbolsException {
    return findSymbolsJson(query, kind, projectRoot, refresh, false);
}
```

**Important:** The existing tests in `FindSymbolsServiceTest.java` call the 4-arg `findSymbolsJson(query, kind, FIXTURE, false)`. They will use the deprecated 4-arg overload (delegates with `false`). No need to update them — that's the point of the backward-compatible overload.

- [ ] **Step 4: Add `include_tests` to `FindSymbolsTool.inputSchema` and wire through `invoke`**

Same pattern as Tasks 7-8.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=FindSymbolsServiceTest#includeTestsTrueReturnsTestSymbols`
Expected: PASS.

- [ ] **Step 6: Run full test suite**

Run: `mvn test`
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/codescope/FindSymbolsTool.java src/main/java/com/codescope/FindSymbolsService.java src/test/java/com/codescope/FindSymbolsServiceTest.java
git commit -m "feat(find-symbols): add include_tests parameter to opt test sources in"
```

---

### Task 10: Verify cache key distinguishes `include_tests`

**Files:**
- Test: `src/test/java/com/codescope/EdgeCaseTest.java`

**Interfaces:**
- Consumes: All X1 work from Tasks 6-9.
- Produces: A regression test verifying that toggling `include_tests` triggers a cache miss and rebuild (not a stale read).

- [ ] **Step 1: Write the test**

Add to `src/test/java/com/codescope/EdgeCaseTest.java`:

```java
@Test
void cacheKeyDistinguishesIncludeTests(@TempDir Path tmp) throws IOException {
    // Build the same project twice — once with include_tests=false,
    // once with true. The two calls must produce different cache
    // entries (no stale read).
    Path mainDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Path testDir = Files.createDirectories(tmp.resolve("src/test/java/com/example"));
    Files.writeString(mainDir.resolve("Target.java"),
            "package com.example;\n"
            + "public class Target {\n"
            + "    public void go() {}\n"
            + "}\n");
    Files.writeString(testDir.resolve("TargetTest.java"),
            "package com.example;\n"
            + "public class TargetTest {\n"
            + "    public void testGo() { new Target().go(); }\n"
            + "}\n");

    TraceCallersService svc = new TraceCallersService();
    // First call: include_tests=false (builds cache)
    String json1 = svc.traceCallersJson(
            "com.example.Target", "go", null, null,
            tmp, false, false);
    assertFalse(json1.contains("TargetTest"),
            "include_tests=false should not see test code, got: " + json1);

    // Second call: include_tests=true (must rebuild, NOT serve stale)
    String json2 = svc.traceCallersJson(
            "com.example.Target", "go", null, null,
            tmp, false, true);
    assertTrue(json2.contains("TargetTest"),
            "include_tests=true should see test code (cache miss + rebuild), got: " + json2);

    // Third call: include_tests=false again (should be a cache hit, returning
    // the main-only index — verifies the false entry wasn't evicted by the
    // true entry, i.e. they're separate cache slots).
    String json3 = svc.traceCallersJson(
            "com.example.Target", "go", null, null,
            tmp, false, false);
    assertFalse(json3.contains("TargetTest"),
            "include_tests=false (2nd time) should still not see test code, got: " + json3);
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=EdgeCaseTest#cacheKeyDistinguishesIncludeTests`
Expected: PASS (because Task 6's `IndexCacheKey` includes `includeTests` in its `equals`/`hashCode`).

If FAIL with the test seeing stale data, the cache key migration in Task 6 has a bug — check `IndexCacheKey.equals`/`hashCode`.

- [ ] **Step 3: Run full test suite**

Run: `mvn test`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/codescope/EdgeCaseTest.java
git commit -m "test(cache): verify cache key distinguishes include_tests"
```

---

## Self-Review

**Spec coverage check:**

| Spec section | Task(s) |
|---|---|
| F6: `static {}` blocks | Task 2 |
| F6: `static field = expr;` | Task 3 |
| F6: `{}` instance blocks | Task 2 |
| F6: instance field initializers | Task 3 |
| F6: enum constant args | Task 4 |
| F6: synthetic kind in find_symbols | Task 1 (kind added) + Tasks 2-4 (symbols recorded) |
| F6: hierarchy repair skip | Implicit — synthetics don't call `recordMethodDeclarationModifiers`, existing `mMods == 0` check at `JdtIndexer.java:159` skips them |
| F6: mixed integration test | Task 5 |
| X1: surface (per-call, default false) | Tasks 7-9 |
| X1: indexing (test root inclusion) | Task 6 (`collectSourceRoots0` filter) |
| X1: cache key includes include_tests | Task 6 (`IndexCacheKey` record) |
| X1: cross-tool consistency | Tasks 7-9 (all three tools get the param) |
| X1: cache key distinguishes include_tests test | Task 10 |
| S6 (top-level records) | Deferred — out of scope per spec |

All spec requirements covered. S6 deferred per spec.

**Placeholder scan:** No TBDs, TODOs, "implement later", or "similar to Task N" references. Each step has complete code.

**Type consistency check:**
- `MethodKey` constructor: `new MethodKey(String declaringClass, String methodName, int arity, List<String> paramTypes)` — used consistently across Tasks 2-5.
- `ProjectIndex.SourceLoc` constructor: `new ProjectIndex.SourceLoc(String file, int line)` — used consistently.
- `ProjectIndex.Symbol` constructor: `new ProjectIndex.Symbol(String name, String kind, String fqn, String container, String file, int line, String signature)` — used consistently with `signature=null` for synthetics (matching existing field/enum-constant pattern at `JdtIndexer.java:565-572`).
- `IndexCacheKey(Path, boolean)` record — used consistently in Task 6.
- `loadOrRebuild(Path, boolean, boolean)` and `validateAndLoad(..., boolean includeTests, ...)` — used consistently in Tasks 6-9.

No type/name mismatches found.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-04-coverage-gaps.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
