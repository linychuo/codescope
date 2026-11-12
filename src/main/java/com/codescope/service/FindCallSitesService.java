package com.codescope.service;

import com.codescope.analysis.MethodResolver;
import com.codescope.index.ProjectIndex;
import com.codescope.index.ProjectIndexCache;
import com.codescope.index.SourceSnippet;
import com.codescope.model.MethodKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for {@code find_call_sites}: validates a Maven project
 * root, loads/looks up the reverse call index, and produces a flat list of
 * (caller, call_site) entries for the given target. Mirrors
 * {@link TraceCallersService} for project-root validation, the LRU
 * {@code indexCache}, the {@code refresh} arg, and library-target
 * resolution.
 */
public final class FindCallSitesService {

    /**
     * Sentinel for {@code limit} meaning "no cap": return every call site.
     * The service treats it as {@link Integer#MAX_VALUE} rather than a
     * separate boolean so the pagination arithmetic stays branch-free.
     */
    public static final int NO_LIMIT = Integer.MAX_VALUE;

    private final ObjectMapper json = ProjectIndexCache.newObjectMapper();

    /**
     * Shared LRU + index builder. Centralized in {@link ProjectIndexCache}
     * so a single MCP session that uses {@code trace_callers} and
     * {@code find_call_sites} against the same project indexes it once.
     */
    private final ProjectIndexCache indexCache;

    public FindCallSitesService() {
        this(new ProjectIndexCache());
    }

    public FindCallSitesService(ProjectIndexCache indexCache) {
        this.indexCache = indexCache;
    }

    /**
     * @param refresh if true, evict the cached index for {@code projectRoot}
     *                and rebuild it. Same contract as
     *                {@link TraceCallersService#traceCallersJson}.
     * @param includeTests if true, index {@code src/test/java} in addition
     *                to {@code src/main/java}. Default {@code false}:
     *                test sources are excluded (test code does not
     *                participate in the call-site index by default). When
     *                {@code true}, test methods appear as callers in
     *                find_call_sites results.
     * @return JSON envelope containing the flat call-site list, status,
     *         and message
     * @throws FindCallSitesException with a user-facing error message
     */
    public String findCallSitesJson(String className, String methodName,
                                    Integer arity, List<String> paramTypes,
                                    Path projectRoot, boolean refresh,
                                    boolean includeTests) throws FindCallSitesException {
        return findCallSitesJson(className, methodName, arity, paramTypes, projectRoot,
                refresh, includeTests, 0, NO_LIMIT);
    }

    /**
     * As {@link #findCallSitesJson(String, String, Integer, List, Path, boolean, boolean)},
     * with windowing over the flat call-site list.
     *
     * @param offset number of call sites to skip (0 = start from the first),
     *               applied after the stable sort
     * @param limit  maximum number of call sites to return; pass
     *               {@link #NO_LIMIT} for "every site" (the default of the
     *               7-arg overload)
     * @throws FindCallSitesException with a user-facing error message
     */
    public String findCallSitesJson(String className, String methodName,
                                    Integer arity, List<String> paramTypes,
                                    Path projectRoot, boolean refresh,
                                    boolean includeTests, int offset, int limit)
            throws FindCallSitesException {
        if (offset < 0) {
            throw new FindCallSitesException("offset must be >= 0; got " + offset + ".");
        }
        if (limit < 1) {
            throw new FindCallSitesException("limit must be >= 1; got " + limit + ".");
        }
        ProjectIndex index = ProjectIndexCache.validateAndLoad(
                indexCache, projectRoot, refresh, includeTests, FindCallSitesException::new);

        // Same library-target resolution as trace_callers. The try/catch
        // and overload-hint formatting are now centralized in MethodResolver.
        MethodResolver.Result res;
        try {
            res = MethodResolver.resolve(index, className, methodName, arity, paramTypes);
        } catch (ProjectIndex.AmbiguousMethodException e) {
            throw new FindCallSitesException(MethodResolver.overloadsHint(
                    index, className, methodName, e));
        }

        MethodKey target;
        List<MethodKey> seeds;
        MethodKey display;
        if (res instanceof MethodResolver.Result.ProjectTarget pt) {
            target = pt.target();
            seeds = List.of(target);
            display = target;
        } else {
            MethodResolver.Result.LibrarySeeds ls = (MethodResolver.Result.LibrarySeeds) res;
            target = null;
            seeds = ls.seeds();
            display = ls.display();
        }

        List<MethodKey> ordered = new ArrayList<>();
        if (target == null) {
            ordered.addAll(seeds);
        } else {
            // Cross interface boundaries: callers that statically invoke
            // an interface method (JDT binding resolves the MethodKey
            // to the interface declaration) are stored under the
            // interface MethodKey in `callSites`. When the user
            // targets a concrete implementation (e.g. Impl#m), the
            // resolved key won't match the recorded key directly —
            // we need to expand to relatedMethods (overrides +
            // implementors + self) and union the call-site maps across
            // the group. See ProjectIndex.recordHierarchy /
            // relatedMethods.
            List<MethodKey> related = new ArrayList<>(index.relatedMethods(target));
            if (related.size() == 1 && related.get(0).equals(target)) {
                ordered.add(target);
            } else {
                // `target` first so the response ordering and overload
                // hint favor the user's selector; related tail.
                ordered.add(target);
                for (MethodKey rk : related) if (!rk.equals(target)) ordered.add(rk);
            }
        }

        Map<MethodKey, List<ProjectIndex.CallSite>> union =
                collectCallSites(index, target, display, ordered, className);

        String message = buildMessage(className, methodName, arity, target, union, ordered);
        // The "(combined callers across N library overloads)" suffix is
        // only accurate for library targets — no project declaration —
        // where `seeds` is the set of recorded call-edge overloads. A
        // project target with a method-hierarchy relation also ends up
        // with ordered.size() > 1, but those keys are overrides /
        // implementors (project types), not library overloads, so they
        // must not get that wording.
        if (target == null && seeds.size() > 1) {
            message = message + MethodResolver.overloadUnionSuffix(seeds);
        }
        message = ProjectIndexCache.withSkippedFilesSuffix(message, index);

        // Pagination applies to the fully-built, stably-sorted list so the
        // window is deterministic across calls (the union's map iteration
        // order is not part of the contract).
        List<Map<String, Object>> allSites =
                buildCallSitesArray(index, union, new SourceSnippet(projectRoot));
        int total = allSites.size();
        List<Map<String, Object>> page = paginate(allSites, offset, limit);
        if (page.size() < total) {
            message = message + " (showing " + (Math.min(offset + 1, total))
                    + "-" + (offset + page.size()) + " of " + total
                    + "; pass offset/limit to paginate)";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        ProjectIndex.SourceLoc targetLoc = index.declarationOf(display);
        Map<String, Object> targetJson = new LinkedHashMap<>();
        targetJson.put("class", display.declaringClass);
        targetJson.put("method", display.methodName);
        targetJson.put("arity", display.arity);
        targetJson.put("signature", display.shortSignature());
        targetJson.put("file", targetLoc != null ? targetLoc.file() : null);
        targetJson.put("line", targetLoc != null ? targetLoc.line() : 0);
        out.put("target", targetJson);
        out.put("status", "ok");
        out.put("message", message);
        out.put("total", total);
        out.put("call_sites", page);
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new FindCallSitesException("Failed to serialize result: " + e.getMessage());
        }
    }

