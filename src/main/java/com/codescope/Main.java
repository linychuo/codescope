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

private static boolean noJdk = false;
    private static boolean cycles = false;
    private static boolean heatmap = false;

    private static void run(String[] args) throws IOException {
        String command = args[0];
        boolean json = Arrays.asList(args).contains("--json");
        noJdk = Arrays.asList(args).contains("--no-jdk");
        cycles = Arrays.asList(args).contains("--cycles");
        heatmap = Arrays.asList(args).contains("--heatmap");
        
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
        } else if (args[0].equals("callers")) {
            output = buildCallers(cb, sourceFile, query);
        } else if (args[0].equals("dot")) {
            output = cb.buildDot(noJdk, cycles, heatmap);
        } else if (args[0].equals("classpath")) {
            output = buildClasspath(sourceFile);
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

        CompilationUnit cu = cb.getModels().get(0).getAst(sourceFile);
        if (cu == null) return "File not found: " + sourceFile;

        ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(sourceFile, cb.getModels().get(0));

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

    private static String buildCallers(ContextBuilder cb, Path sourceFile, String methodName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Callers of: ").append(methodName != null ? methodName : sourceFile.getFileName()).append("\n\n");

        if (methodName == null) {
            return buildCalls(cb, sourceFile);
        }

        sb.append("## Callers\n");
        ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(sourceFile, cb.getModels().get(0));
        Set<ContextBuilder.CallGraph.CallSite> callers = cg.getCallersByName(methodName);
        if (callers.isEmpty()) {
            sb.append("(no callers found)\n");
        } else {
            for (var caller : callers) {
                sb.append("- ").append(caller.resolved).append(" at line ").append(caller.line).append("\n");
            }
        }
        return sb.toString();
    }

    private static String buildClasspath(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Classpath for: ").append(dir).append("\n\n");

        Path pom = dir.resolve("pom.xml");
        if (Files.exists(pom)) {
            sb.append("Detected pom.xml\n\n");
            List<String> deps = parseMavenDeps(pom);
            for (String dep : deps) {
                sb.append("- ").append(dep).append("\n");
            }
        } else {
            sb.append("No pom.xml found\n");
        }

        return sb.toString();
    }

    private static List<String> parseMavenDeps(Path pom) throws IOException {
        List<String> deps = new ArrayList<>();
        String content = Files.readString(pom);
        
        String dependencyPattern = "<dependency>.*?<groupId>(.*?)</groupId>.*?<artifactId>(.*?)</artifactId>.*?(?:<version>(.*?)</version>)?.*?</dependency>";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(dependencyPattern, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String groupId = matcher.group(1).trim();
            String artifactId = matcher.group(2).trim();
            String version = matcher.group(3) != null ? matcher.group(3).trim() : "unknown";
            deps.add(groupId + ":" + artifactId + ":" + version);
        }
        
        return deps;
    }

    private static String buildAst(ContextBuilder cb, Path sourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AST for: ").append(sourceFile.getFileName()).append("\n\n");

        CompilationUnit cu = cb.getModels().get(0).getAst(sourceFile);
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
  callers   Show methods that call a given method
  dot       Generate Graphviz DOT format
  classpath Show classpath (Maven JARs)
  ast       Show AST structure
  index     Build project index (for large projects)

Options:
  -h, --help     Show this help
  -v, --version  Show version
  --json         Output as JSON

dot Options:
  --no-jdk      Exclude JDK method calls
  --cycles      Detect call cycles
  --heatmap    Show call frequency heatmap

Examples:
  Main context Test.java           # Full file context
  Main context Test.java main    # Method-specific context
  Main calls Test.java main      # Show callees of main
  Main callers Test.java main    # Show callers of main
  Main dot Test.java            # Graphviz DOT output
  Main dot src/ --no-jdk       # Exclude JDK calls
  Main dot src/ --cycles       # Detect cycles
  Main dot src/ --heatmap     # Show heatmap
  Main classpath .            # Show Maven dependencies
  Main ast Test.java             # Show AST
  Main index .                   # Index current directory
  Main index . init             # Find method 'init' in project
  Main index src test --json     # JSON output
""");
    }
}