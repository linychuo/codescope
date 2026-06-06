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
    public final List<CallNode> callers = new ArrayList<>();

    public CallNode(String className, String methodName, int arity, String file, int line) {
        this(className, methodName, arity, file, line, false);
    }

    public CallNode(String className, String methodName, int arity,
                    String file, int line, boolean cycle) {
        this.className = className;
        this.methodName = methodName;
        this.arity = arity;
        this.signature = className + "#" + methodName + "/" + arity;
        this.file = file;
        this.line = line;
        this.cycle = cycle;
    }

    /**
     * Builds a back-edge marker for a method that has already been visited
     * on the current chain. Markers carry class + method + arity but no
     * source location, so the tree stays compact when cycles are present.
     */
    public static CallNode cycleMarker(String className, String methodName, int arity) {
        return new CallNode(className, methodName, arity, null, 0, true);
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
