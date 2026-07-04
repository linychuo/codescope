# Coverage gaps — initializer/static-block/enum-arg calls + test source inclusion

**Date:** 2026-07-04
**Bundle:** A (Coverage gaps) — first of four improvement bundles identified in the codescope tool audit.
**Scope:** F6 (calls outside `MethodDeclaration` are silently dropped) + X1 (`src/test/java` silently excluded).
**Out of scope:** S6 (top-level records under JDT 3.46) — deferred pending verification of the uncommitted JDT 3.45 → 3.46 bump in `pom.xml`. Will be a separate small spec or no-op.

## Background

The codescope audit (`docs/audit-non-analysis-layers-2026-06-10.md:57-59`) explicitly documents that calls outside any `MethodDeclaration` are silently dropped — the indexer's `methodStack` is empty, so `recordCall` returns early at `JdtIndexer.java:642`. Affected source contexts:

1. `static {}` blocks
2. Instance `{}` initializer blocks
3. Field initializers (`private Logger logger = LoggerFactory.getLogger();`)
4. Enum constant constructor arguments (`enum E { A(foo()) }`)

Additionally, `ProjectLoader.collectSourceRoots0` (`ProjectLoader.java:340-343`) only matches `src/<X>/main/java` — `src/test/java` is silently excluded. A user asking "is this method called from any test?" gets a confident wrong answer ("No callers found") with no hint that tests were excluded.

Both gaps produce **incomplete results** — the user's primary reported pain with codescope at work.

## Goal

Eliminate both classes of silent drops. After this work:

- `trace_callers(target)` finds callers that live in static blocks, instance blocks, field initializers, and enum constant args.
- `find_call_sites(target)` returns call sites in those same contexts.
- `find_symbols` returns synthetic methods for `<clinit>` / `<class-init>` (filterable via `kind=synthetic`).
- All three tools accept an `include_tests: boolean` parameter (default `false`) that opts test sources into the index.

## F6 — Initializer / static-block / enum-arg call coverage

### Attribution scheme (per-class synthetic)

When the JDT visitor enters any of these contexts, push a synthetic `MethodContext` so calls inside get attributed instead of dropped:

| Source context | Synthetic caller key | Rationale |
|---|---|---|
| `static {}` block | `pkg.Cls.<clinit>/0` | JVM-faithful — runs once at class init |
| `static FieldType field = expr;` | `pkg.Cls.<clinit>/0` | Same init pass |
| `{}` instance block | `pkg.Cls.<class-init>/0` | Separate from explicit `<init>` to avoid key collision with declared constructors |
| `FieldType field = expr;` (instance) | `pkg.Cls.<class-init>/0` | Same init pass |
| `enum E { A(foo(), bar()) }` | `pkg.E.<clinit>/0` | Enum constants are conceptually `public static final E A = new E(args);` — args evaluated during static init, same as static field inits |

Note: JDT uses the class simple name for constructors (e.g. `pkg.E.E/2`), not `<init>` — see comment at `JdtIndexer.java:540-541`. Synthetic keys use `<`-prefixed names (`<clinit>`, `<class-init>`) which cannot collide with any source method name.

### Visitor changes in `JdtIndexer.CallSiteVisitor`

1. **`visit(Initializer node)`** (new override) — push `<clinit>` if `node.isStatic()`, else `<class-init>`. Pop in `endVisit`. Return `true`.
2. **`visit(EnumConstantDeclaration node)`** (new override) — push `pkg.<EnumCls>.<clinit>/0`. Pop in `endVisit`. Return `true` (descend so args get visited).
3. **`visit(FieldDeclaration node)`** (modify) — keep symbol recording as today, but change return from `false` to `true`. Also push `<clinit>` if `Modifier.isStatic(node.getModifiers())`, else `<class-init>`. Pop in `endVisit`.

`recordCall()` (`JdtIndexer.java:640-655`) is unchanged — it already reads `methodStack.peek()` and the early-return at line 642 just works once something is on the stack for these contexts.

