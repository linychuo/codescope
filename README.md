# codescope

一个基于 Eclipse JDT 的 MCP (Model Context Protocol) 工具,给定一个 Java 类+方法名,
返回**所有调用这个方法的方法**的嵌套树,沿调用链一直向上,直到没有更多调用方为止。

底层用的是 Eclipse JDT 3.45 (`org.eclipse.jdt.core` 的 ASTParser 带 binding resolution),
也就是 [Eclipse JDT Language Server (jdtls)](https://projects.eclipse.org/projects/eclipse.jdt.ls)
跑的那套解析引擎。

## 工具

三个工具,共享同一个索引缓存。`trace_callers` / `find_call_sites` 共享一类 args
schema(`class` / `method` / `arity` / `paramTypes` / `project` / `refresh`),`find_symbols`
用另一套(`query` / `kind` / `project` / `refresh` / `limit`):

### `trace_callers`

返回目标方法的**所有调用方**嵌套调用链树,沿调用链一直向上,直到没有更多调用方为止。

**输入** (`arguments`):

| 字段 | 必填 | 说明 |
|------|------|------|
| `class` | 是 | 类的完全限定名,例如 `com.example.Foo` |
| `method` | 是 | 方法名,例如 `bar` |
| `arity` | 否 | 参数个数;用于同名重载消歧。`paramTypes` 更精确时优先用 `paramTypes` |
| `paramTypes` | 否 | 参数类型的完全限定名数组,例如 `["int", "java.lang.String"]`;和 `arity` 一起用来挑出唯一重载 |
| `project` | 否 | Maven 项目根目录的绝对路径。优先级:本参数 > MCP host 声明的 `roots` > 当前工作目录 |
| `refresh` | 否 | `boolean`,默认 `false`。为 `true` 时清掉该 project 的索引缓存并重建 —— 缓存是进程级的,改完源码后调一次 `refresh: true` 才能看到新调用方 |

不传 `arity`/`paramTypes` 而同名方法有多个重载,会报
`AmbiguousMethodException` 并列出所有候选重载,让你在下次调用里补上。

**输出**: 嵌套 JSON 树。

```json
{
  "target": {
    "class": "com.example.Target",
    "method": "leaf",
    "arity": 0,
    "signature": "com.example.Target#leaf/0",
    "file": "src/main/java/com/example/Target.java",
    "line": 4,
    "callers": [
      {
        "class": "com.example.Mid",
        "method": "callsLeaf",
        "arity": 0,
        "signature": "com.example.Mid#callsLeaf/0",
        "file": "src/main/java/com/example/Mid.java",
        "line": 6,
        "callers": [
          {
            "class": "com.example.Top",
            "method": "entryPoint",
            "arity": 0,
            "signature": "com.example.Top#entryPoint/0",
            "file": "src/main/java/com/example/Top.java",
            "line": 6,
            "callers": [
              {
                "class": "com.example.SideBranch",
                "method": "branch",
                "arity": 0,
                "signature": "com.example.SideBranch#branch/0",
                "file": "src/main/java/com/example/SideBranch.java",
                "line": 6
              }
            ]
          }
        ]
      }
    ]
  },
  "status": "ok",
  "message": "OK; 4 caller(s) in chain."
}
```

如果某个 caller 把方法传给已经出现过的祖先方法(成环),会在那个父节点上挂一个
带 `"cycle": true` 的占位节点,树保持有限。

### `find_call_sites`

返回目标方法在项目源码里的**所有调用点**(扁平列表),每条记录回答"是谁在哪一行
调了它"。和 `trace_callers` 的区别:`trace_callers` 给一棵调用链树(且只看 caller
方法**自身**的声明位置),`find_call_sites` 只看**直接调用点**,每条记录里有
调用的精确行号。同一个 caller 调多次同一 callee 会按出现顺序全部出现(不按行
去重,因为同一行合法地可以有两个调用)。

**输入** 与 `trace_callers` 完全相同。

**输出**: 扁平 JSON 数组,按 `caller_file / caller_line / call_site.line`
稳定排序。

