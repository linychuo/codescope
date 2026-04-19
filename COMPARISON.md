# CodeScope vs 业界工具对比

## 概述

CodeScope 是一个专注于"**让 LLM快速理解 Java 代码**"的工具，通过生成结构化语义上下文来实现 AI 代码理解。

## Java AST 解析方案对比

### 主流方案

| 方案 | Stars | 特点 | 适合场景 |
|------|------|------|---------|
| **JavaParser** | 6068 | 纯解析器 + Symbol Solver | 代码转换/生成 |
| **Spoon** | 3833 | 源码分析和转换 | 代码重构 |
| **PMD** | - | 静态分析/规则检查 | 代码规范 |
| **JDT Core** | - | Eclipse IDE 核心 | IDE 集成 |

### 方案优缺点

| 方案 | 优点 | 缺点 |
|------|------|------|
| **JDT Core** | 语义精准（Binding）、与 Eclipse 同一级别 | 依赖重、Eclipse 生态 |
| **JavaParser** | 轻量、社区活跃(6000+⭐) | 语义解析较弱 |
| **Spoon** | AST 转换强大 | 性能一般 |
| **PMD** | 内置 100+ 规则 | 不是为 LLM 设计 |

### 选择结论

**JDT Core 是 LLM 上下文的最佳选择**：
- 语义精准（Binding）
- 与 Eclipse IDE 同一级别
- 输出直接给 LLM 用

其他方案更适合：代码生成（JavaParser）、代码重构（Spoon）、代码规范（PMD）。

## 业界工具对比

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

### 1. java-all-call-graph (560⭐)
- **优点**：支持 SQL 查询、完整调用链、依赖库解析、DeepWiki 集成
- **缺点**：需要数据库、配置复杂、不是为 LLM 设计
- **场景**：企业级静态分析、代码安全扫描

### 2. code-review-graph
- **优点**：MCP 协议、增量更新、HTML 可视化、Cursor 集成
- **缺点**：Python 依赖、需二次处理给 LLM
- **场景**：AI Code Review、PR 审查

### 3. CallGraph (IntelliJ 插件)
- **优点**：IDE 内可视化、交互式
- **缺点**：需要 IDE Plugin
- **场景**：开发时查看调用图

### 4. CodeScope
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