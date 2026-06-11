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
     * error. Ambiguity is reported upstream by
     * {@link ProjectIndex#resolveTarget} throwing
     * {@link ProjectIndex.AmbiguousMethodException} before the BFS even runs.
     */
    public Result traceCallers(ProjectIndex index, MethodKey target) {
        return traceCallers(index, target, List.of(target));
    }

    /**
     * Multi-seed BFS for library targets where the user query (e.g.
     * {@code java.io.PrintStream#println}) matches several recorded
     * overloads. All seeds expand into a single tree rooted at
     * {@code displayTarget}, with caller dedup keyed on {@link MethodKey}
     * — a method that calls multiple overloads of {@code println} appears
     * once, not once per overload. The MAX_NODES / MAX_DEPTH caps apply
     * to the combined traversal so one pathological overload can't blow
     * the budget.
     *
     * <p>For single-seed calls (the common case), {@link #traceCallers(ProjectIndex, MethodKey)}
     * delegates here with a one-element seed list.
     */
    public Result traceCallers(ProjectIndex index, MethodKey displayTarget, List<MethodKey> seeds) {
        ProjectIndex.SourceLoc rootLoc = index.declarationOf(displayTarget);
        CallNode root = new CallNode(
                displayTarget.declaringClass, displayTarget.methodName, displayTarget.arity,
                rootLoc != null ? rootLoc.file() : null,
                rootLoc != null ? rootLoc.line() : 0);

        // Seed the queue with one frame per overload. They all share the
        // same root CallNode, so children land in one tree. Ancestors are
        // per-path; we seed each frame's ancestor set with all the seed
        // keys so that any seed appearing as a transitive caller (rare,
        // but possible for mutually-recursive overloads) becomes a cycle
        // marker, not an infinite loop.
        Set<MethodKey> seedAncestors = new HashSet<>(seeds);
        Deque<PathFrame> queue = new ArrayDeque<>();
        // Dedup direct callers across overloads: the same caller can
        // invoke println(String) AND println(int); it should appear once
        // under the root, not twice. Shared across all root frames
        // (multiple seeds) so the multi-seed library case still dedups.
        Set<MethodKey> directCallersSeen = new HashSet<>();
        for (MethodKey seed : seeds) {
            queue.addLast(new PathFrame(seed, root, seedAncestors, 1, true));
        }

        int nodes = 1;
        int callerCount = 0;
        while (!queue.isEmpty()) {
            PathFrame f = queue.removeFirst();
            // depth cap: see MAX_DEPTH javadoc.
            if (f.depth >= MAX_DEPTH) {
                f.node.addChild(CallNode.depthMarker(
                        f.key.declaringClass, f.key.methodName, f.key.arity));
                continue;
            }
            // Cross interface boundaries: when f.key is a class method
            // implementing an interface, callers that statically go
            // through the interface (recorded under the interface
            // MethodKey) would otherwise be invisible. Querying all
            // relatedMethods (overrides + implementors + self) lets
            // the BFS pick up the interface-typed call sites.
            // Each caller's dedup is per-frame so the same caller
            // appearing under multiple related keys collapses to one
            // tree node — diamond convergence across unrelated paths
            // (different parents reaching the same C) is still preserved
            // by the per-path ancestor check above.
            for (MethodKey relatedKey : index.relatedMethods(f.key)) {
                for (MethodKey caller : index.callersOf(relatedKey)) {
                    if (f.ancestors.contains(caller)) {
                        // True back-edge on the current path -> cycle marker.
                        f.node.addChild(CallNode.cycleMarker(
                                caller.declaringClass, caller.methodName, caller.arity));
                        continue;
                    }
                    // Direct-caller dedup ONLY applies at the root level
                    // (depth 1, isRoot=true) when multiple seeds share the
                    // same caller. Below the root each path has its own
                    // ancestors and we want the full subtree for diamonds.
                    if (f.isRoot && !directCallersSeen.add(caller)) continue;
                    // Per-frame dedup so the same caller discovered via
                    // multiple related keys (e.g. M1 and M2 in
                    // relatedMethods) doesn't show up twice as a child
                    // of this frame.
                    if (!f.localCallersSeen.add(caller)) continue;

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
                    queue.addLast(new PathFrame(caller, child, childAncestors, f.depth + 1, false));
                    nodes++;
                    callerCount++;
                    if (nodes > MAX_NODES) {
                        return new Result(root, true,
                                "Truncated at " + MAX_NODES + " nodes to prevent runaway expansion. "
                                        + "There may be a deeply-recursive or hot method in the chain.");
                    }
                }
            }
        }
        if (callerCount == 0) {
            return new Result(root, true,
                    "No callers found for '" + displayTarget.declaringClass + "#"
                            + displayTarget.methodName + "/" + displayTarget.arity
                            + "' in this project's sources. Verify the FQN and method name; "
                            + "if the method is a library method, it may simply not be called here.");
        }
        return new Result(root, true, "OK; " + callerCount + " caller(s) in chain.");
    }

    private record PathFrame(MethodKey key, CallNode node, Set<MethodKey> ancestors,
                             int depth, boolean isRoot,
                             Set<MethodKey> localCallersSeen) {
        PathFrame(MethodKey key, CallNode node, Set<MethodKey> ancestors,
                  int depth, boolean isRoot) {
            this(key, node, ancestors, depth, isRoot, new HashSet<>());
        }
    }

    /** Safety cap on tree size; configurable per-tool-call later. */
    private static final int MAX_NODES = 50_000;

    /**
     * Safety cap on tree depth. {@link CallNode#toJson()} builds the tree
     * iteratively, so depth is not bounded by the JVM stack — but
     * Jackson's serializer recurses, and a 2000-deep chain blows the
     * default thread stack with {@code StackOverflowError}. 500 leaves
     * headroom for production server stacks (default 512KB) and is well
     * under what any realistic caller-chain project would produce.
     */
    private static final int MAX_DEPTH = 500;
}
