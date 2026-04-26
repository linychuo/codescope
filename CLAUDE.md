# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
mvn compile              # Compile
mvn package              # Build shaded JAR (excludes OSGi signatures)
mvn test                 # Run unit tests
```

Run: `java -jar target/codescope-*.jar <command> <file.java> [method|line]`

## Architecture

CodeScope is a Java semantic context engine built on Eclipse JDT Core. It provides code semantic analysis for LLMs and code agents.

```
Main (CLI entry)
└── CommandHandler (routes commands)
        ├── AnalysisEngine (main facade)
        │       ├── DefaultSourceIndexer (file discovery)
        │       ├── DefaultCallGraphBuilder (call graph analysis)
        │       └── ProjectModel ──► CacheManager (AST cache)
        ├── DotGenerator (Graphviz DOT output)
        ├── Index (full project method lookup)
        ├── ClasspathResolver ──► DefaultMavenParser (Maven JAR resolution)
        └── McpServer (Model Context Protocol server)
```

Key flow: `CommandHandler` routes to specialized analyzers. `AnalysisEngine` is the main facade that orchestrates analysis components. `ProjectModel` manages AST cache per module using Java 21 Virtual Threads.

### Interfaces (abstractions)

```
CallGraphBuilder       - builds call graphs from source
SourceIndexer          - discovers and indexes source files
MavenParser            - parses Maven POMs
SemanticContextBuilder - builds semantic context for a method
CacheStrategy          - handles AST caching
```

## Commands

| Command | Description |
|---------|-------------|
| `context` | Build semantic context for LLM (class, methods, calls, inheritance) |
| `calls` | Show callees (methods this method calls) |
| `callers` | Show callers (methods that call this method, cross-file) |
| `impact` | Analyze method impact (who calls this method, text format) |
| `impact-dot` | Generate impact call graph in DOT format |
| `dot` | Generate Graphviz DOT call graph |
| `classpath` | Show Maven dependencies |
| `ast` | Show AST structure |
| `index` | Build project index for large multi-module projects |

Global options: `--json` (JSON output), `--no-jdk` (exclude JDK calls), `--cycles` (detect call cycles), `--heatmap` (show call frequency).

## Source Structure

```
src/main/java/com/codescope/
├── Main.java                    - CLI entry point
├── CommandHandler.java          - Command routing
├── AnalysisEngine.java          - Main facade (replaces ContextBuilder)
├── DotGenerator.java            - DOT format output
├── Index.java                   - Full project index
├── JavaCodeEngine.java          - Public API (library interface)
├── ProjectModel.java            - AST management per module
├── CacheManager.java            - AST serialization cache (.jdt-cache)
├── ClasspathResolver.java       - Maven dependency JAR resolution
├── McpServer.java               - MCP server (JSON-RPC over stdin/stdout)
├── DefaultCallGraphBuilder.java - Call graph analysis implementation
├── DefaultSourceIndexer.java    - File discovery implementation
├── DefaultMavenParser.java      - XML-based Maven POM parser
├── CallGraphBuilder.java        - Interface for call graph building (includes CallSite)
├── SourceIndexer.java           - Interface for source file discovery
├── MavenParser.java             - Interface for Maven POM parsing
├── SemanticContextBuilder.java  - Interface for semantic context building
├── CacheStrategy.java           - Interface for AST caching
├── Result.java                  - Either-style error handling
└── Logger.java                  - Structured logging
```

## Library API

```java
JavaCodeEngine engine = new JavaCodeEngine(Path.of("src"));

JavaCodeEngine.SemanticContext ctx = engine.getSemanticContext(Path.of("src/MyClass.java"));
ctx.getClassName();
ctx.getSuperclass();
ctx.getMethods();
ctx.getFields();

var calls = engine.getMethodCalls(Path.of("src/MyClass.java"), "main");

String md = engine.getEngine().buildContext(Path.of("src/MyClass.java"), null);
engine.close();
```

## MCP Server

Start: `java -cp target/codescope-*.jar com.codescope.McpServer`

The MCP server provides tools: `context`, `calls`, `callers`, `impact`, `impact_dot`, `dot`, `ast`, `index`, `classpath`.

## Key Technical Details

- **Java 21 required** (Virtual Threads for concurrency)
- **Eclipse JDT Core 3.45.0** (JLS21) for AST and binding resolution
- **AST caching**: persisted to `.jdt-cache`, validated by file modification timestamps
- **Multi-module**: auto-discovers `module-*/src/main/java` directories
- **First run**: ~800ms; subsequent runs use cached AST