### Synthetic methods in the index

- **Declaration**: `index.putDeclaration(<clinit>, loc)` and `(<class-init>, loc)` at the first init site's line (or the type's line if no init block). Lets `find_call_sites` and `trace_callers` resolve the synthetic to a location.
- **find_symbols**: synthetic methods appear with `kind: "synthetic"` (new kind in `FindSymbolsService.VALID_KINDS`). Name is `<clinit>` / `<class-init>`; `fqn` is `pkg.Cls.<clinit>/0`. Lets an LLM ask "show me all classes that have static init" — useful for finding side-effecting class loads.
- **Hierarchy repair**: the post-build reverse-hierarchy repair pass (`JdtIndexer:522-526` records JDT modifiers to skip private/static methods) must skip these synthetics — they're class-specific, not inherited. Skip by checking the `<` prefix in `methodName` — no source method name can start with `<` (Java reserves `<` for JVM-level identifiers like `<init>`/`<clinit>`, and JDT uses the class simple name for constructors anyway, so `<`-prefixed names exist only as our synthetics).

### Dead-end honesty

`<clinit>/0` and `<class-init>/0` have **no source callers** — class loading is runtime, not in source. `trace_callers` of these keys returns an empty tree. This is honest: the static init isn't triggered by a method call in the project. The user's actual win is the *other direction* — `trace_callers(LoggerFactory.getLogger)` now finds the `<clinit>` that calls it, instead of dropping the edge.

### Non-Goals (F6)

- No synthetic call edges from `<clinit>` to constructors for enum constants. The implicit `new E(args)` inside enum constants is not visited as a `ClassInstanceCreation` by JDT; we do not emit a synthetic edge. Enum constant args are attributed to `<clinit>` directly — that's the win. The constructor itself having `<clinit>` as a caller would require a separate synthetic edge we're not adding.
- No synthetic call edges from explicit `<init>` constructors → `<class-init>`. Field initializers don't have a statically-resolvable "caller" constructor (they're inlined into every constructor at runtime). `trace_callers` stops at `<class-init>`.
- No `@Test` annotation filtering on test sources — that's a separate parameter if ever needed.

## X1 — `include_tests` parameter

### Surface

Add `include_tests: boolean` parameter to all three tools' `inputSchema`. Default `false` (preserves current behavior). Same parameter name and semantics across all three tools.

### Effect on indexing

`ProjectLoader.collectSourceRoots0` (`ProjectLoader.java:340-343`) currently only matches `src/<X>/main/java`. When `include_tests=true`, also match `src/<X>/test/java`. Test sources follow the same dedup and filtering rules as main sources.

### Cache key

`ProjectIndexCache` currently keys on `projectRoot` (Path). Extend the key to include `include_tests`:

```java
record IndexCacheKey(Path projectRoot, boolean includeTests) {}
```

Change `Map<Path, ProjectIndex>` to `Map<IndexCacheKey, ProjectIndex>`. This creates up to two LRU slots per project (one for main, one for main+test) — acceptable at LRU capacity 8. Re-indexing on toggle is standard cache behavior.

### Cross-tool consistency

| Tool | Behavior when `include_tests=true` |
|---|---|
| `trace_callers` | Test methods appear in the tree as callers |
| `find_call_sites` | Call sites in test code appear in the flat list |
| `find_symbols` | Test classes/methods/fields appear in results |

### Interaction with existing parameters

- `refresh: true` clears only the cache entry for the *current* `(projectRoot, includeTests)` pair — does not touch the other entry. Semantics: "I edited a file, rebuild what I'm asking about."
- MCP `roots` (host-declared default project) still works; `include_tests` is orthogonal.

### Edge cases

- `src/test/java` doesn't exist → skip silently, no error.
- Test class extends a main class → both indexed; cross-hierarchy edges between test and main classes work as expected.
- `@Test`-annotated methods in test sources → treated as regular methods; `include_tests` is a source-root inclusion, not an annotation filter.

