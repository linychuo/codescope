package com.codescope.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tree node representing a method in a call chain. Serializes to JSON. */
public final class CallNode {
    public final String className;
    public final String methodName;
    public final int arity;
    public final String signature;   // "pkg.Cls#method/arity"
    public final String file;        // declaration file, relative if possible
    public final int line;           // declaration line, 0 if unknown
    public final boolean cycle;      // true if this is a back-edge marker
    public final boolean truncated;  // true if this branch was cut at MAX_DEPTH
    public final boolean fanoutTruncated;  // true if this branch was cut at MAX_CALLERS_PER_FRAME
    public final int hiddenCallerCount;    // number of callers omitted due to fan-out cap
    public final boolean deduped;    // true if this is a marker for a method already shown elsewhere in the tree
    // Whole-tree signal: true only on the root when the analyzer's BFS
    // bailed out at MAX_NODES and the tree is partial. Mutated after
    // construction because the cap is detected mid-BFS, not at root
    // creation time. Never propagated to children — it's a property of
    // the analyzed request, not of any individual method.
    private boolean nodeCapTruncated;
    /**
     * Framework entry-point kind for this method (e.g. {@code "spring-mvc"},
     * {@code "junit5"}, {@code "main"}), or null for an ordinary method
     * (issue #9). Set by the analyzers from
     * {@link ProjectIndex#entryPointKindOf(MethodKey)} after construction.
     */
    private String entryPoint;
    /**
     * MyBatis mapper statement SQL for this node (issue #9 item 4), set
     * when the node is a mapper XML statement rather than a Java method.
     * {@code sqlType} is {@code select}/{@code insert}/{@code update}/
     * {@code delete}/{@code sql}; {@code sqlText} is the normalized body.
     */
    private String sqlType;
    private String sqlText;
    public final List<CallNode> callers = new ArrayList<>();

    public CallNode(String className, String methodName, int arity, String file, int line) {
        this(className, methodName, arity, file, line, false, false, false, 0, false);
    }

    public CallNode(String className, String methodName, int arity,
                    String file, int line, boolean cycle) {
        this(className, methodName, arity, file, line, cycle, false, false, 0, false);
    }

    private CallNode(String className, String methodName, int arity,
                     String file, int line, boolean cycle, boolean truncated,
                     boolean fanoutTruncated, int hiddenCallerCount, boolean deduped) {
        this.className = className;
        this.methodName = methodName;
        this.arity = arity;
        this.signature = className + "#" + methodName + "/" + arity;
        this.file = file;
        this.line = line;
        this.cycle = cycle;
        this.truncated = truncated;
        this.fanoutTruncated = fanoutTruncated;
        this.hiddenCallerCount = hiddenCallerCount;
        this.deduped = deduped;
    }

    /**
     * Builds a back-edge marker for a method that has already been visited
     * on the current chain. Markers carry class + method + arity but no
     * source location, so the tree stays compact when cycles are present.
     */
    public static CallNode cycleMarker(String className, String methodName, int arity) {
        return new CallNode(className, methodName, arity, null, 0, true, false, false, 0, false);
    }

    /**
     * Builds a depth-cap marker for a branch that hit the BFS depth limit.
     * Distinct from {@link #cycleMarker} so callers can tell "we stopped to
     * avoid a loop" from "we stopped because the chain is too deep to
     * serialize safely".
     */
    public static CallNode depthMarker(String className, String methodName, int arity) {
        return new CallNode(className, methodName, arity, null, 0, false, true, false, 0, false);
    }

    /**
     * Builds a fan-out cap marker for a method whose caller set was
     * truncated at {@code MAX_CALLERS_PER_FRAME}. Distinct from
     * {@link #depthMarker} so callers can tell "we stopped at the
     * depth limit" from "we stopped because this method has too many
     * direct callers to enumerate in full".
     *
     * @param hiddenCount number of callers that were discovered but
     *                    omitted from the tree
     */
    public static CallNode fanoutMarker(String className, String methodName,
                                        int arity, int hiddenCount) {
        return new CallNode(className, methodName, arity, null, 0, false, false, true, hiddenCount, false);
    }

    /**
     * Builds a marker for a method that already appears as a full node
     * elsewhere in the tree (a diamond). The marker carries the method's
     * identity and declaration location so a reader can navigate to it,
     * but no callers subtree — keeping the tree compact when popular
     * upstream methods are reached via many paths.
     */
    public static CallNode dedupedMarker(String className, String methodName, int arity,
                                         String file, int line) {
        return new CallNode(className, methodName, arity, file, line, false, false, false, 0, true);
    }

    public CallNode addChild(CallNode child) {
        callers.add(child);
        return child;
    }

    /**
     * Marks this node's whole tree as having been truncated at the
     * analyzer's MAX_NODES safety cap. Should only be called on the
     * root of the tree, and only by the analyzer when it bails out
     * mid-BFS — a partial tree otherwise looks identical to a complete
     * one to consumers that ignore the analyzer's textual message.
     *
     * <p>Distinct from the {@code truncated} flag set by
     * {@link #depthMarker}, which is per-branch (a depth-cut child).
     * The whole-tree signal is consumed by callers iterating
     * {@code root.toJson()} from the {@code trace_callers} envelope.
     */
    public void markNodeCapTruncated() {
        this.nodeCapTruncated = true;
    }

