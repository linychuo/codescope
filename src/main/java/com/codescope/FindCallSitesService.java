package com.codescope;

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

        Map<MethodKey, List<ProjectIndex.SourceLoc>> union;
        if (ordered.size() == 1) {
            union = index.callSitesOf(ordered.get(0));
        } else {
            union = unionCallSites(index, ordered);
        }

        String message = buildMessage(className, methodName, arity, target, union, ordered);
        if (ordered.size() > 1) {
            message = message + MethodResolver.overloadUnionSuffix(ordered);
        }
        message = ProjectIndexCache.withSkippedFilesSuffix(message, index);

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
        out.put("call_sites", buildCallSitesArray(index, union));
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new FindCallSitesException("Failed to serialize result: " + e.getMessage());
        }
    }

    /**
     * Unions call-site maps across multiple seeds (e.g. several
     * library-method overloads). When the same caller appears under
     * multiple seeds, we concatenate its site lists — they are
     * line-specific facts and a caller of {@code println(String)} at
     * line 10 is a different fact from a caller of {@code println(int)}
     * at line 10. The caller key is a {@link MethodKey}, which is exact
     * on parameter types, so different overloads of the same caller
     * stay distinct.
     */
    private static Map<MethodKey, List<ProjectIndex.SourceLoc>> unionCallSites(
            ProjectIndex index, List<MethodKey> seeds) {
        Map<MethodKey, List<ProjectIndex.SourceLoc>> out = new LinkedHashMap<>();
        for (MethodKey seed : seeds) {
            Map<MethodKey, List<ProjectIndex.SourceLoc>> perSeed = index.callSitesOf(seed);
            for (Map.Entry<MethodKey, List<ProjectIndex.SourceLoc>> e : perSeed.entrySet()) {
                out.merge(e.getKey(), e.getValue(),
                        (a, b) -> {
                            List<ProjectIndex.SourceLoc> combined = new ArrayList<>(a.size() + b.size());
                            combined.addAll(a);
                            combined.addAll(b);
                            return combined;
                        });
            }
        }
        return out;
    }

    /**
     * Builds the diagnostic message for a (possibly library) target
     * with the given union of call sites. Mirrors the empty-chain
     * diagnostic produced by {@link CallChainAnalyzer} for consistency.
     */
    private static String buildMessage(String className, String methodName, Integer arity,
                                       MethodKey target,
                                       Map<MethodKey, List<ProjectIndex.SourceLoc>> union,
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
            ProjectIndex index, Map<MethodKey, List<ProjectIndex.SourceLoc>> union) {
        List<Map.Entry<MethodKey, ProjectIndex.SourceLoc>> flat = new ArrayList<>();
        for (Map.Entry<MethodKey, List<ProjectIndex.SourceLoc>> e : union.entrySet()) {
            for (ProjectIndex.SourceLoc loc : e.getValue()) {
                flat.add(Map.entry(e.getKey(), loc));
            }
        }
        flat.sort(Comparator.<Map.Entry<MethodKey, ProjectIndex.SourceLoc>, String>comparing(
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
        for (Map.Entry<MethodKey, ProjectIndex.SourceLoc> e : flat) {
            ProjectIndex.SourceLoc decl = index.declarationOf(e.getKey());
            Map<String, Object> callSite = new LinkedHashMap<>();
            callSite.put("file", e.getValue().file());
            callSite.put("line", e.getValue().line());
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
