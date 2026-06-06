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
     *
     * <p>The BFS works for both project and library targets: callers are
     * discovered from the call edges recorded by {@link JdtIndexer}, not from
     * the target's own declaration. A target with no project callers (e.g. a
     * library method that this project never invokes) returns
     * {@code found=true} with an empty chain — that IS the answer, not an
     * error. We only fail when the target is so ambiguous we can't even
     * give an empty answer meaningfully (see {@link #diagnoseNotFound}).
     */
    public Result traceCallers(ProjectIndex index, MethodKey target) {
        return bfs(index, target);
    }

    private Result bfs(ProjectIndex index, MethodKey target) {
        ProjectIndex.SourceLoc rootLoc = index.declarationOf(target);
        CallNode root = new CallNode(
                target.declaringClass, target.methodName, target.arity,
                rootLoc != null ? rootLoc.file() : null,
                rootLoc != null ? rootLoc.line() : 0);

        Deque<PathFrame> queue = new ArrayDeque<>();
        queue.addLast(new PathFrame(target, root, Set.of(target)));

        int nodes = 1;
        int callerCount = 0;
        while (!queue.isEmpty()) {
            PathFrame f = queue.removeFirst();
            for (MethodKey caller : index.callersOf(f.key)) {
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
                callerCount++;
                if (nodes > MAX_NODES) {
                    return new Result(root, true,
                            "Truncated at " + MAX_NODES + " nodes to prevent runaway expansion. "
                                    + "There may be a deeply-recursive or hot method in the chain.");
                }
            }
        }
        if (callerCount == 0) {
            return new Result(root, true,
                    "No callers found for '" + target.declaringClass + "#"
                            + target.methodName + "/" + target.arity
                            + "' in this project's sources. Verify the FQN and method name; "
                            + "if the method is a library method, it may simply not be called here.");
        }
        return new Result(root, true, "OK; " + callerCount + " caller(s) in chain.");
    }

    private record PathFrame(MethodKey key, CallNode node, Set<MethodKey> ancestors) {}

    /** Safety cap on tree size; configurable per-tool-call later. */
    private static final int MAX_NODES = 50_000;
}
