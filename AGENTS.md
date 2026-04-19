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
| `calls` | Show method call relationships + callers |
| `ast` | Show AST structure |
| `index` | Build project index for large projects |

## Usage Examples (CLI)
```bash
# Full file context (includes inheritance info)
java -jar target/codescope-*.jar context Test.java

# Method-specific context (with resolved calls)
java -jar target/codescope-*.jar context Test.java main

# By line number
java -jar target/codescope-*.jar context Test.java 10

# Show callees + callers
java -jar target/codescope-*.jar calls Test.java main

# Show AST structure
java -jar target/codescope-*.jar ast Test.java

# Index project
java -jar target/codescope-*.jar index src                 # Summary
java -jar target/codescope-*.jar index src init             # Find method across project
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

## Performance
- First run: ~800ms (parses all files)
- Subsequent runs: Uses cached AST

## Known Limitations
- No cross-module resolution (handles single directory)
- Unit tests using JDT directly may fail due to OSGi signing conflicts in Maven test classpath (CLI tests work fine)