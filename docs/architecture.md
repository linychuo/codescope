# codescope 架构

一页纸说清代码组织。读完应该能(1) 找到改 bug 该动的文件,(2) 理解为什么索引要反向存,(3) 知道哪儿是性能/正确性的关键路径。

## 请求流(从 stdio 进来一路向下)

`Main.main` 是唯一入口,按 `args` 分流:

- `args.length == 0` → 起 MCP server (`new McpServer().run()`)
- `args.length > 0` → 调 `Cli.main(args)`,CLI 模式,绕开 JSON-RPC 直接走
  `*Service`,一次性 stdout 打完退出

MCP server 路径:

```
              stdio (JSON-RPC 2.0, 一行一个对象)
                  │
                  ▼
   ┌──────────────────────────────┐
   │ Main.main                    │   唯一的入口; new McpServer().run()
   └──────────────┬───────────────┘
                  ▼
   ┌──────────────────────────────┐
   │ McpServer                    │   分帧、JSON-RPC 调度、roots/list 反向 RPC
   │  - tryParseAndDispatch       │   一次消费一个完整对象,保留尾随字节
   │  - handle                    │   initialize / tools/list / tools/call
   │  - sendRequestAwait          │   server→client 请求(roots/list)
   │  - pending: id → Future      │   服务端挂起请求的关联表
   └──────────────┬───────────────┘
                  ▼ tools/call
        ┌─────────┼─────────────┐
        ▼         ▼             ▼
   ┌────────┐ ┌────────┐ ┌────────────────┐
   │ ...Tool│ │ ...Tool│ │ FindSymbolsTool│   MCP 适配层:参数校验、project 解析
   │  同模式 │ │  同模式 │ │  - inputSchema │   JSON-Schema,host 用它生成 UI
   │        │ │        │ │  - invoke(args)│   → FindSymbolsService
   └────┬───┘ └────┬───┘ └────────┬───────┘
        ▼         ▼              ▼
   ┌─────────────────────────────────────┐
   │ TraceCallersService / ... /         │   业务编排
   │ FindSymbolsService                  │   都各自有 LRU indexCache
   │  - LRU indexCache (8 项)            │   同 project 路径只构建一次,跨会话复用
   └─────┬───────────────────────────────┘
         │
         ▼
   ┌────────────┐
   │ JdtIndexer │   构建索引(单次跑出全部数据:方法调用 + 符号表)
   └────────────┘
         │
         ▼
   ┌─────────────────────────────────────┐
   │ ProjectIndex                        │
   │  - calls:   callee → {caller...}    │  ← trace_callers 用
   │  - callSites: caller+callee → Loc[] │  ← find_call_sites 用
   │  - declarations: method → SourceLoc │  ← 工具通用
   │  - symbols: name → List<Symbol>     │  ← find_symbols 用(子串检索)
   │  - skippedFiles: List<String>       │   解析失败的文件名+原因
   └─────────────────────────────────────┘
```

## 反向索引 — 为什么是反向的

`trace_callers` 的语义是"找一个方法的所有调用方",对应 BFS 沿 `callee → caller` 这条边走。

`JdtIndexer` 解析源码时看到的是 `MethodInvocation` (caller 体内),即 `caller → callee` 这条边。但写到 `ProjectIndex` 时把它翻过来,key 是 callee,value 是 caller 集合:

```java
// JdtIndexer.CallSiteVisitor
index.recordInvocation(methodStack.peek().key, target);  // caller, callee
// ProjectIndex 内部按 callee 建表
calls.computeIfAbsent(callee, k -> new LinkedHashSet<>()).add(caller);
```

带来的几个非显然后果:

- **library target 也能查**:`calls` 表里只关心"谁调用了 X",X 是不是声明在项目里无关紧要 —— `JdtIndexer` 解析 binding 时把 `callee` 的全限定名+签名记下来,library 方法没声明但有调用方就照样能查。
- **重载必须 disambiguate 到 MethodKey 粒度**:`(class, methodName)` 不足以定位一条边 —— 两个重载的调用方可能不一样,`MethodKey` 用 `(class, name, arity, paramTypes)` 四元组锁唯一性。
- **`recordInvocation(caller, callee)` 写的是 caller 的 key,被多个 callee 共享** —— 这就是为什么 `LinkedHashSet` 比 `ArrayList.contains` 在热门函数上快得多(50 000 个 caller 也只是 O(1) 插入/查询)。

## 关键路径上的几个非显然设计

