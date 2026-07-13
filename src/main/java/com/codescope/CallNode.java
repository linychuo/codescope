package com.codescope;

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
    public final String signature;   // "pkg.Cls#method/arity(int,String)" or short if no params
    public final String file;        // declaration file, relative if possible
    public final int line;           // declaration line, 0 if unknown
    public final boolean cycle;      // true if this is a back-edge marker
    public final boolean truncated;  // true if this branch was cut at MAX_DEPTH
    public final boolean fanoutTruncated;  // true if this branch was cut at MAX_CALLERS_PER_FRAME
    public final int hiddenCallerCount;    // number of callers omitted due to fan-out cap
    public final List<CallNode> callers = new ArrayList<>();

    public CallNode(String className, String methodName, int arity, String file, int line) {
        this(className, methodName, arity, file, line, false, false, false, 0);
    }

    public CallNode(String className, String methodName, int arity,
                    String file, int line, boolean cycle) {
        this(className, methodName, arity, file, line, cycle, false, false, 0);
    }

    private CallNode(String className, String methodName, int arity,
                     String file, int line, boolean cycle, boolean truncated,
                     boolean fanoutTruncated, int hiddenCallerCount) {
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
    }

    /**
     * Builds a back-edge marker for a method that has already been visited
     * on the current chain. Markers carry class + method + arity but no
     * source location, so the tree stays compact when cycles are present.
     */
    public static CallNode cycleMarker(String className, String methodName, int arity) {
        return new CallNode(className, methodName, arity, null, 0, true, false, false, 0);
    }

    /**
     * Builds a depth-cap marker for a branch that hit the BFS depth limit.
     * Distinct from {@link #cycleMarker} so callers can tell "we stopped to
     * avoid a loop" from "we stopped because the chain is too deep to
     * serialize safely".
     */
    public static CallNode depthMarker(String className, String methodName, int arity) {
        return new CallNode(className, methodName, arity, null, 0, false, true, false, 0);
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
        return new CallNode(className, methodName, arity, null, 0, false, false, true, hiddenCount);
    }

    public CallNode addChild(CallNode child) {
        callers.add(child);
        return child;
    }

    /**
     * Serialize to a JSON-shaped map. Iterative (not recursive) so deep call
     * chains — up to {@code MAX_NODES} — don't blow the JVM stack.
     *
     * <p>Each occurrence of a {@code CallNode} in the tree becomes its own
     * map: the same method reached via two paths produces two distinct maps,
     * matching the prior recursive semantics.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> rootMap = new LinkedHashMap<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(this, rootMap));
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (f.node.file != null) f.map.put("file", f.node.file);
            if (f.node.line > 0) f.map.put("line", f.node.line);
            f.map.put("class", f.node.className);
            f.map.put("method", f.node.methodName);
            f.map.put("arity", f.node.arity);
            f.map.put("signature", f.node.signature);
            if (f.node.cycle) f.map.put("cycle", true);
            if (f.node.truncated) f.map.put("truncated", true);
            if (f.node.fanoutTruncated) f.map.put("truncatedCallers", f.node.hiddenCallerCount);
            if (f.node.callers.isEmpty()) continue;
            List<Map<String, Object>> kids = new ArrayList<>(f.node.callers.size());
            List<Frame> childFrames = new ArrayList<>(f.node.callers.size());
            for (CallNode c : f.node.callers) {
                Map<String, Object> childMap = new LinkedHashMap<>();
                kids.add(childMap);
                childFrames.add(new Frame(c, childMap));
            }
            f.map.put("callers", kids);
            // Push children in reverse so they're processed in source order.
            for (int i = childFrames.size() - 1; i >= 0; i--) {
                stack.push(childFrames.get(i));
            }
        }
        return rootMap;
    }

    private record Frame(CallNode node, Map<String, Object> map) {}
}