    /**
     * Labels this node as a framework entry point of the given kind (issue
     * #9), or clears the label when {@code kind} is null. Only the node's
     * own JSON gains an {@code "entryPoint"} key — markers (cycle, depth,
     * fan-out) carry no subtree and no declaration binding, so callers set
     * it only on real nodes.
     */
    public void markEntryPoint(String kind) {
        this.entryPoint = kind;
    }

    /**
     * Marks this node as a MyBatis mapper XML statement (issue #9 item 4)
     * and attaches its SQL. Only {@code trace_callees} creates such nodes:
     * they are leaves (an XML statement has no outgoing call edges).
     */
    public void markSql(String type, String sql) {
        this.sqlType = type;
        this.sqlText = sql;
    }

    /**
     * Resolves the source line text for a {@code (file, line)} pair, used
     * to add a {@code snippet} field to each serialized node (issue #9:
     * results used to carry only {@code file} + {@code line}). Returns
     * null when the source is unavailable, in which case the key is
     * omitted.
     *
     * <p>Implemented by {@link com.codescope.index.SourceSnippet} in
     * production; the analyzer itself has no access to the project root.
     */
    @FunctionalInterface
    public interface SnippetResolver {
        String resolve(String file, int line);
    }

    /**
     * Serialize to a JSON-shaped map. Iterative (not recursive) so deep call
     * chains — up to {@code MAX_NODES} — don't blow the JVM stack.
     *
     * <p>Each unique method appears at most once as a full node; subsequent
     * occurrences (diamonds — the same method reached via two different
     * parents) become a {@code "deduped": true} marker carrying the method's
     * identity and declaration location but no callers subtree. This keeps
     * the tree compact for popular upstream methods without losing the
     * signal that the method is reached from multiple paths.
     */
    public Map<String, Object> toJson() {
        return toJson(null);
    }

    /**
     * As {@link #toJson()}, adding a {@code "snippet"} key (the source line
     * at the node's declaration location) when {@code snippetResolver} is
     * non-null and can resolve it.
     */
    public Map<String, Object> toJson(SnippetResolver snippetResolver) {
        return toJson(snippetResolver, "callers");
    }

    /**
     * As {@link #toJson(SnippetResolver)}, with the child array emitted
     * under a caller-supplied key. {@code trace_callers} uses
     * {@code "callers"}; {@code trace_callees} (issue #9) uses
     * {@code "callees"} — the node shapes are otherwise identical (the
     * same marker flags apply), so one class serves both directions.
     *
     * @param childrenKey the JSON key for the child list, e.g.
     *                    {@code "callers"} or {@code "callees"}
     */
    public Map<String, Object> toJson(SnippetResolver snippetResolver, String childrenKey) {
        Map<String, Object> rootMap = new LinkedHashMap<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(this, rootMap));
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (f.node.file != null) f.map.put("file", f.node.file);
            if (f.node.line > 0) f.map.put("line", f.node.line);
            if (snippetResolver != null && f.node.file != null && f.node.line > 0) {
                String snippet = snippetResolver.resolve(f.node.file, f.node.line);
                if (snippet != null) f.map.put("snippet", snippet);
            }
            f.map.put("class", f.node.className);
            f.map.put("method", f.node.methodName);
            f.map.put("arity", f.node.arity);
            f.map.put("signature", f.node.signature);
            if (f.node.cycle) f.map.put("cycle", true);
            if (f.node.truncated) f.map.put("truncated", true);
            if (f.node.fanoutTruncated) f.map.put("truncatedCallers", f.node.hiddenCallerCount);
            if (f.node.deduped) f.map.put("deduped", true);
            if (f.node.entryPoint != null) f.map.put("entryPoint", f.node.entryPoint);
            if (f.node.sqlText != null) {
                Map<String, Object> sql = new LinkedHashMap<>();
                sql.put("type", f.node.sqlType);
                sql.put("text", f.node.sqlText);
                f.map.put("sql", sql);
            }
            if (f.node.nodeCapTruncated) f.map.put("nodeCapTruncated", true);
            List<Map<String, Object>> kids = new ArrayList<>(f.node.callers.size());
            List<Frame> childFrames = new ArrayList<>(f.node.callers.size());
            for (CallNode c : f.node.callers) {
                Map<String, Object> childMap = new LinkedHashMap<>();
                kids.add(childMap);
                childFrames.add(new Frame(c, childMap));
            }
            // Always emit the children key — even as [] for leaves — so
            // downstream consumers can iterate `obj.callers` /
            // `obj.callees` uniformly instead of branching on "missing vs
            // empty array".
            f.map.put(childrenKey, kids);
            if (childFrames.isEmpty()) continue;
            // Push children in reverse so they're processed in source order.
            for (int i = childFrames.size() - 1; i >= 0; i--) {
                stack.push(childFrames.get(i));
            }
        }
        return rootMap;
    }

    private record Frame(CallNode node, Map<String, Object> map) {}
}
