# repairMethodHierarchyViaTypeHierarchy 性能修复

## Problem

Issue #6 的两个根因之一（评论明确指出，2026-07-14）：

`JdtIndexer.repairMethodHierarchyViaTypeHierarchy` 在大项目里极慢。具体两处：

1. **内层全表扫描**（JdtIndexer.java:173）。`for (MethodKey candidate : index.knownMethods())` 对遍历到的每个 subtype 都把整个 declarations 集合过一遍，按 `declaringClass` / `methodName` / `arity` 三个字段逐条过滤。复杂度是 O(subtypes × methods)。

2. **缺索引**（ProjectIndex.java:403-405）。`knownMethods()` 返回 `declarations.keySet()`，底层是 `ConcurrentHashMap<MethodKey, SourceLoc>`（ProjectIndex.java:54），没有按 (methodName, arity) 建二级索引。每次都全表扫。

在 2000+ 源文件 / 数千个方法的工程里，假设 M=5000 declarations、N=2000 type-hierarchy 中被遍历的 subtypes，单次 build 在这步要 ~10M 次迭代 + equals/hashCode 开销。修复 pass 是 build 末尾的同步阶段，串行阻塞整个 MCP 请求。

2026-07-13 的 spec（`docs/superpowers/specs/2026-07-13-trace-callers-perf-and-line-fix-design.md`）修了 BFS 这一头（fan-out cap + `callersOfSet`），但漏了 build 阶段的这一头。这次补上。

## Design

### Fix: ProjectIndex 加 (name, arity) 二级索引

`src/main/java/com/codescope/ProjectIndex.java` 新增字段，紧挨着 `hierarchy` / `typeHierarchy` 等已有索引（line 75-89 附近）：

```java
// Signature index: for each (methodName, arity) pair, the set of
// declared MethodKeys in the project with that signature. Lets the
// post-build reverse-hierarchy repair pass find same-named,
// same-arity candidates in O(1) per signature lookup instead of an
// O(N) full-scan of {@link #knownMethods()} per subtype, which was
// the build-phase bottleneck for large projects (issue #6).
// Key: NameArity(String name, int arity). Value: live Set<MethodKey>
// populated by {@link #putDeclaration}.
private final Map<NameArity, Set<MethodKey>> bySignature = new ConcurrentHashMap<>();
```

`NameArity` 是 ProjectIndex 内部的 package-private record：

```java
record NameArity(String name, int arity) {}
```

### Write path: putDeclaration 末尾追加

`putDeclaration` (ProjectIndex.java:158-160) 改为：

```java
public void putDeclaration(MethodKey method, SourceLoc loc) {
    declarations.putIfAbsent(method, loc);
    bySignature
        .computeIfAbsent(new NameArity(method.methodName, method.arity),
                k -> ConcurrentHashMap.newKeySet())
        .add(method);
}
```

`putIfAbsent` 让 declaration 只接受第一次写入；`bySignature` 用 `Set.add` 是幂等的（同一 methodName+arity+declaringClass 不会重复进入）。两套数据结构在写时同步维护。

线程安全模式跟 `recordHierarchy` / `recordTypeHierarchy` 一致：外层 `ConcurrentHashMap.computeIfAbsent`，内层 `ConcurrentHashMap.newKeySet()`。

### New public API: methodsWithSignature

`ProjectIndex` 新增方法（紧挨着 `knownMethods()` 后面）：

```java
/**
 * Returns the set of declared MethodKeys whose {@code methodName}
 * and {@code arity} match the given signature, across all declaring
 * classes in the project. Returns an empty set if no project
 * declaration matches.
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

### Repair loop 改动

`JdtIndexer.repairMethodHierarchyViaTypeHierarchy`（JdtIndexer.java:151-187）内层循环（line 173-182）：

```java
// Before
for (MethodKey candidate : index.knownMethods()) {
    if (!candidate.declaringClass.equals(sub)) continue;
    if (!candidate.methodName.equals(m.methodName)) continue;
    if (candidate.arity != m.arity) continue;
    ...
}

