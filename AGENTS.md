# AGENTS.md

## Project Type
Java 21 project using Eclipse JDT Core for semantic code analysis.

## Tech Stack
- Java 21 (required)
- Eclipse JDT Core (AST + Binding analysis)
- Maven with shaded JAR (to avoid OSGi/signing issues)

## Build Commands
```bash
mvn compile                    # Compile
mvn package                  # Build shaded JAR
java -jar target/codescope-*.jar <command> <file.java> [method|line]
```

## Commands
| Command | Description |
|---------|-------------|
| `context` | Build semantic context for LLM (default) |
| `calls` | Show method call relationships (callees) |
| `callers` | Show methods that call a given method |
| `dot` | Generate Graphviz DOT call graph |
| `classpath` | Show Maven dependencies |
| `ast` | Show AST structure |
| `index` | Build project index for large projects |

## dot Options
| Option | Description |
|--------|-------------|
| `--no-jdk` | Exclude JDK method calls |
| `--cycles` | Detect call cycles |
| `--heatmap` | Show call frequency heatmap |

## Agent Integration

### Option 1: Shell Command (simplest)
LLM executes via shell:
```bash
cd /path/to/codescope && java -jar target/codescope-*.jar context /project/src/MyClass.java methodName
```

### Option 2: MCP Server (JSON over stdin/stdout)
Start MCP server and send JSON requests:
```bash
# Start server
java -cp target/codescope-*.jar com.codescope.McpServer

# Or pipe JSON request
echo '{"method":"context","filePath":"/path/to/File.java","method":"main"}' | \
  java -cp target/codescope-*.jar com.codescope.McpServer
```

JSON format:
```json
{"method":"context","filePath":"/path/to/Class.java","method":"methodName"}
{"method":"calls","filePath":"/path/to/Class.java","method":"methodName"}
{"method":"callers","filePath":"/path/to/Class.java","method":"methodName"}
{"method":"impact","filePath":"/path/to/project/","method":"methodName"}
{"method":"dot","filePath":"/path/to/project/"}
{"method":"classpath","filePath":"/path/to/project/"}
{"method":"index","filePath":"/path/to/project/","method":"methodName"}
```

### Option 3: Tool Definition for LLM
Define as tool:
```
name: java_code_context
description: Get semantic context for a Java method (class, imports, fields, calls, definition)
parameters:
  - name: filePath
    type: string
    required: true
  - name: methodName  
    type: string
    required: false
command: java -jar target/codescope-*.jar context {{filePath}} {{methodName}}
```

### Example Prompts
```
# Analyze a method
"使用 codescope 分析 /project/src/MyClass.java 中的 main 方法调用关系"

# Find all callers
"查找项目中调用 foo 方法的所有位置"

# Generate call graph
"生成 /project/src/ 的调用图，输出 Graphviz DOT 格式"
```

## Usage Examples (CLI)
```bash
# Full file context (includes inheritance info)
java -jar target/codescope-*.jar context Test.java

# Method-specific context (with resolved calls)
java -jar target/codescope-*.jar context Test.java main

# By line number
java -jar target/codescope-*.jar context Test.java 10

# Show callees
java -jar target/codescope-*.jar calls Test.java main

# Show callers
java -jar target/codescope-*.jar callers Test.java main

# Generate call graph
java -jar target/codescope-*.jar dot src/ > callgraph.dot

# Exclude JDK calls
java -jar target/codescope-*.jar dot src/ --no-jdk > project_calls.dot

# Show AST structure
java -jar target/codescope-*.jar ast Test.java

# Index project
java -jar target/codescope-*.jar index src                 # Summary
java -jar target/codescope-*.jar index src init             # Find method across project

# Maven dependencies
java -jar target/codescope-*.jar classpath .
```

## Library API
```java
import com.codescope.JavaCodeEngine;

JavaCodeEngine engine = new JavaCodeEngine(Path.of("src"));
SemanticContext ctx = engine.getSemanticContext(Path.of("src/MyClass.java"));
System.out.println(ctx.getClassName());         // "MyClass"
System.out.println(ctx.getPackageName());     // "com.example"
System.out.println(ctx.getSuperclass());       // "Parent" or null
ctx.getMethods().forEach(m -> System.out.println(m.getName()));
ctx.getFields().forEach(f -> System.out.println(f.getName()));

// Markdown output
String md = engine.getContextMarkdown(Path.of("src/MyClass.java"));

engine.close();
```

## Key Features
- **Cross-file method resolution** — Uses JDT bindings to resolve method calls across files
- **JDK method resolution** — Resolves standard library methods (e.g., `println -> java.io.PrintStream.println`)
- **Inheritance analysis** — Shows extends/implements relationships via bindings
- **Caller analysis** — Finds all methods that call a given method (reverse call graph)
- **Abstract method resolution** — Finds implementations of abstract methods
- **Index layer** — Full project method lookup (for large projects)
- **Method and line number lookup** — Can find methods by name or line number
- **AST Caching** — Avoids re-parsing unchanged files
- **Public API** — JavaCodeEngine library for programmatic access
- **Multi-module support** — Auto-discovers module-*/src/main/java
- **Graphviz output** — DOT format call graph with cycle detection and heatmap

## Performance
- First run: ~800ms (parses all files)
- Subsequent runs: Uses cached AST

## Known Limitations
- No Maven classpath resolution for third-party JAR bindings
- Unit tests using JDT directly may fail due to OSGi signing conflicts in Maven test classpath (CLI tests work fine)