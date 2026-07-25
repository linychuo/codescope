# codescope service-layer refactor

Date: 2026-07-25

## Goal

Eliminate three structural problems found during code review: (1) a per-service
`ProjectIndexCache` instance that defeats cross-tool sharing despite the field
comments claiming it; (2) duplicated method-selector resolution logic across
`TraceCallersService` and `FindCallSitesService`; (3) two oversized files
(`JdtIndexer.java` at 1110 lines, `ProjectIndex.java` at 805 lines) where each
holds several distinct concerns and one matcher is duplicated.

The refactor is **behavior-preserving**. No public API changes. All 219 existing
tests remain green.

## Scope

In scope:
- Lift the `ProjectIndexCache` to a single owner per entry point (`Main` /
  `McpServer` for the MCP path, `Cli` for the CLI path), inject it into each
  service via constructor.
- Extract `MethodResolver` — a stateless helper class consolidating target
  resolution, ambiguity hints, and overload-union suffix formatting.
- Split `JdtIndexer` into `JdtIndexer` (orchestrator) + top-level
  `CallSiteVisitor` + a new `MethodHierarchyExtractor` that owns the
  initial hierarchy-recording and repair-pass logic.
- Extract `matchStrictThenSuffix` — a private static helper inside
  `ProjectIndex` that deduplicates the strict+suffix match loop currently
  appearing in 4 places (`resolveTarget`, `resolveTargetViaAncestors`,
  `findInvokedKeys`, and `findInvokedKeys`'s ancestor walk). `callersOf` is
  exact-match only and stays untouched.

Out of scope:
- Any change to `McpServer` JSON-RPC framing behavior.
- Any change to JDT parsing semantics (new AST node types, new binding
  resolution strategies).
- Any change to call-graph analysis (BFS, cycle detection, fan-out limits).
- Any change to the public MCP tool schemas (input/output shape is identical).
- Sub-package reorganization — files remain siblings in `com.codescope`.

## Design

### 1. Shared `ProjectIndexCache`

**Current state:** each of `TraceCallersService`, `FindCallSitesService`,
`FindSymbolsService` holds a `private final ProjectIndexCache indexCache =
new ProjectIndexCache();`. The field comment on each says the cache is shared,
but the instances are per-service. A session that uses `trace_callers` then
`find_symbols` against the same project will build the index twice.

**Target state:**
- `McpServer` owns one `ProjectIndexCache` instance, supplied through a new
  constructor `(ProjectIndexCache)`; an MCP session shares it across every
  registered tool.
- `Cli.run` owns one `ProjectIndexCache` instance in a static field on the
  class (keyed to the CLI process); every `dispatch()` call inside the same
  process uses the same instance. Process-lifetime — the CLI exits after one
  call so no eviction semantics matter here, but keeping a single instance
  still avoids the per-`new Service()` allocation churn inside
  `dispatch()`.
- Each `*Service` gets a new constructor `(ProjectIndexCache cache)` that sets
  the cache field. The no-arg constructor (current shape) stays and creates its
  own cache as before, so existing tests that `new TraceCallersService()` are
  unchanged.
- `Main.main` instantiates `ProjectIndexCache` once and passes it to
  `new McpServer(cache).register(new TraceCallersTool(cache))` (and friends).
- `Cli.dispatch()` calls `new TraceCallersService(Cli.cache)` (and friends)
  so all three services share the same `Cli.cache` instance for the lifetime
  of the process.

### 2. `MethodResolver` helper

New top-level class in `com.codescope`. Stateless. Three static methods:

```java
public final class MethodResolver {
    private MethodResolver() {}

    public sealed interface Result
            permits Result.ProjectTarget, Result.LibrarySeeds {
        record ProjectTarget(MethodKey target) implements Result {}
        record LibrarySeeds(List<MethodKey> seeds, MethodKey display) implements Result {}
    }

    /**
     * Resolve a user-supplied selector. Either returns the project's
     * MethodKey, or returns the recorded call-edge keys (synthesizing a
     * display key from the user's selector if no edges exist).
     * Ambiguity is surfaced as-is so the caller can wrap it in its own
     * exception type with a hint.
     */
    public static Result resolve(ProjectIndex index,
                                 String className, String methodName,
                                 Integer arity, List<String> paramTypes)
            throws ProjectIndex.AmbiguousMethodException;

    /**
     * Build the user-facing hint shown after an AmbiguousMethodException.
     * Two branches: project overloads are visible (list them), or the
     * class is library-only (advise passing paramTypes).
     */
    public static String overloadsHint(ProjectIndex index,
                                       String className, String methodName,
                                       ProjectIndex.AmbiguousMethodException cause);

    /**
     * Append the "(combined callers across N library overloads: [...])"
     * suffix used by trace_callers and find_call_sites when the
     * library-target resolution bundled multiple overloads into one
     * display key. Returns the empty string when no union happened.
     */
    public static String overloadUnionSuffix(List<MethodKey> seeds);
}
```

Each `*Service` rewrites its `xxxJson` body to:

```java
MethodResolver.Result res = MethodResolver.resolve(index, cls, mth, arity, paramTypes);
MethodKey target = res instanceof MethodResolver.Result.ProjectTarget pt ? pt.target() : null;
List<MethodKey> seeds; MethodKey display;
if (res instanceof MethodResolver.Result.LibrarySeeds ls) {
    seeds = ls.seeds(); display = ls.display();
} else {
    seeds = List.of(target); display = target;
}
// ... business logic using target/seeds/display ...
message += MethodResolver.overloadUnionSuffix(seeds);
```

The existing `AmbiguousMethodException` catch block becomes a single throw
statement using `MethodResolver.overloadsHint(...)`.

### 3. `JdtIndexer` split

**`JdtIndexer.java`** — orchestrator only:
- `public ProjectIndex build(List<Path>, List<String>, List<String>, Path)`
- `private void parseFile(Path, String[], String[], String[], ..., MethodHierarchyExtractor, ...)` — calls into `CallSiteVisitor` for AST walking.
- Anything else that lives at the top level stays (helpers like
  `relativize(...)`).

Everything else moves out.

**`MethodHierarchyExtractor`** (new, top-level):
- Owns the initial hierarchy-recording pass (`recordMethodHierarchy`) and
  the post-pass repair (`repairMethodHierarchyViaTypeHierarchy`).
- `MethodHierarchyExtractor(ProjectIndex index)` — holds a reference to the
  index being built.
- Public methods called by `JdtIndexer` / `CallSiteVisitor` during `build()`:
  - `recordMethodHierarchy(IMethodBinding b, ...)` — called from inside
    `CallSiteVisitor`.
  - `repairMethodHierarchy()` — runs after all files parse.
- The static helpers used by the repair pass (e.g. `paramTypesMatchBySuffix`
  inside `ProjectIndex`, since the repair pass consults the index) stay
  where they are; the extractor only orchestrates.

**`CallSiteVisitor`** — promoted from inner class to top-level class:
- Constructor takes the inputs it currently captures via outer-class scope:
  `(ProjectIndex index, MethodHierarchyExtractor hierarchy, String file, Path
  projectRoot, String[] classpath, String[] sourcepath, String[] encoding)`.
- All `visit`/`endVisit` methods stay in place.
- `recordTypeHierarchyFromBinding(...)`, `recordSupertypesFromAst(...)`,
  `recordMethodHierarchy(...)` either stay on `CallSiteVisitor` as forwarding
  shims to `MethodHierarchyExtractor`, or move entirely to the extractor (no
  outer-class state needed after extraction). Preferred: move them to the
  extractor so `CallSiteVisitor` is purely AST-walking + delegating.

### 4. `matchStrictThenSuffix` extraction

The strict+suffix pattern — "strict equality pass, then FQN-suffix fallback
iff the strict pass added nothing" — currently appears in four places inside
`ProjectIndex`:

1. `resolveTarget` (line ~548): strict pass over `declarations`, then suffix
   pass when `paramTypes != null`. Throws `AmbiguousMethodException` on
   multiple matches in either pass.
2. `resolveTargetViaAncestors` (line ~631): per ancestor, strict pass over
   `declarations`, then suffix pass.
3. `findInvokedKeys` itself (line ~720): strict pass over `calls`, then
   suffix pass when the strict pass is empty.
4. `findInvokedKeys` ancestor walk (line ~748): per ancestor, strict pass
   over `calls`, then suffix pass — this is the bug we just fixed (the
   per-iteration `addedThisClass` counter).

`callersOf(target)` (line ~374) is *not* a duplicate — it returns the exact
callers of the given `MethodKey`, no selector matching, no suffix fallback.

**Refactor:** add a private static helper in `ProjectIndex`:

```java
/**
 * Strict match with strict+suffix fallback. Iterates the candidates
 * with strict equality first; iff the strict pass added nothing AND
 * {@code paramTypes != null}, runs a second pass with FQN-suffix
 * matching. Returns the union (or just the suffix fallback if strict
 * matched nothing).
 *
 * <p>The "iff strict pass added nothing FOR THIS ITERATION" semantics
 * is the key invariant: a strict hit on ancestor A1 must not suppress
 * the suffix fallback on ancestor A2. Each call to this helper runs
 * against one iteration's candidates (e.g. all `declarations` keys for
 * a single ancestor), so the fallback is naturally per-iteration.
 *
 * <p>The caller decides what to do with the result. `resolveTarget`
 * throws {@code AmbiguousMethodException} when the returned list has
 * size > 1; `findInvokedKeys` collects every match.
 */
private static List<MethodKey> matchStrictThenSuffix(
        Iterable<MethodKey> candidates, String cls, String methodName,
        Integer arity, List<String> paramTypes);
```

The four sites reduce to:

```java
// resolveTarget — pass 1 (over declarations), pass 2 (suffix only when paramTypes != null):
List<MethodKey> matches = matchStrictThenSuffix(declarations.keySet(), className, methodName, arity, paramTypes);
if (matches.size() > 1) throw new AmbiguousMethodException(...);
if (!matches.isEmpty()) return matches.get(0);
// pass 2 is the SAME call when paramTypes != null — helper's internal fallback
// already covered the suffix case. resolveTarget's "only when paramTypes != null"
// still applies to the SUFFIX results: helper returns [] when paramTypes is null
// because the strict pass missed and there's nothing to fall back to.

// resolveTargetViaAncestors:
for (String ancestor : walkAncestors(startClass)) {
    List<MethodKey> hits = matchStrictThenSuffix(declarations.keySet(), ancestor, ...);
    if (hits.size() > 1) throw new AmbiguousMethodException(...);
    if (!hits.isEmpty()) return hits.get(0);
}

// findInvokedKeys initial pass:
List<MethodKey> hits = matchStrictThenSuffix(calls.keySet(), className, methodName, arity, paramTypes);
out.addAll(hits);

// findInvokedKeys ancestor walk:
for (String ancestor : walkAncestors(className)) {
    List<MethodKey> hits = matchStrictThenSuffix(calls.keySet(), ancestor, ...);
    out.addAll(hits);  // union-collect
}
```

`paramTypesMatchBySuffix(List<String>, List<String>)` stays as the underlying
suffix-equality predicate called by the helper. Note that the helper's
suffix fallback is gated on `paramTypes != null` exactly as the four call
sites do today — no behavior change.

### 5. Testing

All existing tests pass with no changes:
- `CallChainAnalyzerTest`, `EdgeCaseTest`, `McpServerTest`, `McpServerStdioTest`,
  `CliTest`, `FindCallSitesServiceTest`, `FindSymbolsServiceTest`,
  `MavenClasspathResolverTest`, `MavenSettingsTest`, `MultiModuleTest`,
  `MvnCliDependencyResolverTest`, `DependencyResolverFactoryTest`,
  `McpServerTest`, `MethodHierarchyOverloadTest`,
  `ProjectIndexFindInvokedKeysTest`, `CallChainNodeCapTruncationTest`.

New tests:

- `MethodResolverTest` — covers the three branches of `resolve` (project
  target, library seeds, library with synthesized display), both branches of
  `overloadsHint` (project overloads visible, library-only), and the
  union-suffix empty / non-empty cases.
- `SharedIndexCacheTest` — drives an `McpServer` (or its collaborators) with
  two services; the second call must observe the same `ProjectIndex` instance
  built by the first (verified via a `ProjectIndexCache` test seam or by
  recording build time and asserting equality).
- `ProjectIndexStrictSuffixTest` — small targeted unit test for
  `matchStrictThenSuffix`. Drives the helper directly via a package-private
  accessor on `ProjectIndex` (the helper itself stays private to avoid
  leaking its existence as an API). Covers: strict pass returns matches;
  suffix fallback only runs when strict pass is empty; per-iteration
  fallback (regression for the bug fixed in 2026-07-25).

## Migration order

Each step is independently shippable; do them in this order to keep diffs
reviewable:

1. **`matchStrictThenSuffix` extraction (smallest, internal)** — extract
   the helper inside `ProjectIndex`; rename callers; rerun full suite;
   add `ProjectIndexStrictSuffixTest`.
2. **`MethodResolver` extraction** — add helper, rewrite the two services to
   use it; rerun full suite; add `MethodResolverTest`.
3. **`JdtIndexer` split** — top-level `CallSiteVisitor`, new
   `MethodHierarchyExtractor`; rerun full suite.
4. **Shared index cache** — changes to `Main`, `McpServer`, `Cli`, the three
   `*Service` constructors; rerun full suite; add `SharedIndexCacheTest`.

After every step: `mvn test` must show all 219 baseline tests still passing
plus any new tests added in the step.

## Non-goals

- Not changing JSON envelope shape.
- Not changing MCP tool schemas (`inputSchema()` output stays byte-identical).
- Not unifying per-service error types (`TraceCallersException`,
  `FindCallSitesException`, `FindSymbolsException` stay separate — each
  service still owns its own exception for caller-friendly wrapping).
- Not adding a sub-package structure.