    /**
     * Unions call-site maps across the target's method-hierarchy group
     * (or across a library target's overload seeds), applying the issue
     * #7 / #8 receiver-static-type filter to ancestor expansion.
     *
     * <p>When the same caller appears under multiple keys, we concatenate
     * its site lists — they are line-specific facts and a caller of
     * {@code println(String)} at line 10 is a different fact from a caller
     * of {@code println(int)} at line 10. The caller key is a
     * {@link MethodKey}, which is exact on parameter types, so different
     * overloads of the same caller stay distinct.
     *
     * <p>Every expansion direction (the target itself, its ancestors and
     * its descendants) contributes only the sites whose receiver's static
     * type is hierarchy-comparable with {@code queryClass} — the class the
     * user asked about (issue #7 / #8). Using the query class rather than
     * the resolved target's declaring class is what keeps an inherited
     * query ({@code trace ChildA#save} → {@code Parent#save}) from
     * surfacing a sibling override's callers.
     *
     * <p>Library targets ({@code target == null}) resolve only by strict
     * call-edge matches, whose receivers are the library type itself or a
     * recorded subtype, so the same check is a no-op there in practice.
     */
    private static Map<MethodKey, List<ProjectIndex.CallSite>> collectCallSites(
            ProjectIndex index, MethodKey target, MethodKey display, List<MethodKey> ordered,
            String queryClass) {
        Map<MethodKey, List<ProjectIndex.CallSite>> out = new LinkedHashMap<>();
        // Issue #8: every expansion direction is filtered by whether the
        // call site's receiver could hold an instance of the user's query
        // class — not just ancestor expansion, and not keyed on the
        // resolved target's declaring class (which is broader than the
        // query class for an inherited method).
        for (MethodKey key : ordered) {
            for (Map.Entry<MethodKey, List<ProjectIndex.CallSite>> e
                    : index.callSitesOf(key).entrySet()) {
                List<ProjectIndex.CallSite> sites = e.getValue();
                List<ProjectIndex.CallSite> filtered = new ArrayList<>(sites.size());
                for (ProjectIndex.CallSite site : sites) {
                    if (index.isComparable(queryClass, site.receiverType())) {
                        filtered.add(site);
                    }
                }
                if (filtered.isEmpty()) continue;
                out.merge(e.getKey(), filtered, FindCallSitesService::concat);
            }
        }
        // Issue #9: the calls JDT could not bind (project interface
        // inheriting from a class supertype) carry no callee key, so they
        // are matched against the user's target by receiver type +
        // signature. Each contributes one site at its own position.
        for (ProjectIndex.UnresolvedCall u : index.unresolvedCallersOf(display, queryClass)) {
            out.merge(u.caller(),
                    new ArrayList<>(List.of(
                            new ProjectIndex.CallSite(u.file(), u.line(), u.receiverType()))),
                    FindCallSitesService::concat);
        }
        return out;
    }

