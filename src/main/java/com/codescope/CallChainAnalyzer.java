package com.codescope;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Given a {@link ProjectIndex}, builds a tree of transitive callers of a target method. */
public final class CallChainAnalyzer {

    public record Result(CallNode root, boolean found, String message) {}

    /**
     * BFS over the reverse call index starting from {@code target}. Cycles are
     * collapsed: a method encountered via two paths appears once, and a
     * back-edge is recorded as a cycle marker so the tree is finite.
     */
    public Result traceCallers(ProjectIndex index, MethodKey target) {
        ProjectIndex.SourceLoc rootLoc = index.declarationOf(target);
        if (rootLoc == null) {
            // try to find any matching (class, method) to give a useful error
            MethodKey alt;
            try {
                alt = index.resolveTarget(target.declaringClass, target.methodName);
            } catch (ProjectIndex.AmbiguousMethodException e) {
                return new Result(
                        new CallNode(target.declaringClass, target.methodName, target.arity, null, 0),
                        false, e.getMessage());
            }
            if (alt == null) {
                return new Result(
                        new CallNode(target.declaringClass, target.methodName, target.arity, null, 0),
                        false,
                        "Method not found in any source file. Check the class FQN, method name, "
                                + "and that the project sources are on the analyzed source roots.");
            }
            target = alt;
            rootLoc = index.declarationOf(target);
        }
        CallNode root = new CallNode(
                target.declaringClass, target.methodName, target.arity,
                rootLoc != null ? rootLoc.file() : null,
                rootLoc != null ? rootLoc.line() : 0);

        Set<MethodKey> visited = new HashSet<>();
        visited.add(target);

        Deque<Frame> queue = new ArrayDeque<>();
        queue.add(new Frame(target, root));

        int nodes = 1;
        while (!queue.isEmpty()) {
            Frame f = queue.removeFirst();
            List<MethodKey> callers = index.callersOf(f.key);
            for (MethodKey caller : callers) {
                if (visited.add(caller)) {
                    ProjectIndex.SourceLoc loc = index.declarationOf(caller);
                    CallNode child = new CallNode(
                            caller.declaringClass, caller.methodName, caller.arity,
                            loc != null ? loc.file() : null,
                            loc != null ? loc.line() : 0);
                    f.node.addChild(child);
                    queue.addLast(new Frame(caller, child));
                    nodes++;
                    if (nodes > MAX_NODES) {
                        return new Result(root, true,
                                "Truncated at " + MAX_NODES + " nodes to prevent runaway expansion. "
                                        + "There may be a deeply-recursive or hot method in the chain.");
                    }
                } else {
                    // back-edge: mark it on the parent so the tree stays finite
                    f.node.addChild(CallNode.cycleMarker(
                            caller.declaringClass, caller.methodName, caller.arity));
                }
            }
        }
        return new Result(root, true, "OK; " + nodes + " method(s) in chain.");
    }

    private record Frame(MethodKey key, CallNode node) {}

    /** Safety cap on tree size; configurable per-tool-call later. */
    private static final int MAX_NODES = 50_000;
}
