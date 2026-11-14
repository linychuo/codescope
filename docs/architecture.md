# codescope 架构

一页纸说清代码组织。读完应该能(1) 找到改 bug 该动的文件,(2) 理解为什么索引要反向存,(3) 知道哪儿是性能/正确性的关键路径。

## 包结构

```
com.codescope
├── Main                     入口:无参起 MCP server,有参走 CLI
├── cli/                     Cli
├── mcp/                     McpServer、Tool、AbstractMcpTool + 四个 Tool
├── service/                 TraceCallersService、TraceCalleesService、FindCallSitesService、FindSymbolsService
├── index/                   JdtIndexer、CallSiteVisitor、ProjectIndex、ProjectIndexCache、MethodHierarchyExtractor、MapperXmlIndexer、EntryPoints
├── analysis/                CallChainAnalyzer、CalleeChainAnalyzer、CallNode、MethodResolver
├── model/                   MethodKey
├── dependency/              DependencyResolver(+Factory)、MvnCliDependencyResolver、MavenClasspathResolver、MavenSettings
└── loader/                  ProjectLoader
```

测试包镜像主包结构(如 `McpServerTest` 在 `mcp`,`ProjectIndex*Test` 在 `index`,`CallChain*Test` 在 `analysis`),这样 package-private 的协议/算法内部仍能被直接测试,不必为了跨包访问而扩大 public 表面。

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
   │ FindSymbolsService                  │   share a single LRU indexCache injected by `Main` (MCP path) / held in `Cli.SHARED_CACHE` (CLI path)
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
   (callSites 的 Loc 现在是 ProjectIndex.CallSite：file + line + receiverType，后者用于 issue #7/#8 的继承过滤)
```

## 反向索引 — 为什么是反向的

`trace_callers` 的语义是"找一个方法的所有调用方",对应 BFS 沿 `callee → caller` 这条边走。

`JdtIndexer` 解析源码时看到的是 `MethodInvocation` (caller 体内),即 `caller → callee` 这条边。但写到 `ProjectIndex` 时把它翻过来,key 是 callee,value 是 caller 集合:

```java
// CallSiteVisitor
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
| `McpServer.dispatchAsync` | 只有 `tools/call` 丢到 worker 线程,读循环不阻塞 | 大项目索引耗时数秒;阻塞读循环会收不到 `notifications/cancelled` 和后续请求,`stop()` 也中断不了在飞的工具 |
| `McpServer.sendRequestAwait` | server→client 的反向 RPC 用 `CompletableFuture<JsonNode>` 关联,`pending: id → Future` 先注册再写请求避免被极快响应甩掉,`notifications/cancelled` 直接 cancel | JSON-RPC 2.0 §6.1;响应可能超时、可能带 `error` 字段、可能极快到达,三种都得能正确结束 |
| `CallChainAnalyzer.bfs` | 用 per-path ancestor set 而不是全局 `visited` | 钻石调用 `a→b→d, a→c→d` 不能误标成环;只有当前路径上出现过的祖先才算 cycle |
| `CallSiteVisitor.recordCall` 记 receiver 静态类型 + `CallChainAnalyzer` 按 `isComparable` 过滤 | call site 带 `CallSite.receiverType()`;BFS 对**所有方向**(自身/祖先/后代)的调用点都要求 receiver 与 frame 的 context 类层级可比。context:根帧=用户查询的类 Q(不是解析后目标的声明类),更深帧=`frame.key.declaringClass` | issue #7/#8:否则兄弟子类(`ChildB#save` 调 `super.save`)、以及 trace 继承方法时兄弟 override 的 caller,都会被错误地算作目标方法的 caller |
| `CallChainAnalyzer.maxDepth`(实例字段,默认 500) | BFS 按 caller 层数截断,根帧深度 0;被截断的分支挂 `truncated: true` 标记 | issue #9 深度控制;`toJson` 是迭代的,但 Jackson 序列化是递归的,层级无上限会 StackOverflow |
| `SourceSnippet` | 按 project root 解析索引里的相对路径,逐文件缓存行 | issue #9 源码片段;文件不可读时返回 null(省略 `snippet` 字段),不在结果格式化路径上抛异常 |
| `find_call_sites` / `find_symbols` 输出补齐 | 两个扁平工具对齐 trace 工具:target/条目带 `snippet`、caller 带 `caller_snippet`、`entryPoint`;`total` 报告窗口前的总数;`find_symbols` 支持 `offset` 分页 | 一致性收尾:此前只有 `trace_*` 有入口标记和 caller 片段,`find_symbols` 没有 total/offset |
| `ProjectIndex.searchSymbols(..., fuzzy)` | 子串匹配失败后再做一次 Levenshtein(阈值 `len<=7 ? 1 : 2`);名字匹配失败后还会匹配符号的 `detail`(mapper statement 的 SQL 正文);支持 `offset` 分页,每个命中带 `SymbolMatch` 注释 | issue #9 模糊匹配 + 标注差异 + SQL 内容搜索;`match_type`(含 `detail`)/`field`/`matched`/`case_differs`/`distance` 让不同命中不再长得一样 |
| `ProjectIndex.invokes`(正向边)+ `CalleeChainAnalyzer` | `recordInvocation` 写反向边时同步写正向边;`trace_callees` 沿正向边做 BFS,按 `relatedMethods` 扩展——但只扩展 context 类的**子树实现**(`M.declaringClass <: contextClass`;根帧 context = 用户查询的类,更深帧 = 该方法的声明类) | issue #9-2:以前只能回答"谁调用我",无法回答"我调用谁";扩展过滤是 #8 的 callee 侧对应:继承查询不会把兄弟 override 的 callee 混进来 |
| `ProjectIndex.UnresolvedCall` + `MapperXmlIndexer` 的 XML 扫描 | JDT 无法绑定的调用(项目接口从**类**超类继承方法)记成 `(caller, receiverType, name, arity)`,查询时按 receiver 与目标的层级可比性归属;类型层级同时记录库超类 | issue #9-1 接口多态:`interface extends 抽象类/库类` 时 JDT 的 `getInterfaces()` 不暴露类超类,调用边整条丢失 |
| `MapperXmlIndexer` | StAX 扫 `*.xml`(排除 target/hidden/`src/<...>/test/{java,resources}`——后者由 `include_tests` 控制),根元素是 `<mapper>` 就解析 `namespace` + `select/insert/update/delete/sql` 语句;语句进 `mapperStatements`(SQL 作为符号的 `detail`,可搜)并登记为 `mapper-statement` 符号;`trace_callees` 只把 `select/insert/update/delete` 挂在 mapper 方法下(`<sql>` 是复用片段,不是方法实现) | issue #9-4:mapper 接口方法的"实现"在 XML 里,以前完全不可见;StAX 而不是 DOM 是为了拿起始行号 |
| `EntryPoints` + `CallNode.entryPoint` | 方法注解(FQN 优先,简单名兜底)+ `main` 签名 + 框架回调接口(`ApplicationListener`/`CommandLineRunner`/`ApplicationRunner`/`InitializingBean`)→ 入口标记,注解在 AST 上解析(`resolveAnnotationBinding`),接口名有"写下的简单名"兜底 | issue #9-3:调用链停在 Spring handler / 定时任务 / runner / listener / advice 时,以前看起来像"还有没找到的 caller" |
| `EntryPoints.isTransactional` + `CallNode.transactional` | 方法或类上的 `@Transactional`(Spring/Jakarta/javax)→ 代理边界标记,与入口标记分开 | 真实项目 review:`@Transactional` 在 35 个文件里,是链路语义的一部分但不是入口 |
| `CallSiteVisitor.synthesizeLombokAccessors` | `@Data/@Getter/@Setter/@Value` → 为字段合成 `getX`/`setX`/`isX`(static 跳过、final 无 setter) | JDT 不跑 Lombok 注解处理器,不合成这些方法就是不可见的;真实项目 8 个文件受影响 |
| `MyBatisPlus` | 项目类 `extends ServiceImpl<M, E>` → 记 `IService#method -> M#mapperMethod` 合成边 | 真实项目 review:链路停在库方法 `IService.getById`,看不到项目 mapper |
| `IndexFingerprint` + `ProjectIndexStore` | 索引按「源码/mapper XML 的 mtime+大小 + pom 内容」的指纹序列化到 `~/.cache/codescope/`;`ProjectIndex` 全量 `Serializable` | CLI 单发进程,不缓存每次都要 Maven+JDT;实测 386 文件 ~26s→~2s |
| `CallChainAnalyzer.maxNodes = 50_000`(实例字段,默认 50_000) | 树大小硬上限 | 防止一个热门函数被广泛调用时 BFS 跑飞;package-private `CallChainAnalyzer(int)` 构造器让测试能用小 cap 验证截断行为 |
| `JdtIndexer.build` | 每个源文件一个 virtual thread + `Executors.newVirtualThreadPerTaskExecutor()` | 解析+ binding 解析会卡在 jar I/O 上;虚拟线程的阻塞是廉价的 |
| `JdtIndexer.parseFile` | 解析器 compliance 固定为 JDT 支持的最新级别,并启用 preview 特性 | 默认 compliance 是 Java 1.8,record/var/文本块/switch 表达式等现代语法会被当成语法错误,整个文件被静默丢弃;preview 语法同理 |
| shared `ProjectIndexCache` (injected by `Main`) | 同步 LRU,容量 8;固定大小的锁条(64 条 `ReentrantLock`)按 key 哈希分配,构建在缓存锁外进行 | 长时间会话里 host 可能把同一个工具指向多个 project;所有 MCP 工具共用一个 cache,跨 `tools/call` 复用索引构建结果;锁条让不同 project 并发构建且内存有界,LRU 的读写仍在全局锁内保持正确 |
| `MvnCliDependencyResolver` | 调用 `mvn dependency:build-classpath` 获取真实依赖树;单次调用有超时(默认 600s,`-Dcodescope.mvn.timeout` 覆盖) | 支持私服、镜像、`-gs` 自定义 settings.xml、`CODESCOPE_MVN_ARGS` 环境变量;由 `DependencyResolverFactory` 根据构建文件自动选择;没有超时,卡住的 Maven 会让工具无限挂起 |
| `MavenClasspathResolver` | 保留不用(旧实现,纯文件系统解析 pom) | 作为离线 fallback 备用,不再被 `ProjectLoader` 引用 |
| `ProjectLoader.collectSourceRoots0` | 匹配 `src/<...>/main/java`(test 视 `includeTests`),并并入每个 pom 的 `<build><sourceDirectory>`/`<testSourceDirectory>` | 测试代码默认不参与调用链;Maven 允许自定义源码目录,不读 pom 会漏掉这些文件 |

## 改哪儿 — 常见场景

| 想做的事 | 改哪儿 |
|----------|--------|
| 加一个新的 MCP 工具 | 在 `com.codescope.mcp` 新建一个 `XxxTool extends AbstractMcpTool`,在 `Main` 里 `new McpServer(shared).register(new XxxTool(shared))` |
| 给 CLI 加一个子命令(不动 MCP) | `com.codescope.cli.Cli.dispatch` 添一个 `case`,参数复用对应 `*Service` |
| 修改 `trace_callers` 的入参/出参 schema | `mcp.TraceCallersTool.inputSchema()`(对外)+ `service.TraceCallersService`(业务)+ `mcp.McpServerTest`/`analysis.CallChainAnalyzerTest`(测试) |
| 改 JSON-RPC 协议层行为(分帧、错误码、cancellation) | `mcp.McpServer.java` 一处,改动会反映在 `mcp.McpServerTest` |
| 改 JDT 解析(支持新的 AST 节点类型) | `index.CallSiteVisitor` 里的 `visit(Xxx)` 方法 |
| 改 BFS/cycle 算法 | `analysis.CallChainAnalyzer.bfs`,`analysis.EdgeCaseTest` 是它的回归网 |
| 改依赖解析(支持 Gradle、自定义 settings.xml 等) | `dependency.DependencyResolver`(接口) + `dependency.MvnCliDependencyResolver`(Maven CLI 实现) + `dependency.DependencyResolverFactory`(自动检测),测试在 `dependency.MvnCliDependencyResolverTest` / `dependency.DependencyResolverFactoryTest` |
| 修并发 bug | `index.ProjectIndex`(`ConcurrentHashMap` 外层 + `synchronized(set)` 保护 `LinkedHashSet`),`analysis.EdgeCaseTest.projectIndexIsThreadSafe*` 是直接覆盖 |

## 测试怎么组织的

测试包镜像主包结构(见上):`mcp.*Test` 在 `com.codescope.mcp`,`index.*Test` 在 `com.codescope.index`,`analysis.*Test` 在 `com.codescope.analysis`,依此类推。

- **`analysis.CallChainAnalyzerTest`** — fixture Maven 项目(`src/test/resources/fixture-project`)上的端到端:传递调用、重载、ambiguity、cycle、test 排除、enum/record/annotation 兼容
- **`analysis.EdgeCaseTest`** — 边界:深链(>2000 层不爆栈)、钻石 vs 环、library target、50 000 节点截断、并发写、坏源文件
- **`analysis.HierarchyEdgeCaseTest`** — 方法层级边界:local class 方法、package-private 跨包不链接、record 紧凑构造器、泛型类型变量 override、自递归诊断
- **`mcp.McpServerTest`** — 协议层:JSON-RPC 错误码、cancellation、string id、负 arity、错误响应完成 future 异常并格式化错误码、`method`/`params` 类型校验、尾随字节保留、不完整输入保留
- **`mcp.McpServerStdioTest`** — 真起一个进程跑 stdio(只跑 `McpServerTest` 没覆盖的整条链路):initialize / tools/list / tools/call、错误响应、roots/list 反向 RPC、host 不声明 roots 时不去拉、`result: null` 这类畸形响应不影响后续调用
- **`dependency.MavenClasspathResolverTest` / `dependency.MavenSettingsTest`** — pom 解析各自的边界(旧实现,保留作 offline fallback)
- **`loader.MultiModuleTest`** — 多模块 Maven:跨模块 binding、子模块项目根回退到 aggregator、缓存相对路径
- **`MvnCliDependencyResolverTest` / `DependencyResolverFactoryTest`** — 依赖解析抽象层:解析 `mvn dependency:build-classpath` 输出、错误处理、构建工具自动检测
- **`CliTest`** — CLI 前端(`java -jar codescope.jar <command>`)的协议测试:`Cli.run` 直驱(不触发 `System.exit`),覆盖子命令分发、必填 `--project`、缺位置参数、未知 option / kind、退出码 0/1/2、三个子命令在 codescope 自身上的 happy path。`Cli.dispatch` 改完直接看红绿
- **`FindCallSitesServiceTest`** — `find_call_sites` 服务层:单/多调用点、library target 多 overload union、no-callers / unknown target / ambiguity 诊断、缓存 + `refresh` 在新文件加入后能拾到新调用点
- **`FindSymbolsServiceTest`** — `find_symbols` 服务层:大小写不敏感子串、kind 过滤、各类声明的索引路径(类/方法/构造器/普通字段/enum 常量/record 组件)、no-match 诊断、limit 截断提示、blank query / unknown kind / 缺 `pom.xml` 报错、缓存 + `refresh` 在新文件加入后能拾到新符号