## Testing

### F6 — new tests in `EdgeCaseTest`

Following the existing pattern (integration style via `JdtIndexer.build` + unit style via direct `ProjectIndex` manipulation):

| Test | Scenario | Assertion |
|---|---|---|
| `staticBlockCallersFound` | Class with `static {}` calling `foo()` | `trace_callers(foo)` finds `Cls.<clinit>/0` |
| `staticFieldInitializerCallersFound` | `static Logger LOG = LoggerFactory.getLogger();` | `trace_callers(getLogger)` finds `Cls.<clinit>/0` |
| `instanceBlockCallersFound` | Class with `{}` calling `foo()` | `trace_callers(foo)` finds `Cls.<class-init>/0` |
| `instanceFieldInitializerCallersFound` | `List<X> xs = buildList();` | `trace_callers(buildList)` finds `Cls.<class-init>/0` |
| `enumConstantArgCallersFound` | `enum E { A(foo()) }` | `trace_callers(foo)` finds `E.<clinit>/0` |
| `syntheticMethodsInFindSymbols` | Class with static init | `find_symbols kind=synthetic` returns `<clinit>` |
| `mixedMainAndInitCalls` | Class with both `<clinit>` and `<class-init>` | Both distinct; call sites attribute to the right one |

### X1 — new tests

| Test | Assertion |
|---|---|
| `includeTestsFalseExcludesTestSources` (default) | Test roots not indexed; `trace_callers` doesn't find callers in test code |
| `includeTestsTrueIncludesTestCallers` | Test methods appear as callers |
| `cacheKeyDistinguishesIncludeTests` | `include_tests=false` then `=true` on same project builds twice (cache miss); same value twice is a hit |
| `findSymbolsIncludeTests` | `find_symbols` returns test classes only when `include_tests=true` |

### Existing tests

All 125 existing tests must continue to pass. The `false` default preserves current behavior.

## Risks

- **Synthetic method name collision**: a source method literally named `<clinit>` is impossible at the JVM level but conceivable in weird source. Mitigated by `<` prefix being rare in source — low risk.
- **Synthetic context push/pop correctness**: must ensure `endVisit` pops consistently, even if an exception is thrown mid-traversal. JDT AST visitors call `endVisit` whenever `visit` returned `true` regardless of subsequent traversal — same pattern as the existing `MethodDeclaration` handling.
- **Class with explicit constructors but no instance init code**: no `<class-init>` synthesized. Correct — nothing to attribute.
- **Cache key migration**: changing the `ProjectIndexCache` key type from `Path` to `IndexCacheKey` touches every call site of `loadOrRebuild`. There's a single `loadOrRebuild` entry point today (`ProjectIndexCache.java:68`); widening its signature to `(projectRoot, includeTests)` is a contained change — callers are the three `*Service` classes.

## Success criteria

- An LLM consumer running `trace_callers` on a popular method (`LoggerFactory.getLogger`, `Objects.requireNonNull`) finds callers in static blocks, instance blocks, field initializers, and enum constant args.
- An LLM consumer can pass `include_tests=true` to find test callers/symbols.
- `find_symbols kind=synthetic` returns synthetic init methods.
- All 125 existing tests still pass; new tests above also pass.

## Out of scope (deferred to other specs in the audit bundle series)

- **Theme B** (output richness): call-site location in tree, column in `SourceLoc`, full signatures, return types + modifiers, field types, enum_constant vs record_component distinction.
- **Theme C** (filters & scoping): `max_depth`, `exclude`, `limit` on find_call_sites, `kind` array, `mode`, `package` filter.
- **Theme D** (infrastructure): shared `ProjectIndexCache`, mtime staleness, `skipped_files` list, `error_code`, Gradle support, well-known API jars.
- **S6** (top-level records): verify JDT 3.46 first; small fix or no-op.
