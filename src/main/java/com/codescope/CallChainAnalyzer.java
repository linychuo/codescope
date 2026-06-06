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
     * BFS over the reverse call index starting from {@code target}. Each path
     * carries its own ancestor set: a method already on the current path is a
     * true back-edge (cycle marker); a method reached via two different paths
     * (a diamond) is a real caller on the second path and is not marked as
     * a cycle.
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

        Deque<PathFrame> queue = new ArrayDeque<>();
        queue.addLast(new PathFrame(target, root, Set.of(target)));

        int nodes = 1;
        while (!queue.isEmpty()) {
            PathFrame f = queue.removeFirst();
            List<MethodKey> callers = index.callersOf(f.key);
            for (MethodKey caller : callers) {
                if (f.ancestors.contains(caller)) {
                    // True back-edge on the current path -> cycle marker.
                    f.node.addChild(CallNode.cycleMarker(
                            caller.declaringClass, caller.methodName, caller.arity));
                    continue;
                }
                ProjectIndex.SourceLoc loc = index.declarationOf(caller);
                CallNode child = new CallNode(
                        caller.declaringClass, caller.methodName, caller.arity,
                        loc != null ? loc.file() : null,
                        loc != null ? loc.line() : 0);
                f.node.addChild(child);
                // Diamond: same method reached via another path is a real
                // caller on this branch. We add `caller` to the ancestors of
                // *its* descendants, not to its own ancestors.
                Set<MethodKey> childAncestors = new HashSet<>(f.ancestors.size() + 1);
                childAncestors.add(caller);
                childAncestors.addAll(f.ancestors);
                queue.addLast(new PathFrame(caller, child, childAncestors));
                nodes++;
                if (nodes > MAX_NODES) {
                    return new Result(root, true,
                            "Truncated at " + MAX_NODES + " nodes to prevent runaway expansion. "
                                    + "There may be a deeply-recursive or hot method in the chain.");
                }
            }
        }
        return new Result(root, true, "OK; " + nodes + " method(s) in chain.");
    }

    private record PathFrame(MethodKey key, CallNode node, Set<MethodKey> ancestors) {}

    /** Safety cap on tree size; configurable per-tool-call later. */
    private static final int MAX_NODES = 50_000;
}
