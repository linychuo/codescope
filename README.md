# codescope

基于 Eclipse JDT 的 MCP 工具。给定一个 Java 类+方法名，返回**所有调用方**的嵌套调用链树。

## 快速开始

```bash
mvn package
# 产出 target/codescope.jar
```

### CLI 模式

```bash
java -jar target/codescope.jar trace-callers com.example.Foo bar --project /abs/path
java -jar target/codescope.jar find-call-sites com.example.Foo bar --project /abs/path
java -jar target/codescope.jar find-symbols validate --project /abs/path --kind method
```

### MCP 模式（给 AI 用）

```bash
java -jar target/codescope.jar
```

无参数启动 = MCP server over stdio。Claude Desktop 配置：

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

## 工具

| 工具 | 功能 |
|------|------|
| `trace_callers` | 目标方法的所有调用方嵌套树 |
| `find_call_sites` | 目标方法的所有调用点（精确行号） |
| `find_symbols` | 按名称子串搜索符号定义位置 |

三个工具共享索引缓存。所有工具都支持 `project`（项目路径）、`refresh`（重建索引）、`include_tests`（索引测试代码）参数。

详细参数见 [`docs/direct-stdio-usage.md`](docs/direct-stdio-usage.md) 或运行 `--help`。

## 配置

支持以下方式传递 Maven 额外参数（优先级从高到低）：

1. 程序调用 `DependencyResolverFactory.setMavenExtraArgs(...)`
2. JVM 系统属性: `-Dcodescope.mvn.args="-gs /path/to/settings.xml"`
3. 环境变量: `CODESCOPE_MVN_ARGS`

```bash
# JVM 系统属性（java -jar 时用）
java -Dcodescope.mvn.args="-gs /path/to/settings.xml" -jar codescope.jar

# 环境变量
export CODESCOPE_MVN_ARGS="-gs /path/to/settings.xml"
```

自动检测 Maven Wrapper（`mvnw` / `mvnw.cmd`）和 `MAVEN_HOME`。

## 限制

- 支持 Maven 项目（调用 `mvn dependency:build-classpath` 获取依赖），Gradle 后续加入
- `project` 必须是绝对路径
- 重载需用 `arity` 或 `paramTypes` 消歧
- `find_symbols` 看不到顶层 `record` 声明（JDT 限制）

## 测试

```bash
mvn test
# 181 个测试
```
