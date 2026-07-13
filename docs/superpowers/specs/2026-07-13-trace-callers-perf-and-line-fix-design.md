# trace_callers line 修复 + 性能优化

## Problem

仓库当前有两个 open issue，都影响 `trace_callers` 的可用性。

### Issue #5: line 字段指向注释行而非方法签名行

`JdtIndexer.java:555` 在记录方法 declaration 时调用 `cuLine(node)`。`MethodDeclaration.getStartPosition()` 包含前面的 Javadoc 和注解，所以 `cu.getLineNumber()` 返回的是注释起始行，不是方法签名行。

用户拿到 `trace_callers` 结果后无法用 line 直接跳转——点击行号打开的是 Javadoc 注释。

### Issue #6: 分析被广泛调用的方法时执行 10+ 分钟无结果

`CallChainAnalyzer` 的 BFS 单帧复杂度是 **O(R × C)**：

- **R** = `index.relatedMethods(f.key).size()`：当前方法在类型层级中的所有相关方法（接口+所有实现 / 父类+所有子类）
- **C** = 每个相关方法的调用者数量

对于一个被广泛实现的接口方法（如 `IfaceRepository.save`），R 可能达到 100+（一个接口有 100+ 个实现类），C 达到 1000+（每个实现被 1000+ 处调用）。单帧迭代 10 万次，每次都做 `List.copyOf(set)`（C 个元素）。

BFS 处理 50000 帧（MAX_NODES）= 50B 操作。在 10ns/op 的速度下 = 500 秒 ≈ 8 分钟。加上 `relatedMethods` 每次都新建 `TreeSet` 并按 `toString()` 排序的开销，实际执行 10+ 分钟。

根因有两个：
1. `ProjectIndex.callersOf()` 每次都做 `List.copyOf(set)`，O(C) 复制开销
2. BFS 对每个 related method 都调用 `callersOf`，O(R × C) 迭代

## Design

### Fix #1: `JdtIndexer` 用方法名行号

`src/main/java/com/codescope/JdtIndexer.java` 第 555 行：

```java
// Before
int line = cuLine(node);

// After
int line = cuLine(node.getName());
```

`MethodDeclaration.getName()` 返回 `SimpleName` 节点，位置是方法名 token 出现的地方（不含 Javadoc、注解、修饰符）。`cuLine` 内部用 `cu.getLineNumber(pos)` 映射到行号。

这与 `recordTypeSymbol`（第 1030-1032 行）的现有模式一致：
```java
int line = node instanceof AbstractTypeDeclaration atd && atd.getName() != null
        ? cuLine(atd.getName())
        : cuLine(node);
```

**影响**：只影响 `visit(MethodDeclaration)` 中 declaration 的 line 记录，不改变 callSite 的 line（仍用 `cuLine(node)`，因为那是 call expression 节点，不需要修）。

### Fix #2: BFS 加 per-frame fan-out 上限

`src/main/java/com/codescope/CallChainAnalyzer.java` BFS 内层循环（第 92-132 行）。

**当前行为**：对每个 BFS 帧 f，先 `relatedMethods(f.key)` 得到 R 个相关方法，再对每个调 `callersOf(relatedKey)` 收集 C 个调用者。完全展开到 MAX_NODES。

**新行为**：
- 对每个 BFS 帧 f，**先收集**所有 unique callers 到一个 `LinkedHashSet<MethodKey>`（保序去重）
- 收集时计数 `collected`
- 当 `collected > MAX_CALLERS_PER_FRAME`（默认 **500**）时停止收集，给当前节点添加一个 `truncation` 标记子节点，标注被截断的调用者数
- 超过的调用者不进入子节点队列，不再展开

**新字段**（与现有 `cycle`、`truncated` 并列）：
- `CallNode` 增加 `boolean fanoutTruncated` 字段（默认 false），与现有 `truncated`（深度截断）区分
- `CallNode` 增加 `int hiddenCallerCount` 字段（默认 0），对 fanout 截断有效
- 新增 factory `CallNode.fanoutMarker(className, methodName, arity, hiddenCount)` 仿照现有 `cycleMarker` / `depthMarker` 模式
- `toJson()` 序列化时：当 `fanoutTruncated` 为 true 时输出 `truncatedCallers: N`

