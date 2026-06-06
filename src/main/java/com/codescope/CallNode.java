package com.codescope;

import java.util.ArrayList;
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

    public CallNode addChild(CallNode child) {
        callers.add(child);
        return child;
    }

    /** Serialize to a JSON-shaped map (so Jackson emits clean output). */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", className);
        m.put("method", methodName);
        m.put("arity", arity);
        m.put("signature", signature);
        if (cycle) m.put("cycle", true);
        if (file != null) m.put("file", file);
        if (line > 0) m.put("line", line);
        if (!callers.isEmpty()) {
            List<Map<String, Object>> kids = new ArrayList<>(callers.size());
            for (CallNode c : callers) kids.add(c.toJson());
            m.put("callers", kids);
        }
        return m;
    }
}