| 文件 | 决策 | 原因 |
|------|------|------|
| `McpServer.tryParseAndDispatch` | 一次只消费一个 JSON 对象,尾随字节保留 | MCP host 不保证一请求一行,可能是粘包;不能因为中途有脏数据就把整段丢 |
| `McpServer.sendRequestAwait` | server→client 的反向 RPC 用 `CompletableFuture<JsonNode>` 关联,`pending: id → Future` 先注册再写请求避免被极快响应甩掉,`notifications/cancelled` 直接 cancel | JSON-RPC 2.0 §6.1;响应可能超时、可能带 `error` 字段、可能极快到达,三种都得能正确结束 |
| `CallChainAnalyzer.bfs` | 用 per-path ancestor set 而不是全局 `visited` | 钻石调用 `a→b→d, a→c→d` 不能误标成环;只有当前路径上出现过的祖先才算 cycle |
| `CallChainAnalyzer.MAX_NODES = 50_000` | 树大小硬上限 | 防止一个热门函数被广泛调用时 BFS 跑飞 |
| `JdtIndexer.build` | 每个源文件一个 virtual thread + `Executors.newVirtualThreadPerTaskExecutor()` | 解析+ binding 解析会卡在 jar I/O 上;虚拟线程的阻塞是廉价的 |
| `TraceCallersService.indexCache` | 同步 LRU,容量 8 | 长时间会话里 host 可能把同一个工具指向多个 project;缓存命中省得每次都重做 pom 解析和文件扫描 |
| `MavenClasspathResolver` | 只看 `~/.m2/repository` 不联网 | 离线工作;transitive 依赖靠本地的 `.pom` 递归走 |
| `ProjectLoader.collectSourceRoots0` | 只匹配 `src/<...>/main/java`,**排除 test** | 测试代码不参与调用链;`src/test` 是另一棵子树 |

## 改哪儿 — 常见场景

| 想做的事 | 改哪儿 |
|----------|--------|
| 加一个新的 MCP 工具 | 新建一个 `XxxTool implements Tool`,在 `Main` 里 `new McpServer().register(new XxxTool())` |
| 给 CLI 加一个子命令(不动 MCP) | `Cli.dispatch` 添一个 `case`,参数复用对应 `*Service` |
| 修改 `trace_callers` 的入参/出参 schema | `TraceCallersTool.inputSchema()`(对外)+ `TraceCallersService`(业务)+ `McpServerTest`/`CallChainAnalyzerTest`(测试) |
| 改 JSON-RPC 协议层行为(分帧、错误码、cancellation) | `McpServer.java` 一处,改动会反映在 `McpServerTest` |
| 改 JDT 解析(支持新的 AST 节点类型) | `JdtIndexer.CallSiteVisitor` 里的 `visit(Xxx)` 方法 |
| 改 BFS/cycle 算法 | `CallChainAnalyzer.bfs`,`EdgeCaseTest` 是它的回归网 |
| 改 Maven 依赖解析(支持 settings.xml 镜像、Gradle 等) | `MavenClasspathResolver` + `MavenSettings`,测试在 `MavenClasspathResolverTest` / `MavenSettingsTest` |
| 修并发 bug | `ProjectIndex`(`ConcurrentHashMap` 外层 + `synchronized(set)` 保护 `LinkedHashSet`),`EdgeCaseTest.projectIndexIsThreadSafe*` 是直接覆盖 |

## 测试怎么组织的

- **`CallChainAnalyzerTest`** — fixture Maven 项目(`src/test/resources/fixture-project`)上的端到端:传递调用、重载、ambiguity、cycle、test 排除、enum/record/annotation 兼容
- **`EdgeCaseTest`** — 边界:深链(>2000 层不爆栈)、钻石 vs 环、library target、50 000 节点截断、并发写、坏源文件
- **`McpServerTest`** — 协议层:JSON-RPC 错误码、cancellation、string id、负 arity、错误响应完成 future 异常并格式化错误码、`method`/`params` 类型校验、尾随字节保留、不完整输入保留
- **`McpServerStdioTest`** — 真起一个进程跑 stdio(只跑 `McpServerTest` 没覆盖的整条链路):initialize / tools/list / tools/call、错误响应、roots/list 反向 RPC、host 不声明 roots 时不去拉、`result: null` 这类畸形响应不影响后续调用
- **`MavenClasspathResolverTest` / `MavenSettingsTest` / `MultiModuleTest`** — pom 解析各自的边界
- **`CliTest`** — CLI 前端(`java -jar codescope.jar <command>`)的协议测试:`Cli.run` 直驱(不触发 `System.exit`),覆盖子命令分发、必填 `--project`、缺位置参数、未知 option / kind、退出码 0/1/2、三个子命令在 codescope 自身上的 happy path。`Cli.dispatch` 改完直接看红绿
- **`FindCallSitesServiceTest`** — `find_call_sites` 服务层:单/多调用点、library target 多 overload union、no-callers / unknown target / ambiguity 诊断、缓存 + `refresh` 在新文件加入后能拾到新调用点
- **`FindSymbolsServiceTest`** — `find_symbols` 服务层:大小写不敏感子串、kind 过滤、各类声明的索引路径(类/方法/构造器/普通字段/enum 常量/record 组件)、no-match 诊断、limit 截断提示、blank query / unknown kind / 缺 `pom.xml` 报错、缓存 + `refresh` 在新文件加入后能拾到新符号
