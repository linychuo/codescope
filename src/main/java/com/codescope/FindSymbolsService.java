package com.codescope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business logic for {@code find_symbols}: validates a Maven project root,
 * loads/looks up the index, and produces a flat list of matching symbols
 * (types, methods, constructors, fields) whose simple names contain the
 * query as a case-insensitive substring. Mirrors
 * {@link TraceCallersService} and {@link FindCallSitesService} for
 * project-root validation, the LRU {@code indexCache}, and the
 * {@code refresh} arg.
 */
public final class FindSymbolsService {

    /** Default result cap when the caller doesn't pass `limit`. */
    static final int DEFAULT_LIMIT = 100;
    /** Hard ceiling on `limit` to keep responses bounded. */
    static final int MAX_LIMIT = 1000;

    /** Recognized kind values for the optional `kind` argument. */
    static final Set<String> VALID_KINDS = Set.of(
            "class", "interface", "enum", "record", "annotation",
            "method", "constructor", "field");

    private final ObjectMapper json = ProjectIndexCache.newObjectMapper();

    /**
     * Shared LRU + index builder. Centralized in {@link ProjectIndexCache}
     * so a single MCP session that uses {@code trace_callers} and
     * {@code find_symbols} against the same project indexes it once.
     */
    private final ProjectIndexCache indexCache = new ProjectIndexCache();

    public FindSymbolsService() {}

    /**
     * @param query      case-insensitive substring matched against symbol simple names
     * @param kind       optional; one of {@link #VALID_KINDS} or null
     * @param projectRoot absolute path to a Maven project root
     * @param refresh    if true, evict the cached index and rebuild
     * @param limit      max symbols to return; clamped to {@value #MAX_LIMIT}
     * @return JSON envelope: status, message, and the symbol array
     * @throws FindSymbolsException with a user-facing error message
     */
    public String findSymbolsJson(String query, String kind,
                                  Path projectRoot, boolean refresh, int limit)
            throws FindSymbolsException {
        if (query == null || query.isBlank()) {
            throw new FindSymbolsException("Missing or blank required argument: query");
        }
        if (kind != null && !VALID_KINDS.contains(kind)) {
            throw new FindSymbolsException("Unknown kind '" + kind
                    + "'. Valid: " + VALID_KINDS);
        }
        if (!Files.isDirectory(projectRoot)) {
            throw new FindSymbolsException("Project root is not a directory: " + projectRoot);
        }
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            throw new FindSymbolsException("No pom.xml at " + projectRoot
                    + " — only Maven projects are supported in this version.");
        }
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        ProjectIndex index;
        try {
            index = indexCache.loadOrRebuild(projectRoot, refresh);
        } catch (UncheckedIOException e) {
            throw new FindSymbolsException(e.getCause().getMessage());
        }

        List<ProjectIndex.Symbol> matches = index.searchSymbols(query, kind, effectiveLimit);
        boolean truncated = matches.size() >= effectiveLimit
                && index.searchSymbols(query, kind, Integer.MAX_VALUE).size() > effectiveLimit;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("kind", kind);
        out.put("status", "ok");
        out.put("message", buildMessage(query, kind, matches, truncated, effectiveLimit));
        out.put("symbols", buildSymbolsArray(matches));
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new FindSymbolsException("Failed to serialize result: " + e.getMessage());
        }
    }

    private static String buildMessage(String query, String kind,
                                       List<ProjectIndex.Symbol> matches, boolean truncated,
                                       int limit) {
        if (matches.isEmpty()) {
            String hint = kind == null
                    ? "Try a shorter query or pass a `kind` filter (one of: "
                            + VALID_KINDS + ")."
                    : "Try a shorter query or remove the `kind` filter.";
            return "No symbols matching '" + query + "'"
                    + (kind == null ? "" : " (kind=" + kind + ")")
                    + " in this project's sources. " + hint;
        }
        String noun = matches.size() == 1 ? "symbol" : "symbols";
        String base = "Found " + matches.size() + " " + noun + " matching '" + query + "'"
                + (kind == null ? "" : " (kind=" + kind + ")") + ".";
        return truncated
                ? base + " (capped at limit=" + limit + "; refine the query to see more)"
                : base;
    }

    private static List<Map<String, Object>> buildSymbolsArray(List<ProjectIndex.Symbol> matches) {
        List<Map<String, Object>> out = new ArrayList<>(matches.size());
        for (ProjectIndex.Symbol s : matches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", s.name());
            entry.put("kind", s.kind());
            entry.put("fqn", s.fqn());
            entry.put("container", s.container());
            entry.put("file", s.file());
            entry.put("line", s.line());
            entry.put("signature", s.signature());
            out.add(entry);
        }
        return out;
    }

    /** Thrown by {@link #findSymbolsJson} for user-facing error conditions. */
    public static final class FindSymbolsException extends Exception {
        public FindSymbolsException(String message) { super(message); }
    }
}
