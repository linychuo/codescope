# Java Code Agent 精准上下文引擎方案（基于 Eclipse JDT Core + Java 21）

---

# 1. 项目目标

构建一个本地运行的 **Java Code Agent Context Engine**，用于：

> 👉 将 Java 项目中的"局部代码请求"转换为"结构化语义上下文"，供 LLM 使用，从而显著提升代码理解与生成质量。

---

# 2. 核心设计原则

## 2.1 不做"代码搜索工具"，做"语义上下文引擎"

区别：

| 类型 | 目标 |
|------|------|
| 代码搜索 | 找文件 |
| 本项目 | 理解代码关系 + 构建上下文 |

---

## 2.2 以语义为核心（不是文本）

基于：

- Eclipse JDT Core（AST + Binding）
- Java 21 runtime

能力包括：

- 方法调用关系
- 类型解析
- 继承体系
- 引用关系

---

## 2.3 上下文必须"结构化"，不是"拼代码"

输出必须是：

- 调用链
- 依赖关系
- 类型信息
- 关键代码片段

而不是整文件 dump

---

# 3. 技术选型

## 3.1 核心语言

👉 Java 21（必须）

## 3.2 核心分析引擎

Eclipse JDT Core

## 3.3 并发模型

Java Virtual Threads

---

# 4. 系统架构设计

Go CLI -> Java Engine -> JDT Core -> Project Model

---

# 5. 核心模块

## Project Model
AST缓存 + classpath

## Semantic Engine
方法解析 / 调用图 / 类型解析

## Index Layer
MethodIndex / CallGraph / ReverseGraph

## Context Builder
构建 LLM 输入上下文

---

# 6. 增量分析
文件变化 -> 单文件AST更新 -> 索引更新

---

# 7. 性能优化
- Virtual Threads
- AST Cache
- Index First Query

---

# 8. 分阶段实施
Phase 1: AST解析
Phase 2: CallGraph
Phase 3: Context Builder
Phase 4: CLI + LLM

---

# 9. 核心价值
构建 Java 语义上下文引擎，提升 Code Agent 能力