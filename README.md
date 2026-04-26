# CodeScope

Java 语义上下文引擎，基于 Eclipse JDT Core 构建，为 LLM 提供代码语义分析能力。

## 技术选型

采用 Eclipse JDT Core 而非其他方案的原因：
- **语义精准**：JDT Binding 直接解析类型，比正则/Tree-sitter 更准确
- **零配置**：单个 JAR，无需编译
- **LLM 直接可用**：输出 Markdown，无需二次处理

详见 [COMPARISON.md](./COMPARISON.md)

## 特性

- **跨文件方法解析** - 解析同目录下的方法调用
- **JDK 方法解析** - 解析标准库方法
- **继承分析** - extends/implements 关系
- **Caller 分析** - 查找调用某方法的所有位置
- **影响范围分析** - 提交前分析修改的影响
- **抽象方法解析** - 查找抽象方法的实现
- **项目索引** - 大型项目方法查找
- **多模块支持** - 自动发现 module-*/src/main/java
- **AST 缓存** - 避免重复解析
- **Graphviz 输出** - DOT 格式调用图
- **循环检测** - 检测方法调用循环
- **调用热度** - 统计方法调用次数
- **Virtual Threads** - 并行加速

## 代码结构

```
com/codescope/
├── Main                    - CLI 入口
├── CommandHandler          - 命令分发
├── AnalysisEngine          - 分析引擎（主 facade）
├── DefaultCallGraphBuilder - 调用图分析
├── DefaultSourceIndexer    - 文件发现
├── DotGenerator            - DOT 输出
├── Index                   - 项目索引
├── JavaCodeEngine          - 公开 API
├── ProjectModel            - AST 管理
├── CacheManager            - 缓存
├── McpServer               - MCP 服务端
└── DefaultMavenParser      - Maven POM 解析
```

## 快速开始

```bash
# 构建
mvn package

# 运行
java -jar target/codescope-*.jar context Test.java main
```

## 命令

| 命令 | 说明 | 示例 |
|------|------|------|
| `context` | 构建语义上下文 | `context Test.java main` |
| `calls` | 方法调用关系 (callees) | `calls Test.java main` |
| `callers` | 反向调用查找 | `callers Test.java main` |
| `impact` | 影响范围分析 | `impact src/ methodName` |
| `dot` | Graphviz DOT 输出 | `dot Test.java` |
| `classpath` | Maven 依赖 | `classpath .` |
| `ast` | AST 结构 | `ast Test.java` |
| `index` | 项目索引 | `index src init` |

### 全局选项

| 选项 | 说明 |
|------|------|
| `--json` | JSON 格式输出 |
| `--no-jdk` | (dot 命令) 排除 JDK 方法调用 |
| `--cycles` | (dot 命令) 检测调用循环 |
| `--heatmap` | (dot 命令) 显示调用热度 |

## 使用示例

### 方法上下文（带调用解析）
```bash
java -jar target/codescope-*.jar context Test.java main
```

输出：
```
# Semantic Context for: main

## Class
Test

## Extends
- Helper

## Method
public static main()

### Calls
- greet -> Helper.greet(java.lang.String)
- println -> java.io.PrintStream.println(java.lang.String)

### Definition
public static void main(String[] args){
  String greeting=Helper.greet("world");
  System.out.println(greeting);
}
```

### 调用关系 (callees)
```bash
java -jar target/codescope-*.jar calls UserRepository.java save
```

### 反向调用查找 (callers)
```bash
java -jar target/codescope-*.jar callers UserRepository.java save
```

### 多模块项目
```bash
java -jar target/codescope-*.jar index project-dir hello
```

### Maven 依赖
```bash
java -jar target/codescope-*.jar classpath .
```

### 单文件调用图
```bash
java -jar target/codescope-*.jar dot Test.java
```

### Graphviz 调用图
```bash
# 单文件
java -jar target/codescope-*.jar dot Test.java > callgraph.dot

# 整个目录
java -jar target/codescope-*.jar dot src/ > callgraph.dot

# 多模块项目
java -jar target/codescope-*.jar dot project-dir/ > callgraph.dot

# 生成图片
dot -Tpng callgraph.dot -o callgraph.png
```

### dot 选项
```bash
# 排除 JDK 方法
java -jar target/codescope-*.jar dot src/ --no-jdk > callgraph.dot

# 检测循环调用
java -jar target/codescope-*.jar dot src/ --cycles > callgraph.dot

# 调用热度图
java -jar target/codescope-*.jar dot src/ --heatmap > callgraph.dot

# 组合使用
java -jar target/codescope-*.jar dot src/ --no-jdk --cycles --heatmap > callgraph.dot
```

### 项目索引
```bash
java -jar target/codescope-*.jar index src init
```

## MCP 服务端

MCP 服务端保持进程存活，AST 只需解析一次，性能比 CLI 调用高 10-100 倍。

### 启动 MCP Server

```bash
java -cp target/codescope-*.jar com.codescope.McpServer
```

### Claude Desktop 配置

在 `~/.config/claude/claude_desktop_config.json` 添加：

```json
{
  "mcpServers": {
    "codescope": {
      "command": "java",
      "args": ["-cp", "/path/to/codescope/target/codescope-*.jar", "com.codescope.McpServer"]
    }
  }
}
```

### Claude Code 配置

在项目根目录创建 `.claude/mcp.json`:

```json
{
  "mcpServers": {
    "codescope": {
      "command": "java",
      "args": ["-cp", "/path/to/codescope/target/codescope-*.jar", "com.codescope.McpServer"]
    }
  }
}
```

### MCP 工具定义

在 LLM Agent 中配置为 Tool：

```json
{
  "name": "codescope",
  "description": "Java 代码语义分析工具 - 查询方法调用关系、影响范围、生成调用图",
  "commands": {
    "context": {"description": "获取类/方法的语义上下文", "args": ["filePath", "methodName?"]},
    "calls": {"description": "查看方法调用的其他方法", "args": ["filePath", "methodName"]},
    "callers": {"description": "查找调用某方法的所有位置", "args": ["filePath", "methodName"]},
    "impact": {"description": "分析方法影响范围 (文本)", "args": ["filePath", "methodName"]},
    "impact_dot": {"description": "生成方法影响范围的 DOT 调用图", "args": ["filePath", "methodName"]},
    "dot": {"description": "生成完整调用图", "args": ["filePath"]}
  }
}
```

### MCP 工具列表

| 工具 | 说明 |
|------|------|
| `context` | 获取类/方法的语义上下文 |
| `calls` | 查看方法调用的其他方法 |
| `callers` | 查找调用某方法的所有位置 |
| `impact` | 分析方法影响范围 (文本) |
| `impact_dot` | 生成方法影响范围的 DOT 图 |
| `dot` | 生成完整调用图 |
| `ast` | 显示 AST 结构 |
| `index` | 构建项目索引 |
| `classpath` | 显示 Maven 依赖 |

## 技术架构

- **解析器**: Eclipse JDT Core AST
- **类型解析**: JDT Bindings
- **并发**: Java 21 Virtual Threads
- **打包**: Maven Shade (排除 OSGi 签名)

## 性能

- 首次运行: ~800ms
- 后续运行: 使用缓存

## 限制

- 需 Java 21