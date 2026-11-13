package com.codescope.cli;

import com.codescope.index.ProjectIndexCache;
import com.codescope.service.FindCallSitesService;
import com.codescope.service.FindSymbolsService;
import com.codescope.service.TraceCalleesService;
import com.codescope.service.TraceCallersService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI front-end for codescope. Bypasses the JSON-RPC layer and calls the
 * service classes directly, so the day-to-day flow is just
 * {@code java -jar codescope.jar <command> [options]}. The MCP server
 * path is unchanged: when {@link Main} is invoked with no args, the
 * server still starts on stdio.
 */
public final class Cli {

    private static final ObjectMapper RAW = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper();
    // One cache per CLI invocation so `trace-callers && find-symbols` in
    // the same session rebuilds the index once, not twice.
    private static final ProjectIndexCache SHARED_CACHE = new ProjectIndexCache();

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * @return process exit code: 0 success, 1 tool error, 2 usage error.
     *         Package-private so {@link CliTest} can drive it without
     *         going through {@code System.exit}.
     */
    static int run(String[] args) {
        if (args.length == 0) {
            // No args: usage error → stderr, exit 2. We don't print to
            // stdout because stdout is reserved for tool output (the
            // pretty-printed JSON); mixing usage and result on the same
            // stream makes piping fragile.
            System.err.println(usage());
            return 2;
        }
        if ("--help".equals(args[0]) || "-h".equals(args[0])) {
            // Explicit --help: stdout (the user asked for it).
            System.out.println(usage());
            return 0;
        }

        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        ParsedArgs p;
        try {
            p = parseArgs(rest, command);
        } catch (UsageException e) {
            System.err.println("codescope: " + e.getMessage());
            System.err.println();
            System.err.println(usage());
            return 2;
        }

        if (p.project == null) {
            System.err.println("codescope: --project is required");
            System.err.println();
            System.err.println(usage());
            return 2;
        }

        try {
            String json = dispatch(command, p);
            System.out.println(pretty(json));
            return 0;
        } catch (TraceCallersService.TraceCallersException
               | TraceCalleesService.TraceCalleesException
               | FindCallSitesService.FindCallSitesException
               | FindSymbolsService.FindSymbolsException e) {
            // Service layer already formats the user-facing message —
            // surface it as-is. No stack trace: callers asked the CLI
            // a question, they don't want a Java exception dump.
            System.err.println("codescope: " + e.getMessage());
            return 1;
        } catch (UsageException e) {
            System.err.println("codescope: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("codescope: internal error: " + e.getMessage());
            return 1;
        }
    }

    private static String dispatch(String command, ParsedArgs p) throws Exception {
        return switch (command) {
            case "trace-callers" -> {
                String cls = p.requirePos(0, "class");
                String mth = p.requirePos(1, "method");
                yield new TraceCallersService(SHARED_CACHE).traceCallersJson(
                        cls, mth, p.arity, p.paramTypes, p.project, p.refresh, p.includeTests,
                        p.maxDepth);
            }
            case "trace-callees" -> {
                String cls = p.requirePos(0, "class");
                String mth = p.requirePos(1, "method");
                yield new TraceCalleesService(SHARED_CACHE).traceCalleesJson(
                        cls, mth, p.arity, p.paramTypes, p.project, p.refresh, p.includeTests,
                        p.maxDepth);
            }
            case "find-call-sites" -> {
                String cls = p.requirePos(0, "class");
                String mth = p.requirePos(1, "method");
                // --limit is a page size here, not a hard cap: without it
                // the whole (possibly large) result set is returned.
                int limit = p.limitSet ? p.limit : Integer.MAX_VALUE;
                yield new FindCallSitesService(SHARED_CACHE).findCallSitesJson(
                        cls, mth, p.arity, p.paramTypes, p.project, p.refresh, p.includeTests,
                        p.offset, limit);
            }
            case "find-symbols" -> {
                String q = p.requirePos(0, "query");
                yield new FindSymbolsService(SHARED_CACHE).findSymbolsJson(
                        q, p.kind, p.project, p.refresh, p.includeTests, p.limit, p.offset,
                        p.fuzzy);
            }
            default -> throw new UsageException("Unknown command: " + command
                    + ". Use trace-callers, trace-callees, find-call-sites, or find-symbols.");
        };
    }

    private static String pretty(String json) {
        // Pretty-print for human eyes; if the JSON is malformed (shouldn't
        // happen — the services produced it), fall back to the raw form
        // so the user at least sees something.
        try {
            return PRETTY.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(RAW.readTree(json));
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private static ParsedArgs parseArgs(String[] args, String command) {
        ParsedArgs p = new ParsedArgs();
        int i = 0;
        while (i < args.length) {
            String a = args[i];
            switch (a) {
                case "--project" -> {
                    requireValue(args, i, "--project");
                    String raw = args[++i];
                    try {
                        p.project = Paths.get(raw).toAbsolutePath();
                    } catch (InvalidPathException e) {
                        // A path with a NUL byte (or another invalid sequence)
                        // would otherwise escape parseArgs as a runtime
                        // exception and produce a stack trace instead of a
                        // clean usage error.
                        throw new UsageException(
                                "--project is not a valid path: '" + raw + "'");
                    }
                    i++;
                }
                case "--arity" -> {
                    requireValue(args, i, "--arity");
                    int n = parsePositiveInt(args[++i], "--arity");
                    p.arity = n;
                    p.aritySet = true;
                    i++;
                }
                case "--param-types" -> {
                    requireValue(args, i, "--param-types");
                    String s = args[++i];
                    p.paramTypes = parseParamTypes(s);
                    p.paramTypesSet = true;
                    i++;
                }
                case "--refresh" -> { p.refresh = true; i++; }
                case "--include-tests" -> { p.includeTests = true; i++; }
                case "--fuzzy" -> { p.fuzzy = true; p.fuzzySet = true; i++; }
                case "--kind" -> {
                    requireValue(args, i, "--kind");
                    p.kind = args[++i];
                    p.kindSet = true;
                    i++;
                }
                case "--limit" -> {
                    requireValue(args, i, "--limit");
                    p.limit = parsePositiveInt(args[++i], "--limit");
                    p.limitSet = true;
                    i++;
                }
                case "--max-depth" -> {
                    requireValue(args, i, "--max-depth");
                    p.maxDepth = parsePositiveInt(args[++i], "--max-depth");
                    p.maxDepthSet = true;
                    i++;
                }
                case "--offset" -> {
                    requireValue(args, i, "--offset");
                    p.offset = parsePositiveInt(args[++i], "--offset");
                    p.offsetSet = true;
                    i++;
                }
                default -> {
                    if (a.startsWith("--")) {
                        throw new UsageException("Unknown option: " + a);
                    }
                    p.positional.add(a);
                    i++;
                }
            }
        }
        warnIfIrrelevant(p, command);
        return p;
    }

    /**
     * Options that only one command understands are warned about rather
     * than silently ignored, so a copy-pasted command line doesn't look
     * like it applied a setting it did not.
     */
    private static void warnIfIrrelevant(ParsedArgs p, String command) {
        switch (command) {
            case "find-symbols" -> {
                if (p.aritySet) warnIrrelevant("--arity", command);
                if (p.paramTypesSet) warnIrrelevant("--param-types", command);
                if (p.maxDepthSet) warnIrrelevant("--max-depth", command);
            }
            case "trace-callers", "trace-callees" -> {
                if (p.kindSet) warnIrrelevant("--kind", command);
                if (p.limitSet) warnIrrelevant("--limit", command);
                if (p.offsetSet) warnIrrelevant("--offset", command);
                if (p.fuzzySet) warnIrrelevant("--fuzzy", command);
            }
            case "find-call-sites" -> {
                if (p.kindSet) warnIrrelevant("--kind", command);
                if (p.maxDepthSet) warnIrrelevant("--max-depth", command);
                if (p.fuzzySet) warnIrrelevant("--fuzzy", command);
            }
            default -> { /* unknown command: dispatch reports it */ }
        }
    }

    private static void warnIrrelevant(String option, String command) {
        System.err.println("codescope: warning: " + option
                + " is not used by '" + command + "'; ignoring");
    }

    private static void requireValue(String[] args, int i, String opt) {
        if (i + 1 >= args.length) {
            throw new UsageException(opt + " requires a value");
        }
    }

    /**
     * Parse a positive integer option value. Negative or zero values would
     * silently match nothing downstream (e.g. {@code arity=-1} skips every
     * candidate) and non-numeric values used to surface as an internal
     * error from {@link Integer#parseInt}; both are now a clean usage error
     * so the user knows the flag was wrong, not that the project is broken.
     */
    private static int parsePositiveInt(String s, String opt) {
        int n;
        try {
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new UsageException(opt + " requires an integer, got '" + s + "'");
        }
        if (n < 0) {
            throw new UsageException(opt + " must be >= 0, got " + n);
        }
        return n;
    }

    /**
     * Split a comma-separated {@code --param-types} value, dropping empty
     * segments so {@code "a,,b"} becomes {@code ["a","b"]} and an entirely
     * empty value becomes an empty list (which the services treat as
     * "no filter"). Trims each entry so {@code "a, b "} is normalized.
     */
    private static List<String> parseParamTypes(String s) {
        if (s.isEmpty()) return List.of();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }

    static String usage() {
        return """
                codescope — call graph analysis for Java projects (CLI mode)

                Usage:
                  java -jar codescope.jar <command> [options]

                Commands:
                  trace-callers <class> <method>     Find all callers of a method
                  trace-callees <class> <method>     Find all callees of a method
                  find-call-sites <class> <method>   Find all call sites (flat list)
                  find-symbols <query>               Find symbols by name substring

                Options:
                  --project <path>                   Absolute path to project root (required)
                  --arity <n>                        Method arity (overload disambiguation)
                  --param-types <t1,t2,...>          Comma-separated FQN parameter types
                  --refresh                          Evict cached index and rebuild
                  --include-tests                    Include test sources in the index (src/test/java)
                  --kind <kind>                      Symbol kind: class|interface|enum|record|annotation|method|constructor|field
                  --limit <n>                        Max results for find-symbols (default 100);
                                                 page size for find-call-sites (default: all)
                  --offset <n>                       Skip the first n results (find-call-sites/find-symbols)
                  --max-depth <n>                    Max levels for trace-callers/trace-callees (default 500)
                  --fuzzy                            Typo-tolerant matching for find-symbols

                Exit codes:
                  0  success
                  1  tool error (e.g. project not found, ambiguous method)
                  2  usage error (bad or missing args)

                Examples:
                  java -jar codescope.jar trace-callers com.example.Foo bar --project C:/projects/foo
                  java -jar codescope.jar find-symbols validate --project C:/projects/foo --kind method
                  java -jar codescope.jar find-call-sites com.example.Foo bar --project C:/projects/foo --refresh
                """;
    }

    private static final class ParsedArgs {
        Path project;
        Integer arity;
        List<String> paramTypes;
        boolean refresh;
        boolean includeTests;
        boolean fuzzy;
        String kind;
        int limit = 100;
        int maxDepth = com.codescope.analysis.CallChainAnalyzer.DEFAULT_MAX_DEPTH;
        int offset;
        boolean aritySet, paramTypesSet, kindSet, limitSet, maxDepthSet, offsetSet, fuzzySet;
        final List<String> positional = new ArrayList<>();

        String requirePos(int idx, String name) {
            if (idx >= positional.size()) {
                throw new UsageException("Missing required positional argument: " + name
                        + " (position " + idx + ")");
            }
            return positional.get(idx);
        }
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) { super(message); }
    }
}
