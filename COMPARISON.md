# CodeScope vs 业界工具对比

## 概述

CodeScope 是一个专注于"**让 LLM快速理解 Java 代码**"的工具，通过生成结构化语义上下文来实现 AI 代码理解。

## 对比表

| 特性 | CodeScope | java-all-call-graph (560⭐) | code-review-graph | CallGraph (IntelliJ插件) |
|-------|---------|------------------------|---------------|---------------------|
| **定位** | LLM 上下文生成 | 深度代码分析 | AI Code Review | IDE 可视化 |
| **分析方式** | JDT AST（源码） | 字节码 | Tree-sitter | JDT |
| **输出格式** | Markdown/DOT | SQLite | SQLite/MCP | HTML |
| **部署** | 单 JAR | 需编译 | pip install | Plugin |
| **增量更新** | AST 缓存 | ✅ | ✅ | - |
| **第三方库** | ❌ | ✅ | ✅ | - |
| **MCP 协议** | ❌ | ❌ | ✅ | ❌ |

## 详细对比

### 1. java-all-call-graph
- **优点**：支持 SQL 查询、完整调用链、依赖库解析
- **缺点**：需要数据库、配置复杂、不是为 LLM 设计
- **场景**：企业级静态分析

### 2. code-review-graph
- **优点**：MCP 协议、增量更新、HTML 可视化
- **缺点**：Python 依赖、需二次处理给 LLM
- **场景**：Cursor/Cline AI 集成

### 3. CodeScope
- **优点**：单命令输出 Markdown、LLM 直接使用、简单部署
- **缺点**：不支持第三方库解析
- **场景**：LLM 代码理解、Code Agent 集成

## 核心差异

```
用户问: "这个方法被哪里调用？"

java-all-call-graph:
  → 执行 SQL 查询
  → 返回 10 条记录
  → 用户自己分析

code-review-graph:
  → 返回 JSON
  → 需二次处理

CodeScope:
  → java -jar codescope.jar callers Class.java method
  → 直接返回 Markdown
  → LLM 可以立即理解并回答
```

## CodeScope 优势

| 优势 | 说明 |
|------|------|
| **直接可用** | CLI 输出 Markdown，LLM 直接理解 |
| **简单部署** | 单个 JAR，无需额外依赖 |
| **快速** | 首次 ~800ms，后续使用缓存 |
| **Code Agent 友好** | 一条命令即可调用 |

## 选择建议

| 场景 | 推荐工具 |
|------|--------|
| LLM 代码理解 | CodeScope |
| 企业静态分析 | java-all-call-graph |
| AI Code Review | code-review-graph |
| IDE 可视化 | CallGraph 插件 |