    private static List<ProjectIndex.CallSite> concat(List<ProjectIndex.CallSite> a,
                                                      List<ProjectIndex.CallSite> b) {
        List<ProjectIndex.CallSite> combined = new ArrayList<>(a.size() + b.size());
        combined.addAll(a);
        combined.addAll(b);
        return combined;
    }

    /**
     * Returns the {@code [offset, offset + limit)} window of {@code all},
     * clamped to the list. {@code offset} beyond the end yields an empty
     * list; {@code limit} is clamped with a {@code long} addition so
     * {@link #NO_LIMIT} ({@code Integer.MAX_VALUE}) plus an offset cannot
     * overflow.
     */
    private static List<Map<String, Object>> paginate(
            List<Map<String, Object>> all, int offset, int limit) {
        if (offset >= all.size()) return List.of();
        int to = (int) Math.min((long) offset + limit, all.size());
        return List.copyOf(all.subList(offset, to));
    }

    /**
     * Builds the diagnostic message for a (possibly library) target
     * with the given union of call sites. Mirrors the empty-chain
     * diagnostic produced by {@link CallChainAnalyzer} for consistency.
     */
    private static String buildMessage(String className, String methodName, Integer arity,
                                       MethodKey target,
                                       Map<MethodKey, List<ProjectIndex.CallSite>> union,
                                       List<MethodKey> seeds) {
        int totalSites = union.values().stream().mapToInt(List::size).sum();
        int callerCount = union.size();
        if (totalSites == 0) {
            // Distinguish "no project caller" from "no project code
            // touches this at all" — same wording trace_callers uses,
            // so the two tools' diagnostics stay parallel.
            return "No call sites found for '" + className + "#" + methodName
                    + (arity == null ? "" : "/" + arity)
                    + "' in this project's sources. Verify the FQN and method name; "
                    + "if the method is a library method, it may simply not be called here.";
        }
        String noun = totalSites == 1 ? "call site" : "call sites";
        String cnoun = callerCount == 1 ? "caller method" : "caller methods";
        return "Found " + totalSites + " " + noun + " across " + callerCount + " " + cnoun + ".";
    }

    /**
     * Flattens the per-caller call-site map into a list of objects sorted
     * by (caller_file, caller_line, call_site.line) for stable output.
     * Each entry has: caller (short signature), caller_file, caller_line,
     * call_site (file, line). Caller file/line come from
     * {@link ProjectIndex#declarationOf} and are null/0 if the caller is
     * itself a library method (rare but possible).
     */
    private static List<Map<String, Object>> buildCallSitesArray(
            ProjectIndex index, Map<MethodKey, List<ProjectIndex.CallSite>> union,
            SourceSnippet snippets) {
        List<Map.Entry<MethodKey, ProjectIndex.CallSite>> flat = new ArrayList<>();
        for (Map.Entry<MethodKey, List<ProjectIndex.CallSite>> e : union.entrySet()) {
            for (ProjectIndex.CallSite loc : e.getValue()) {
                flat.add(Map.entry(e.getKey(), loc));
            }
        }
        flat.sort(Comparator.<Map.Entry<MethodKey, ProjectIndex.CallSite>, String>comparing(
                        e -> {
                            ProjectIndex.SourceLoc decl = index.declarationOf(e.getKey());
                            return decl != null && decl.file() != null ? decl.file() : "";
                        },
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(e -> {
                    ProjectIndex.SourceLoc decl = index.declarationOf(e.getKey());
                    return decl != null ? decl.line() : 0;
                })
                .thenComparingInt(e -> e.getValue().line()));

        List<Map<String, Object>> out = new ArrayList<>(flat.size());
        for (Map.Entry<MethodKey, ProjectIndex.CallSite> e : flat) {
            ProjectIndex.SourceLoc decl = index.declarationOf(e.getKey());
            Map<String, Object> callSite = new LinkedHashMap<>();
            callSite.put("file", e.getValue().file());
            callSite.put("line", e.getValue().line());
            // Issue #9: the call-site line is the most actionable snippet —
            // it shows the actual call expression in context.
            String snippet = snippets.line(e.getValue().file(), e.getValue().line());
            if (snippet != null) callSite.put("snippet", snippet);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("caller", e.getKey().shortSignature());
            entry.put("caller_file", decl != null ? decl.file() : null);
            entry.put("caller_line", decl != null ? decl.line() : 0);
            entry.put("call_site", callSite);
            out.add(entry);
        }
        return out;
    }

    /** Thrown by {@link #findCallSitesJson} for user-facing error conditions. */
    public static final class FindCallSitesException extends Exception {
        public FindCallSitesException(String message) { super(message); }
    }
}