**配置**：`MAX_CALLERS_PER_FRAME` 作为 `CallChainAnalyzer` 的 static final 常量，默认 500。

**理由**：被广泛调用的方法（如 `log.info`、`StringUtils.isBlank`）在用户的 chain 中是末端节点，继续展开对调试价值有限；截断后用户知道"这里有 N 个调用者被省略"，可以改用更具体的子类方法继续追溯。

### Fix #3: 避免 `List.copyOf` 开销

`src/main/java/com/codescope/ProjectIndex.java` 新增方法：

```java
/**
 * Returns the raw set of callers for {@code target} without copying.
 * 
 * <p>Intended for single-threaded BFS in {@link CallChainAnalyzer}
 * after indexing is complete. NOT a substitute for {@link #callersOf}
 * in concurrent contexts — the returned set is the live index entry,
 * not a defensive copy.
 */
public Set<MethodKey> callersOfSet(MethodKey target) {
    Set<MethodKey> set = calls.get(target);
    return set == null ? Set.of() : set;
}
```

**BFS 改动**：`CallChainAnalyzer` 内 `index.callersOf(relatedKey)` → `index.callersOfSet(relatedKey)`，迭代 Set 而非 List。

**保留**：`callersOf` 保持返回 `List<MethodKey>`，不破坏现有契约。
- `EdgeCaseTest:968` 显式断言 "callersOf returns a List (defensive copy...)"，修改会破坏该测试
- `FindCallSitesService` 等外部使用方不需要改

**线程安全论证**：BFS 在索引构建完成后执行，索引已不可变（`recordInvocation` 只在 `JdtIndexer.parseFile` 的虚拟线程中调用，BFS 入口在 `TraceCallersService.traceCallersJson` 串行调用）。所以直接返回 Set 是安全的。javadoc 显式标注"仅供单线程读路径使用"。

## 文件清单

| 操作 | 文件 |
|---|---|
| **修改** | `src/main/java/com/codescope/JdtIndexer.java`（1 行：第 555 行） |
| **修改** | `src/main/java/com/codescope/CallNode.java`（新增 hiddenCallerCount 字段、truncation 工厂、toJson 输出 truncatedCallers） |
| **修改** | `src/main/java/com/codescope/CallChainAnalyzer.java`（BFS 内层循环重构，加 MAX_CALLERS_PER_FRAME 常量） |
| **修改** | `src/main/java/com/codescope/ProjectIndex.java`（新增 callersOfSet 方法） |
| **新增** | `src/test/java/com/codescope/CallNodeTruncationTest.java`（CallNode 新字段的序列化） |
| **新增** | `src/test/java/com/codescope/MethodLineFixTest.java`（带 Javadoc 的方法，line 指向签名行） |
| **新增** | `src/test/java/com/codescope/CallChainFanoutCapTest.java`（fan-out cap 行为） |
| **新增** | `src/test/java/com/codescope/ProjectIndexCallersOfSetTest.java`（callersOfSet 与 callersOf 内容一致） |

## 测试策略

### Fix #1 测试

新增 fixture（含 Javadoc、注解的方法），断言 `trace_callers` 返回的 `line` 等于方法签名行号而非注释行号。

### Fix #2 测试

构造一个被 1000+ 处调用的方法，断言 BFS：
- 在合理时间（< 5 秒）完成
- 返回的根节点子树中包含 `truncatedCallers: 500`（或实际截断数）
- 子节点数 ≤ 500

### Fix #3 测试

断言 `callersOfSet(M)` 返回的 Set 与 `callersOf(M)` 的 List 去重后内容完全相同。

### 回归

- 跑完整 `mvn test` 套件（当前 181 个测试），确保没有破坏现有契约
- 重点关注 `CallChainAnalyzerTest`、`FindCallSitesServiceTest`、`TraceCallersServiceTest`、`EdgeCaseTest`（line 968 那个 List 契约）

## 后续扩展

- `MAX_CALLERS_PER_FRAME` 后续可以暴露为 MCP tool 参数（`max_callers_per_frame`），让用户按需调整
- `hiddenCallerCount` 后续可以暴露在 result message 中（"Truncated at N callers"），让用户从输出直接感知
