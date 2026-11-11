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

## 4. 剩余四项（#9-1 ~ #9-4）

### #9-1 接口多态未展开

复现：receiver 是**项目接口**、且方法是从**类**超类继承时（`interface I extends AbsBase`、
`interface MyList extends ArrayList<String>`），JDT 的 `ITypeBinding.getInterfaces()`
不暴露类超类，`resolveMethodBinding()` 返回 null，调用边整条不进索引。

两处修复：

1. **类型层级记录库超类**（`CallSiteVisitor.recordSupertypesFromAst` /
   `recordTypeHierarchyFromBinding` 去掉 `isFromSource` 门）。这样
   `isSubtypeOf`、`resolveTargetViaAncestors`、`findInvokedKeys` 都能走到库类型。
2. **unresolved-call 回退**：绑定为 null 时记 `UnresolvedCall(caller, receiverType,
   name, arity, loc)`；查询时 `unresolvedCallersOf(target)` 按 name+arity 且
   receiver 与目标类**层级可比**（`isSubtypeOf(t, r) || isSubtypeOf(r, t)`）归属。
   兄弟类不可比，所以 #7/#8 的不串味约束仍然成立。

### #9-2 trace_callees（向下）

`ProjectIndex.recordInvocation` 写反向边时同步写 `invokes[caller]` 正向边；
`CalleeChainAnalyzer` 沿正向边 BFS，按 `relatedMethods` 扩展（接口方法的树包含
实现类的方法体），复用 `CallNode` 的 marker/截断语义，子数组序列化为 `callees`。
新增 `TraceCalleesService` / `TraceCalleesTool` / CLI `trace-callees`。

### #9-3 外部框架入口

`EntryPoints` 在索引期检测方法注解（AST 注解 → `resolveAnnotationBinding` 拿 FQN；
拿不到时用写下的简单名兜底）与 `main` 签名，记成 `ProjectIndex.entryPoints`。
`CallChainAnalyzer` / `CalleeChainAnalyzer` 给节点打 `entryPoint` 标记
（`spring-mvc` / `spring-scheduled` / `spring-event` / `spring-kafka` /
`spring-rabbit` / `spring-jms` / `junit5` / `junit4` / `testng` / `main`）。

### #9-4 mybatis xml

`MapperXmlIndexer` 用 StAX 扫 `*.xml`（排除 target/hidden），根元素是
`<mapper>` 就解析 `namespace` 和 `select/insert/update/delete/sql` 语句
（嵌套动态 SQL 标签保留为文本，空白折叠成一行）。语句进 `mapperStatements`，
并登记为 `mapper-statement` 符号（`find_symbols` 可按 id 搜）。
`CalleeChainAnalyzer` 在 mapper 方法节点下挂 SQL 叶子（`sql: {type, text}`）。
DTD/外部实体被禁用（XXE 防护）。

## 5. 测试（剩余四项）

- `InterfacePolymorphismTest`（3 个）：接口继承抽象类 / 库类、兄弟接口不串味。
- `TraceCalleesServiceTest`（5 个）：链、maxDepth、叶子、接口展开、非法深度。
- `EntryPointTest`（6 个）：Spring 方法/类注解、Scheduled/EventListener、JUnit、main、普通方法。
- `MapperXmlTest`（4 个）：语句索引 + SQL 挂载、动态标签、非 mapper XML 忽略、DOCTYPE。
