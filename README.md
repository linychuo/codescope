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
| `method` | 是 | 方法名,例如 `bar`。同名重载不会消歧,匹配第一个 |
| `project` | 否 | Maven 项目根目录的绝对路径,默认是 MCP host 的当前工作目录 |

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

- 只支持 Maven 项目(读 `pom.xml` 找依赖)
- 只看**项目里**的 `.java` 源码 —— `~/.m2/repository/*.jar` 里的方法调用看不到
  (虽然 binding resolution 会用到 jar 让跨文件类型解析能成功)
- 重载按 `methodName` 匹配,匹配第一个 arity 命中
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

包含两组:

- `CallChainAnalyzerTest` —— 在 fixture 项目上跑 `JdtIndexer` + `CallChainAnalyzer`,
  验证:传递调用、重载、未被调用、不存在的方法、循环。
- `McpServerStdioTest` —— `ProcessBuilder` 启 fat jar,发 `initialize` / `tools/list` /
  `tools/call` 三连,验证整条 stdio 链。
