# codescope

一个基于 Eclipse JDT 的 MCP (Model Context Protocol) 工具,给定一个 Java 类+方法名,
返回**所有调用这个方法的方法**的嵌套树,沿调用链一直向上,直到没有更多调用方为止。

底层用的是 Eclipse JDT 3.45 (`org.eclipse.jdt.core` 的 ASTParser 带 binding resolution),
也就是 [Eclipse JDT Language Server (jdtls)](https://projects.eclipse.org/projects/eclipse.jdt.ls)
跑的那套解析引擎。

## 工具

只有一个工具:

### `trace_callers`

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
  "message": "OK; 4 method(s) in chain."
}
```

如果某个 caller 把方法传给已经出现过的祖先方法(成环),会在那个父节点上挂一个
带 `"cycle": true` 的占位节点,树保持有限。

## 限制

- 只支持 Maven 项目(读 `pom.xml` 找依赖)。**多模块项目**也支持 —— 顺着 `pom.xml` 树把所有
  模块的源根都收进来,只要每个子模块有自己的 `pom.xml`
- 本地 Maven 仓库优先用 `~/.m2/settings.xml` 里的 `<localRepository>`,否则才是 `~/.m2/repository`
- 只看项目 `src/main/java` 下的源码 —— `src/test/java` 排除掉(测试代码不参与调用链)
- `trace_callers` 的 target 可以是**库里的方法**(只声明在 jar 里、没在项目源码里),
  只要项目里有谁调用了它 —— 这种情况工具能找到项目的调用方并返回;
  如果项目里压根没人调用这个库方法,返回结果是一棵空树(message 会说"No callers found")
- `project` 必须是**绝对路径** —— 工具不做相对路径猜测。
  不传 `project` 且 MCP host 没通过 `roots` 告知 workspace 根,工具会显式报错,
  不会去用服务进程的 CWD(那通常不是用户的当前目录)
- 重载必须用 `arity` 或 `paramTypes` 显式消歧,否则报错并列出所有候选
- 构造方法(`<init>`)也按方法处理
- 一次会话里同一个 project 路径的索引只构建一次,缓存复用

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

78 个测试,7 组:

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