```json
{
  "target": {
    "class": "com.example.Target",
    "method": "leaf",
    "arity": 0,
    "signature": "com.example.Target#leaf/0",
    "file": "src/main/java/com/example/Target.java",
    "line": 5
  },
  "status": "ok",
  "message": "Found 2 call sites across 1 caller method.",
  "call_sites": [
    {
      "caller": "com.example.Mid#callsLeaf/0",
      "caller_file": "src/main/java/com/example/Mid.java",
      "caller_line": 6,
      "call_site": { "file": "src/main/java/com/example/Mid.java", "line": 7 }
    },
    {
      "caller": "com.example.Mid#callsLeaf/0",
      "caller_file": "src/main/java/com/example/Mid.java",
      "caller_line": 6,
      "call_site": { "file": "src/main/java/com/example/Mid.java", "line": 11 }
    }
  ]
}
```

- `caller` 是被调方法的 caller 的短签名,`caller_file` / `caller_line` 是 caller
  方法**声明**位置
- `call_site.file` / `call_site.line` 是这一次具体调用的源位置 —— 这是
  `trace_callers` 给不出的信息
- 没人在项目里调 → `call_sites: []`,message 含 "No call sites found"
- target 是 jar 方法时,`find_call_sites` 会把所有 overload 的调用点 union 起来,
  message 里会列出合并的 overload 数和签名

### `find_symbols`

按简单名做**大小写不敏感的子串匹配**,在项目索引里找出所有 class / interface /
enum / record / annotation / method / constructor / field,回答"X 在哪儿定义"
和"哪些标识符包含 Y"。和 `trace_callers` / `find_call_sites` 不同,这个工具不
需要知道完整 FQN —— 给一个名字片段就能搜。

**输入** (`arguments`):

| 字段 | 必填 | 说明 |
|------|------|------|
| `query` | 是 | 大小写不敏感的子串,匹配 symbol 的简单名。例如 `"Repository"` 匹配 `UserRepository`、`OrderRepository`;`"get"` 匹配所有 getter;`"validate"` 同时匹配 `validate()` 方法和 `Validator` 类 |
| `kind` | 否 | 过滤,可选值 `class` / `interface` / `enum` / `record` / `annotation` / `method` / `constructor` / `field`。不传则所有 kind 一起返回(同名 class `equals` 和 `Object#equals` 方法会同时出现) |
| `project` | 否 | 同上 |
| `refresh` | 否 | 同上 |
| `limit` | 否 | 返回上限,默认 `100`,最大 `1000`。命中上限时 `message` 含 "capped" 提示,让你收紧 `query` 或加 `kind` 过滤 |

**输出**: 扁平 JSON 数组,按 `(fqn, signature)` 稳定排序。

```json
{
  "query": "leaf",
  "kind": null,
  "status": "ok",
  "message": "OK; 3 match(es).",
  "symbols": [
    {
      "name": "leaf",
      "kind": "method",
      "fqn": "com.example.Target#leaf/0",
      "container": "com.example.Target",
      "file": "src/main/java/com/example/Target.java",
      "line": 6,
      "signature": ""
    },
    {
      "name": "leafWithArg",
      "kind": "method",
      "fqn": "com.example.Target#leafWithArg/1",
      "container": "com.example.Target",
      "file": "src/main/java/com/example/Target.java",
      "line": 10,
      "signature": "int"
    }
  ]
}
```

- `container` 是直接包它的类型的 FQN;**顶层类型的 `container` 是 `null`**(不是
  package 名),这样能直接区分"X 是顶层类"和"X 是某个类的内部类"
- `signature` 只对 method / constructor 有意义 —— 是参数类型的完全限定名按声明
  顺序用 `,` 拼起来;零参方法是空串。class / field / enum 常量 / record 组件的
  `signature` 永远是 `null`
- `fqn` 对 method / constructor 是 `Container#name/arity` 形状,可以直接原样回传
  给 `trace_callers` / `find_call_sites`,不用再解析
- enum 常量按 `field` kind 暴露(例如 `com.example.Kind.ALPHA`);record 组件
  也按 `field` 暴露
- `line` 是声明位置的源行号 —— 对类型来说用的是类型名那一行(不是 `public class`
  修饰符的行),所以前面的 Javadoc 不会把行号往上推

## 限制

- 只支持 Maven 项目(读 `pom.xml` 找依赖)。**多模块项目**也支持 —— 顺着 `pom.xml` 树把所有
  模块的源根都收进来,只要每个子模块有自己的 `pom.xml`
