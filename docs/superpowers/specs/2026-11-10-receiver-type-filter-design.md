# 设计说明：继承 caller 过滤（#7 / #8）与四项增强（#9 quick wins）

日期：2026-11-10
相关 issue：#7（兄弟子类 caller 合并）、#8（继承方法无 receiver 静态类型过滤）、#9（缺失功能，本轮做 4 项）

## 1. 问题

### #7 / #8 是同一个根因

`ProjectIndex` 的方法层级索引 `hierarchy` 是一张**无向**图：`recordHierarchy(child, parent)`
会同时写两条边。`CallChainAnalyzer` 扩展一个 BFS frame 时，遍历
`relatedMethods(key)`（key 自己 + 所有 linked 的方法），并把它们的 caller 全部收进来。

由此产生两类错误归属：

1. **兄弟子类合并（#7）**：`ChildA#save` 与 `ChildB#save` 都 override
   `Parent#save`，于是 `relatedMethods(Parent#save) = {Parent, ChildA, ChildB}`。
   BFS 从 `ChildA#save` 扩展到祖先 `Parent#save` 时，会收走 `Parent#save` 的**所有** caller，
   其中就包括 `ChildB#save`（它只是 `super.save()`）。结果是 `ChildB#save` 出现在
   `ChildA#save` 的 caller 树上——两个兄弟类谁也不可能是谁的 receiver。
2. **继承方法没有 receiver 过滤（#8）**：`ChildA` 继承 `Parent#save`（未 override），
   `new ChildA().save("x")` 被 JDT 绑定到 `Parent#save`，receiver 静态类型是 `ChildA`。
   同一调用点在追 `ChildB#save` 时也会被收进来，但 `ChildA` 的实例永远不可能 dispatch 到
   `ChildB#save`。

`super.save()` 的调用边本身是**精确**的（JDT 把它绑到 `Parent#save` 的具体重载，见
`ReceiverTypeFilterTest.superCallOnThisIsAttributedToTheTargetClass`）。问题不在调用边，
而在层级扩展时"只看方法名+签名，不看 receiver 是谁"。

### #9（本轮范围）

用户选择了"quick wins first"：分页、深度控制、源码片段、模糊/大小写不敏感 + 标注差异。
`#1 接口多态`、`#2 向下 callees`、`#3 外部框架入口`、`#4 mybatis xml` 暂缓。

## 2. 方案

### 2.1 receiver 静态类型

`CallSiteVisitor.recordCall` 为每个调用点额外记录 **receiver 表达式的静态类型**（擦除后的 FQN）：

| 调用形式 | receiver 类型 |
|---|---|
| `expr.m()` | `expr.resolveTypeBinding()` 的擦除 |
| `m()`（隐式 this） | 当前类 |
| `super.m()` / `super::m` | 当前类（dispatch 在 `this` 上，**不是**父类） |
| `Type::m` | `Type.resolveBinding()` |
| 构造器 / `new` | `null`（非虚分派，不参与过滤） |

新增 `ProjectIndex.CallSite(file, line, receiverType)` 承载该信息；`callSitesOf` 的返回类型
随之从 `List<SourceLoc>` 变为 `List<CallSite>`。

### 2.2 祖先扩展过滤

`ProjectIndex.isSubtypeOf(sub, super)` 沿 `typeParents` 上溯（null 视为"未知，不过滤"）。
BFS 扩展 frame `K` 时，对每个 `relatedKey`：

- `relatedKey == K` 或 `relatedKey` 是 **后代**（`K` override 它）→ 不过滤（保持既有
  "override 家族"语义，接口跨边界靠的就是这个方向）；
- `relatedKey` 是 **祖先**（`K` 被它 override）→ 只保留 receiver 类型满足
  `K.declaringClass <: receiverType` 的调用点。

`find_call_sites` 用同一套规则（`FindCallSitesService.collectCallSites`），保证两个工具口径一致。

`super.save()` 的 receiver 记为**当前类**是关键：这样 `ChildB#save` 的 super 调用
（receiver = `ChildB`）在追 `ChildA#save` 时被过滤掉，而 `ChildA#save` 自己的 super 调用
（receiver = `ChildA`）仍然保留为 cycle。

> 手写 `ProjectIndex`（只调 `recordInvocation`、没有 call site）时没有 receiver 信息，
> 过滤跳过——保持既有单元测试的行为不变。

### 2.3 四项增强

| 功能 | 落点 | 默认 |
|---|---|---|
| 深度控制 | `CallChainAnalyzer(maxNodes, maxDepth)`，根帧深度 0；`TraceCallersService` / `TraceCallersTool` / CLI `--max-depth` | 500（上限 1000） |
| 分页 | `FindCallSitesService` 稳定排序后 `[offset, offset+limit)`；响应新增 `total` | 不限 |
| 源码片段 | `SourceSnippet`（相对 root 解析、逐文件缓存、读不到返回 null）；`CallNode.toJson(SnippetResolver)` | 始终附带 |
| 模糊匹配 + 标注 | `ProjectIndex.searchSymbols(..., fuzzy)`：子串失败后做一次 Levenshtein（阈值 `len<=7 ? 1 : 2`）；命中带 `SymbolMatch(symbol, kind, matchedText, distance)` | fuzzy 关闭 |

## 3. 测试

- `ReceiverTypeFilterTest`（4 个）：兄弟合并、继承方法 receiver 过滤、接口兄弟实现、super 的 receiver 语义。
- `SourceSnippetTest`（5 个）：相对/绝对路径、缩进保留、缺失/越界返回 null、`..` 路径。
- `CallChainAnalyzerTest` / `TraceCallersServiceTest`：maxDepth 语义与标记。
- `FindCallSitesServiceTest`：分页窗口、total、非法窗口。
- `FindSymbolsServiceTest`：match 标注（exact / case_insensitive / substring / fuzzy）。