// After
for (MethodKey candidate : index.methodsWithSignature(m.methodName, m.arity)) {
    if (!candidate.declaringClass.equals(sub)) continue;
    ...
}
```

外层 `for (MethodKey m : declared)` 仍然走 `index.knownMethods()`（line 152），因为要拿所有 declaration 跑 type-hierarchy 向下遍历——这一层是 O(M)，不在热路径上。

预期复杂度：单次 build 修复 pass 从 O(M × N × C_per_subtype) 降到 O(M × N × K_per_signature)，其中 K_per_signature 实际是个位数（Java 里同名同 arity 方法在工程里通常就 1-2 个；同名同 arity 跨类是真正的"候选 override/implement"，修复 pass 要的正是这些）。

### 行为等价

修复 pass 的语义完全不变：
- 仍然按 `methodName` + `arity` 在 subtype 上找候选
- 仍然按 `declaringClass` 过滤到当前 subtype
- 仍然走 private/static 修饰符门控

只是把"扫整个 declarations 然后过滤"换成"按 (name, arity) 索引直接拿候选"，结果集等价。

## 文件清单

| 操作 | 文件 |
|---|---|
| **修改** | `src/main/java/com/codescope/ProjectIndex.java`（新 NameArity record、bySignature 字段、putDeclaration 写时维护、methodsWithSignature 方法、javadoc） |
| **修改** | `src/main/java/com/codescope/JdtIndexer.java`（修复 pass 内层循环改两行） |
| **新增** | `src/test/java/com/codescope/ProjectIndexSignatureIndexTest.java`（methodsWithSignature 行为 + 写时可见性） |

预计 +40 行生产代码（含 javadoc）、+60 行测试。

## 测试策略

### 新增：ProjectIndexSignatureIndexTest

用 in-memory 写 ProjectIndex（不需要走 JdtIndexer），覆盖：

1. **基础查找**：写 N 个 declaration，调 `methodsWithSignature(name, arity)`，断言返回的 Set 精确匹配
2. **arity 区分**：同名不同 arity 的方法返回不同 Set，互不污染
3. **不存在签名**：返回 `Set.of()`（不是 null）
4. **跨类同名同 arity**：不同 declaringClass 的同名同 arity 方法都在结果里（这是修复 pass 需要的语义）
5. **写时可见**：连续多次 `putDeclaration` 后，索引立即反映所有 entry（验证 write-through）
6. **null name**：返回 `Set.of()`，不抛 NPE

### 回归

跑完整 `mvn test`（当前 181 个测试）。重点关注行为级测试：

- `ProjectIndexInterfaceExtendsAbstractTest.abstractMethodRelatedMethodsIncludesImplementor` — 验证修复 pass 行为不变
- `CallChainAnalyzerTest.privateMethodsAreNotCrossClassHierarchy` — 验证 private/static 门控不变
- `ProjectIndexSubInterfaceTest` — 验证 sub-interface 修复路径不变
- 其余 trace_callers / find_call_sites / find_symbols 测试

如果现有测试有任何一个挂了，说明修复 pass 的语义被破坏了，需要回去检查。

## 非目标

- 不动 `knownMethods()`，继续 public 返回完整 declarations
- 不动 `hierarchy` / `typeHierarchy` / `calls` / `callSites` / `symbols` 等任何已有索引
- 不动 `CallChainAnalyzer` / BFS 路径
- 不做 perf benchmark（行为等价 + 复杂度降一阶已经够说服力；Maven test 跑通即可）
- 不动 public API 契约以外的方法签名
- 不引入新依赖

## 后续可能

- `MAX_CALLERS_PER_FRAME` 已经先一步做了；这次只补 build 阶段
- 如果未来还需要按 declaringClass 反查方法列表（`methodsOf(class)`），可以再加一级索引

## 实现修正(2026-07-19,后置记录)

以下四点在 spec 日期之后被修正,实现已偏离 spec 描述;**未来按 spec 实现会重新引入被修复的 bug**。

### 1. `bySignature` inner 实际是 multimap,而非 spec 描述的 `Set<MethodKey>`

spec 第 31-32 行的伪代码:
```
private final Map<NameArity, Set<MethodKey>> bySignature = new ConcurrentHashMap<>();
```

**实际代码**(参见 `ProjectIndex.java:97`):
```
private final Map<NameArity, Map<String, Set<MethodKey>>> bySignature = new ConcurrentHashMap<>();
```

变更原因:初次实现(`b68dc64`)把 inner 设成 `Map<String, MethodKey>`,key 是 `declaringClass` —— 同一个类里 `save(String)` 和 `save(int)` 共享 `(name, arity)` 外层 key 和 `declaringClass` 内层 key,`.put` 后写覆盖前写。`ProjectIndexSignatureIndexTest.methodsWithSignatureDistinguishesOverloadsByParameterTypes` 的 RED 阶段(`expected: 2 but was: 1`)证实了这个 bug。

修复后的 multimap 让同一个 `declaringClass` 下多个 overload 都保留(外层仍按 `NameArity` 索引),自然支持新的 `methodsInClassWithSignature(sub, ...)` 直接按 `sub` 反查。

### 2. `methodsWithSignature` 改成防御性 snapshot,不再是 live set

spec 第 76-86 行:
> "The returned set is the live index set, not a defensive snapshot."

实际 `methodsWithSignature`(`ProjectIndex.java:443` 起)做 `Set.copyOf(...)`,multimap 的所有 inner Set 加到一个新 `HashSet` 后再 `Set.copyOf`。理由:multimap inner 是 `ConcurrentHashMap.newKeySet()`,对调用方更友好不持有 live 引用。

下游(`repairMethodHierarchyViaTypeHierarchy` 在 `JdtIndexer.java:174`)改用 `methodsInClassWithSignature(sub, ...)`(`ProjectIndex.java:495`)做 per-subtype 查询,直接命中 inner map 的 bucket 而不再迭代整个 outer bucket。

### 3. `methodInClassWithSignature` 在 overload 歧义时抛 `AmbiguousMethodException`

实际 `methodInClassWithSignature(className, methodName, arity)`(`ProjectIndex.java:471`)遇到多个 overload 返回 null 抛出 `ProjectIndex.AmbiguousMethodException`(同文件 `ProjectIndex.java:776` 已有的歧义处理范式),返回 `null` 仅当 0 或 1 个 overload。注意 spec 没有提过这个方法,因为修复 pass 当时还没新增。

### 4. 旧实现没注意到自我引用

原 spec 假设 inner `Set<MethodKey>` 是正确的(因为 `MethodKey` 自身的 equals 已经覆盖 `declaringClass`/`methodName`/`arity`/`parameterTypes` 四元组)。b68dc64 真实实现没用 `Set`,而是 `Map<String, MethodKey>` —— 这才暴露了 round-1 修复的 bug。如果未来 contributor 严格按 spec 把 inner 改回 `Set<MethodKey>`,逻辑上也能 work,但失去了 `methodsInClassWithSignature(sub, ...)` 做 O(1) sub-bucket 查询的能力。鉴于此,**推荐保持 multimap 形状**。
