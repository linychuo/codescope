# CodeScope

Java 语义上下文引擎，基于 Eclipse JDT Core 构建，为 LLM 提供代码语义分析能力。

## 特性

- **跨文件方法解析** - 解析同目录下的方法调用
- **JDK 方法解析** - 解析标准库方法
- **继承分析** - extends/implements 关系
- **Caller 分析** - 查找调用某方法的所有位置
- **抽象方法解析** - 查找抽象方法的实现
- **项目索引** - 大型项目方法查找
- **多模块支持** - 自动发现 module-*/src/main/java
- **AST 缓存** - 避免重复解析
- **Virtual Threads** - 并行加速

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
| `dot` | Graphviz DOT 输出 | `dot Test.java` |
| `ast` | AST 结构 | `ast Test.java` |
| `index` | 项目索引 | `index src init` |

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

# 生成图片
dot -Tpng callgraph.dot -o callgraph.png
```

### 项目索引
```bash
java -jar target/codescope-*.jar index src init
```

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