- 本地 Maven 仓库优先用 `~/.m2/settings.xml` 里的 `<localRepository>`,否则才是 `~/.m2/repository`
- 只看项目 `src/main/java` 下的源码 —— `src/test/java` 排除掉(测试代码不参与调用链)
- 两个工具(`trace_callers` / `find_call_sites`)的 target 都可以是**库里的方法**(只声明在 jar 里、没在项目源码里),
  只要项目里有谁调用了它 —— 这种情况工具能找到项目的调用方/调用点并返回;
  如果项目里压根没人调用这个库方法,`trace_callers` 返回一棵空树(message 含
  "No callers found"),`find_call_sites` 返回空数组(message 含 "No call sites found")
- `project` 必须是**绝对路径** —— 工具不做相对路径猜测。
  不传 `project` 且 MCP host 没通过 `roots` 告知 workspace 根,工具会显式报错,
  不会去用服务进程的 CWD(那通常不是用户的当前目录)
- 重载必须用 `arity` 或 `paramTypes` 显式消歧,否则报错并列出所有候选
- 构造方法(`<init>`)也按方法处理
- 一次会话里同一个 project 路径的索引只构建一次,缓存复用
- JDT 3.45 的一个限制:**顶层 `record` 声明会被包在 `ImplicitTypeDeclaration` 里**,
  `find_symbols` 看不到这种 record 自身(kind 永远拿不到 `record`)以及它的
  components —— 嵌套 record 正常。这个限制在 `find_symbols` 的 description 里
  也写了,宿主/客户端能看到

## 编译运行

```bash
mvn package
# 产出 target/codescope.jar,自带所有依赖
```

直接用 stdio 跑:

```bash
java -jar target/codescope.jar
```

## 接入 MCP host

Claude Desktop 的 `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "codescope": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/codescope.jar"]
    }
  }
}
```

stdio 上跑的是 JSON-RPC 2.0,服务端每条响应一行 JSON,客户端不强制带换行(本服务
做了分帧,容忍无换行/多对象连发)。

## 测试

```bash
mvn test
```

112 个测试,9 组:

- `CallChainAnalyzerTest` —— 在 fixture 项目上跑 `JdtIndexer` + `CallChainAnalyzer`,
  验证:传递调用、重载消歧、未被调用、不存在的方法、循环、排除测试源码、
  enum/record/annotation 兼容。
- `EdgeCaseTest` —— 边界:深链不爆栈、钻石 vs 环、library target、
  50 000 节点截断、并发读写、坏源文件、匿名内部类归属。
- `MultiModuleTest` —— 多模块项目源码收集、`settings.xml` 解析、产物目录过滤、
  JRE classpath。
- `McpServerTest` —— 协议层:JSON-RPC 错误码、cancellation、string id、
  负 arity、错误响应完成 future 异常并格式化错误码、`method`/`params`
  类型校验、尾随字节保留、不完整输入保留、重复工具名拒绝。
- `McpServerStdioTest` —— `ProcessBuilder` 启 fat jar,发 `initialize` /
  `tools/list` / `tools/call`,验证整条 stdio 链,包括 MCP `roots` capability
  (host 声明后服务端主动 `roots/list` 拿默认 project;host 不声明则不去拉;
  `result: null` 这类畸形响应不影响后续调用)。
- `MavenClasspathResolverTest` / `MavenSettingsTest` —— pom 解析、
  classifier 排除、XXE 防御的边界。
- `FindCallSitesServiceTest` —— `find_call_sites` 的服务层:单/多调用点、
  library target 多 overload union、no-callers / unknown target / ambiguity
  诊断、缓存 + `refresh` 在新文件加入后能拾到新调用点。
- `FindSymbolsServiceTest` —— `find_symbols` 的服务层:大小写不敏感子串、
  kind 过滤、各类声明的索引路径(类/方法/构造器/普通字段/enum 常量/record
  组件)、no-match 诊断、limit 截断提示、blank query / unknown kind /
  缺 `pom.xml` 报错、缓存 + `refresh` 在新文件加入后能拾到新符号。
- `EdgeCaseTest` 里有一条 `callSitesRecordMultipleSitesForSameCaller` 钉住
  `ProjectIndex.callSitesOf` 的数据模型契约(多调用点保序、同行不去重)。
