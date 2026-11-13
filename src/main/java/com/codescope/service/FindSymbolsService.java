package com.codescope.service;

import com.codescope.index.ProjectIndex;
import com.codescope.index.ProjectIndexCache;
import com.codescope.index.SourceSnippet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    public static final int DEFAULT_LIMIT = 100;
    /** Hard ceiling on `limit` to keep responses bounded. */
    public static final int MAX_LIMIT = 1000;

    /**
     * Recognized kind values for the optional {@code kind} argument, in the
     * order they are advertised. A {@link LinkedHashSet} rather than
     * {@code Set.of}: the latter's iteration order is randomized per JVM
     * run, which made the "Valid: ..." diagnostics nondeterministic.
     */
    public static final Set<String> VALID_KINDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(
                    "class", "interface", "enum", "record", "annotation",
                    "method", "constructor", "field", "synthetic",
                    "mapper-statement")));

    private final ObjectMapper json = ProjectIndexCache.newObjectMapper();

    /**
     * Shared LRU + index builder. Centralized in {@link ProjectIndexCache}
     * so a single MCP session that uses {@code trace_callers} and
     * {@code find_symbols} against the same project indexes it once.
     */
    private final ProjectIndexCache indexCache;

    public FindSymbolsService() {
        this(new ProjectIndexCache());
    }

    public FindSymbolsService(ProjectIndexCache indexCache) {
        this.indexCache = indexCache;
    }

    /**
     * @param query      case-insensitive substring matched against symbol simple names
     * @param kind       optional; one of {@link #VALID_KINDS} or null
     * @param projectRoot absolute path to a Maven project root
     * @param refresh    if true, evict the cached index and rebuild
     * @param includeTests if true, index {@code src/test/java} in addition
     *                to {@code src/main/java}. Default {@code false}:
     *                test sources are excluded (test code does not appear
     *                in symbol search results by default). When {@code true},
     *                test classes/methods surface as symbols in
     *                find_symbols results.
     * @param limit      max symbols to return; clamped to {@value #MAX_LIMIT}
     * @return JSON envelope: status, message, and the symbol array
     * @throws FindSymbolsException with a user-facing error message
     */
    public String findSymbolsJson(String query, String kind,
                                  Path projectRoot, boolean refresh,
                                  boolean includeTests, int limit)
            throws FindSymbolsException {
        return findSymbolsJson(query, kind, projectRoot, refresh, includeTests, limit, false);
    }

    /**
     * As {@link #findSymbolsJson(String, String, Path, boolean, boolean, int)},
     * with an optional typo-tolerant fuzzy pass and a match annotation on
     * every hit (issue #9).
     *
     * @param fuzzy when true, names within a small Levenshtein distance of
     *              the query also match (see
     *              {@link ProjectIndex#searchSymbols(String, String, int, boolean)})
     * @throws FindSymbolsException with a user-facing error message
     */
    public String findSymbolsJson(String query, String kind,
                                  Path projectRoot, boolean refresh,
                                  boolean includeTests, int limit, boolean fuzzy)
            throws FindSymbolsException {
        return findSymbolsJson(query, kind, projectRoot, refresh, includeTests, limit, 0, fuzzy);
    }

    /**
     * As {@link #findSymbolsJson(String, String, Path, boolean, boolean, int, boolean)},
     * with an explicit window offset (issue #9 follow-up: pagination).
     *
     * @param offset number of matches to skip, applied after the stable
     *               sort; {@code total} in the envelope always reports the
     *               full match count so the caller can page deterministically
     */
    public String findSymbolsJson(String query, String kind,
                                  Path projectRoot, boolean refresh,
                                  boolean includeTests, int limit, int offset, boolean fuzzy)
            throws FindSymbolsException {
        if (query == null || query.isBlank()) {
            throw new FindSymbolsException("Missing or blank required argument: query");
        }
        if (kind != null && !VALID_KINDS.contains(kind)) {
            throw new FindSymbolsException("Unknown kind '" + kind
                    + "'. Valid: " + VALID_KINDS);
        }
        if (offset < 0) {
            throw new FindSymbolsException("offset must be >= 0; got " + offset + ".");
        }
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        ProjectIndex index = ProjectIndexCache.validateAndLoad(
                indexCache, projectRoot, refresh, includeTests, FindSymbolsException::new);

        ProjectIndex.SymbolMatchResult result =
                index.searchSymbols(query, kind, offset, effectiveLimit, fuzzy);
        List<ProjectIndex.SymbolMatch> matches = result.matches();
        int total = result.totalCount();
        // Truncated when the window ends before the last match.
        boolean truncated = total > (long) offset + effectiveLimit;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("kind", kind);
        out.put("status", "ok");
        out.put("message", buildMessage(query, kind, matches, truncated, effectiveLimit, offset,
                total));
        out.put("total", total);
        out.put("symbols", buildSymbolsArray(query, matches, new SourceSnippet(projectRoot)));
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new FindSymbolsException("Failed to serialize result: " + e.getMessage());
        }
    }

    private static String buildMessage(String query, String kind,
                                       List<ProjectIndex.SymbolMatch> matches, boolean truncated,
                                       int limit, int offset, int total) {
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
        if (truncated) {
            base = base + " (capped at limit=" + limit + "; refine the query to see more)";
        }
        if (offset > 0 || truncated) {
            base = base + " (showing " + (Math.min(offset + 1, total)) + "-"
                    + (offset + matches.size()) + " of " + total
                    + "; pass offset/limit to paginate)";
        }
        return base;
    }

    private static List<Map<String, Object>> buildSymbolsArray(
            String query, List<ProjectIndex.SymbolMatch> matches, SourceSnippet snippets) {
        List<Map<String, Object>> out = new ArrayList<>(matches.size());
        for (ProjectIndex.SymbolMatch m : matches) {
            ProjectIndex.Symbol s = m.symbol();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", s.name());
            entry.put("kind", s.kind());
            entry.put("fqn", s.fqn());
            entry.put("container", s.container());
            entry.put("file", s.file());
            entry.put("line", s.line());
            entry.put("signature", s.signature());
            entry.put("match", buildMatchJson(query, m));
            // Mapper statements carry their SQL body as a searchable
            // payload; surface it so a content hit is readable in place.
            if (s.detail() != null) entry.put("detail", s.detail());
            // Issue #9: the declaration line, so a hit can be judged
            // without opening the file.
            String snippet = snippets.line(s.file(), s.line());
            if (snippet != null) entry.put("snippet", snippet);
            out.add(entry);
        }
        return out;
    }

    /**
     * Builds the issue #9 match annotation: how the hit matched, which
     * field matched ({@code name} or the symbol's {@code detail} payload),
     * the text that matched, whether the case differs from the query, and
     * the edit distance for fuzzy hits. This is the "标注差异" half of the
     * feature — without it, a case-insensitive or fuzzy hit looked
     * identical to an exact one.
     */
    private static Map<String, Object> buildMatchJson(String query, ProjectIndex.SymbolMatch m) {
        Map<String, Object> match = new LinkedHashMap<>();
        boolean detailMatch = m.kind() == ProjectIndex.MatchKind.DETAIL;
        match.put("type", m.kind().name().toLowerCase(Locale.ROOT));
        match.put("field", detailMatch ? "detail" : "name");
        match.put("matched", m.matchedText());
        boolean caseDiffers = m.kind() == ProjectIndex.MatchKind.CASE_INSENSITIVE
                || (m.kind() == ProjectIndex.MatchKind.SUBSTRING
                        && !m.matchedText().equals(query));
        match.put("case_differs", caseDiffers);
        match.put("distance", m.distance());
        return match;
    }

    /** Thrown by {@link #findSymbolsJson} for user-facing error conditions. */
    public static final class FindSymbolsException extends Exception {
        public FindSymbolsException(String message) { super(message); }
    }
}
