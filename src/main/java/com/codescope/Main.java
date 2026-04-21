package com.codescope;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.Flags;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * CLI entry point for CodeScope.
 * Parses arguments and delegates to CommandHandler.
 */
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
            System.out.println("CodeScope v" + getVersion());
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
        String command = args[0];
        String input = args[1];
        String query = args.length > 2 ? args[2] : null;

        Path sourceFile = Path.of(input).toAbsolutePath();
        Path dir = sourceFile.getParent();

        if (command.equals("impact-dot") && !Files.exists(sourceFile)) {
            if (!input.contains("/")) {
                List<Path> dirs = new ArrayList<>();
                if (dir != null && Files.exists(dir)) {
                    dirs.add(dir);
                }
                if (dirs.isEmpty()) {
                    dirs.add(Path.of(".").toAbsolutePath());
                }

                Index index = new Index(dirs);
                index.build();
                List<Path> found = index.findClass(input);

                if (found.isEmpty()) {
                    throw new IOException("Class not found: " + input);
                }
                sourceFile = found.get(0);
            }
            dir = sourceFile.getParent();
            System.err.println("Found " + input + " at " + sourceFile);
        } else if (!Files.exists(sourceFile) && !input.contains("/")) {
            List<Path> dirs = new ArrayList<>();
            if (dir != null && Files.exists(dir)) {
                dirs.add(dir);
            }
            if (dirs.isEmpty()) {
                dirs.add(Path.of(".").toAbsolutePath());
            }

            Index index = new Index(dirs);
            index.build();
            List<Path> found = index.findClass(input);

            if (found.isEmpty()) {
                throw new IOException("Class not found: " + input);
            }
            sourceFile = found.get(0);
            dir = sourceFile.getParent();
            System.err.println("Found " + input + " at " + sourceFile);
        } else if (!Files.exists(sourceFile)) {
            throw new IOException("File not found: " + sourceFile);
        }

        long start = System.currentTimeMillis();
        String output;

        try {
            output = CommandHandler.handle(command, sourceFile, query, noJdk, cycles, heatmap);
        } catch (Exception e) {
            output = "Error: " + e.getMessage();
            if (Boolean.parseBoolean(System.getProperty("debug", "false"))) {
                e.printStackTrace();
            }
        }

        if (json) {
            output = toJson(output);
        }
        System.out.println(output);
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

    private static String getVersion() {
        return "0.1.0";
    }

    private static void printUsage() {
        System.out.println("""
CodeScope - Java Semantic Context Engine

Usage: Main <command> <source> [query]

Commands:
  context   Build semantic context for LLM
  calls     Show method call relationships
  callers   Show methods that call a given method
  impact    Analyze method impact (who calls this method)
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