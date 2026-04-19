package com.codescope;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.Flags;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(0);
        }

        try {
            run(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (Boolean.parseBoolean(System.getProperty("debug", "false"))) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        String command = args[0];
        boolean json = Arrays.asList(args).contains("--json");

        if (command.equals("-h") || command.equals("--help")) {
            printUsage();
            return;
        }

        if (command.equals("-v") || command.equals("--version")) {
            System.out.println("JDT Context Engine v0.1.0");
            return;
        }

        if (args.length < 2) {
            System.err.println("Error: Missing arguments");
            printUsage();
            System.exit(1);
        }

        try {
            if (command.equals("index")) {
                runIndex(args, json);
            } else {
                runContext(args, json);
            }
        } finally {
            if (json) {
                // JSON output already handled
            }
        }
    }

    private static void runIndex(String[] args, boolean json) throws IOException {
        List<Path> dirs = new ArrayList<>();
        String query = null;

        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("-")) continue;
            if (!args[i].contains("/") && i == args.length - 1 && !args[i].contains(".")) {
                query = args[i];
                continue;
            }
            Path p = Paths.get(args[i]).toAbsolutePath();
            if (Files.exists(p) && Files.isDirectory(p)) {
                dirs.add(p);
            }
        }

        if (dirs.isEmpty()) {
            throw new IOException("No valid directories found");
        }

        long start = System.currentTimeMillis();
        Index index = new Index(dirs);
        index.build();

        String output;
        if (query != null) {
            output = index.findMethod(query);
        } else {
            output = index.summary();
        }

        if (json) {
            System.out.println(toJson(output));
        } else {
            System.out.println(output);
        }
        System.out.println("Done in " + (System.currentTimeMillis() - start) + "ms");
    }

    private static void runContext(String[] args, boolean json) throws IOException {
        Path sourceFile = Path.of(args[1]).toAbsolutePath();
        if (!Files.exists(sourceFile)) {
            throw new IOException("File not found: " + sourceFile);
        }

        String query = args.length > 2 ? args[2] : null;
        Path dir = sourceFile.getParent();

        long start = System.currentTimeMillis();
        ContextBuilder cb = new ContextBuilder(dir);
        String output;

        if (args[0].equals("context")) {
            output = cb.build(sourceFile, query);
        } else if (args[0].equals("calls")) {
            output = buildCalls(cb, sourceFile);
        } else if (args[0].equals("ast")) {
            output = buildAst(cb, sourceFile);
        } else {
            output = "Unknown command: " + args[0];
        }

        if (json) {
            System.out.println(toJson(output));
        } else {
            System.out.println(output);
        }
        System.out.println("Done in " + (System.currentTimeMillis() - start) + "ms");
    }

    private static String toJson(String markdown) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        
        String[] lines = markdown.split("\n");
        boolean inCode = false;
        for (String line : lines) {
            if (line.startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                sb.append("  \"").append(escape(line)).append("\",\n");
            }
        }
        
        sb.append("}\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String buildCalls(ContextBuilder cb, Path sourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Call Graph for: ").append(sourceFile.getFileName()).append("\n\n");

        CompilationUnit cu = cb.model.getAst(sourceFile);
        if (cu == null) return "File not found: " + sourceFile;

        ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(sourceFile, cb.model);

        for (Object obj : cu.types()) {
            if (!(obj instanceof TypeDeclaration type)) continue;
            for (Object member : type.bodyDeclarations()) {
                if (!(member instanceof MethodDeclaration method)) continue;
                String methodName = method.getName().getIdentifier();
                sb.append("## ").append(methodName).append("\n");
                Set<ContextBuilder.CallGraph.CallSite> calls = cg.getCallees(sourceFile, methodName);
                if (calls.isEmpty()) {
                    sb.append("(no calls)\n");
                } else {
                    for (var call : calls) {
                        sb.append("- ").append(call).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String buildAst(ContextBuilder cb, Path sourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AST for: ").append(sourceFile.getFileName()).append("\n\n");

        CompilationUnit cu = cb.model.getAst(sourceFile);
        if (cu == null) return "File not found: " + sourceFile;

        for (Object obj : cu.types()) {
            if (obj instanceof TypeDeclaration type) {
                sb.append("## Class: ").append(type.getName().getIdentifier()).append("\n");
                for (Object member : type.bodyDeclarations()) {
                    if (member instanceof MethodDeclaration m) {
                        sb.append("### Method: ").append(m.getName().getIdentifier()).append("\n");
                        sb.append("```java\n").append(m.toString()).append("\n```\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void printUsage() {
        System.out.println("""
JDT Context Engine - Java Semantic Context for LLM

Usage: Main <command> <source> [query]

Commands:
  context   Build semantic context for LLM
  calls     Show method call relationships
  ast       Show AST structure
  index     Build project index (for large projects)

Options:
  -h, --help     Show this help
  -v, --version  Show version
  --json         Output as JSON

Examples:
  Main context Test.java           # Full file context
  Main context Test.java main    # Method-specific context
  Main calls Test.java main      # Show callees of main
  Main ast Test.java             # Show AST
  Main index .                   # Index current directory
  Main index . init             # Find method 'init' in project
  Main index src test --json     # JSON output
""");
    }
}