package com.codescope.analysis;

import com.codescope.index.ProjectIndex;
import com.codescope.model.MethodKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Given a {@link ProjectIndex}, builds a tree of transitive callers of a target method. */
public final class CallChainAnalyzer {

    public record Result(CallNode root, boolean found, String message) {}

    /** Default {@link #maxNodes}, matching {@link ProjectIndexCache}'s JSON nesting depth. */
    public static final int DEFAULT_MAX_NODES = 50_000;

    /**
     * Default {@link #maxDepth}: the maximum number of caller levels the
     * BFS expands. See {@link #maxDepth} for why this is a safety cap.
     */
    public static final int DEFAULT_MAX_DEPTH = 500;

    private final int maxNodes;

    /**
     * Maximum number of caller levels to expand: 0 keeps only the target,
     * 1 adds direct callers, 2 adds their callers, and so on. This is a
     * safety cap as much as a feature — {@link CallNode#toJson()} builds
     * the tree iteratively, so depth is not bounded by the JVM stack, but
     * Jackson's serializer recurses and a 2000-deep chain blows the
     * default thread stack with {@code StackOverflowError}. The default
     * (500) leaves headroom for production server stacks (default 512KB)
     * and is well above any realistic caller-chain project.
     */
    private final int maxDepth;

    /** Production constructor — uses {@link #DEFAULT_MAX_NODES} / {@link #DEFAULT_MAX_DEPTH}. */
    public CallChainAnalyzer() {
        this(DEFAULT_MAX_NODES, DEFAULT_MAX_DEPTH);
    }

    /**
     * Test-friendly constructor that lets callers dial the node cap
     * down to a small number so a fixture doesn't need 50k+ real
     * methods to exercise the truncation path. Intended for tests;
     * production callers should use the no-arg form.
     */
    CallChainAnalyzer(int maxNodes) {
        this(maxNodes, DEFAULT_MAX_DEPTH);
    }

    /**
     * Full-control constructor: {@code maxNodes} bounds the whole tree,
     * {@code maxDepth} bounds how many caller levels are expanded
     * (0 = the target only, 1 = direct callers, ...).
     */
    public CallChainAnalyzer(int maxNodes, int maxDepth) {
        this.maxNodes = maxNodes;
        this.maxDepth = maxDepth;
    }

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
        return traceCallers(index, target, List.of(target), target.declaringClass);
    }

    /**
     * As {@link #traceCallers(ProjectIndex, MethodKey)}, with an explicit
     * <em>query class</em> used as the receiver context for the root frame
     * (issue #8). Pass the class the user asked about, which may differ
     * from the resolved target's declaring class when the method is
     * inherited.
     */
    public Result traceCallers(ProjectIndex index, MethodKey target, String queryClass) {
        return traceCallers(index, target, List.of(target), queryClass);
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
        return traceCallers(index, displayTarget, seeds, displayTarget.declaringClass);
    }

    /**
     * As {@link #traceCallers(ProjectIndex, MethodKey, List)}, with an
     * explicit <em>query class</em> used as the receiver context for the
     * root frame (issue #8).
     *
     * @param queryClass the class the user asked about; a call site only
     *                   counts when its receiver could hold an instance of
     *                   it (hierarchy-comparable). Deeper frames use the
     *                   frame key's declaring class, since each level asks
     *                   "who calls this method?".
     */
    public Result traceCallers(ProjectIndex index, MethodKey displayTarget, List<MethodKey> seeds,
                               String queryClass) {
        ProjectIndex.SourceLoc rootLoc = index.declarationOf(displayTarget);
        CallNode root = new CallNode(
                displayTarget.declaringClass, displayTarget.methodName, displayTarget.arity,
                rootLoc != null ? rootLoc.file() : null,
                rootLoc != null ? rootLoc.line() : 0);
        // Issue #9: label the root when it is a framework entry point.
        root.markEntryPoint(index.entryPointKindOf(displayTarget));

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
        // Shared across root frames so the fan-out cap applies to the union
        // of all seeds, not per seed (the root CallNode is shared too).
        Set<MethodKey> rootCollected = new LinkedHashSet<>();
        // Root frame depth is 0 (the target itself); each caller level
        // increments it. The depth cap therefore reads naturally:
        // maxDepth=1 keeps only direct callers, maxDepth=2 adds one more
        // level, and so on.
        // The root frame's receiver context is the class the user asked
        // about (issue #8), which may be a subtype of the resolved target's
        // declaring class when the method is inherited.
        String rootContext = queryClass != null ? queryClass : displayTarget.declaringClass;
        for (MethodKey seed : seeds) {
            queue.addLast(new PathFrame(seed, root, seedAncestors, 0, true, rootContext));
        }

        int nodes = 1;
        // Per-frame dedup (localCallersSeen / collected) doesn't cover the
        // diamond case where one method is reached via two parents; this
        // set tracks tree-wide so the second occurrence becomes a
        // dedupedMarker instead of a duplicate subtree.
        Set<MethodKey> seenInTree = new HashSet<>();
        while (!queue.isEmpty()) {
            PathFrame f = queue.removeFirst();
            // Depth cap: stop expanding at maxDepth caller levels. The
            // marker is only emitted when there is actually more to
            // expand — a leaf sitting exactly on the boundary should not
            // be reported as truncated.
            if (f.depth >= maxDepth) {
                if (hasAnyCaller(index, f.key)) {
                    f.node.addChild(CallNode.depthMarker(
                            f.key.declaringClass, f.key.methodName, f.key.arity));
                }
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
            // Collect this frame's candidate callers into one ordered set:
            // resolved callers grouped by related key first (stable order),
            // then the unresolved calls (JDT could not bind the invocation)
            // that match the frame key by receiver type + signature. The
            // cap below is enforced on this union, not per relatedKey.
            Set<MethodKey> frameCallers = new LinkedHashSet<>();
            for (MethodKey relatedKey : index.relatedMethods(f.key)) {
                // Issue #7 / #8: every expansion direction (self,
                // ancestors, descendants) is filtered by whether the call
                // site's receiver could hold an instance of the frame's
                // context class. Without this, expanding ChildA#save →
                // Parent#save also picks up every other caller of
                // Parent#save — including ChildB#save, a sibling override
                // that merely calls super.save() — merging the siblings'
                // callers; and expanding Parent#save → ChildB#save picks up
                // callers on a ChildB receiver when the user asked about an
                // inherited ChildA#save.
                //
                // Receiver types are only known when the indexer recorded
                // call sites (production always does; a hand-built index
                // that only called recordInvocation has none). Without
                // site data there is nothing to filter on, so the caller
                // is kept — the historical behavior.
                Map<MethodKey, List<ProjectIndex.CallSite>> sitesByCaller =
                        index.callSitesOfSet(relatedKey);
                for (MethodKey caller : index.callersOfSet(relatedKey)) {
                    List<ProjectIndex.CallSite> sites = sitesByCaller.get(caller);
                    if (sites != null && !anySiteMatchesContext(index, f.contextClass, sites)) {
                        continue;
                    }
                    frameCallers.add(caller);
                }
            }
            // Issue #9: attribute the calls JDT could not bind (project
            // interface inheriting from a class supertype) by receiver
            // type + signature. See ProjectIndex.unresolvedCallersOf.
            for (ProjectIndex.UnresolvedCall u
                    : index.unresolvedCallersOf(f.key, f.contextClass)) {
                frameCallers.add(u.caller());
            }
            Set<MethodKey> collected = f.isRoot ? rootCollected : new LinkedHashSet<>();
            int hidden = 0;
            for (MethodKey caller : frameCallers) {
                // A true back-edge on the current path is a cycle
                // marker, not an expandable caller. Markers carry no
                // subtree, so they are always recorded — the fan-out
                // cap below bounds the callers the BFS expands, not
                // the markers it emits. Checking this first also keeps
                // `hidden` an accurate count of omitted callers rather
                // than folding cycle markers into it. Dedup via
                // localCallersSeen so the same ancestor reached through
                // two related keys yields one marker, not two.
                if (f.ancestors.contains(caller)) {
                    if (f.localCallersSeen.add(caller)) {
                        f.node.addChild(CallNode.cycleMarker(
                                caller.declaringClass, caller.methodName, caller.arity));
                    }
                    continue;
                }
                if (collected.size() >= MAX_CALLERS_PER_FRAME) {
                    hidden++;
                    continue;
                }
                // Direct-caller dedup ONLY applies at the root level
                // (isRoot=true) when multiple seeds share the same
                // caller. Below the root each path has its own
                // ancestors and we want the full subtree for diamonds.
                if (f.isRoot && !directCallersSeen.add(caller)) continue;
                // Per-frame dedup so the same caller discovered via
                // multiple related keys (e.g. M1 and M2 in
                // relatedMethods) doesn't show up twice as a child
                // of this frame.
                if (!f.localCallersSeen.add(caller)) continue;
                if (!collected.add(caller)) continue;
                ProjectIndex.SourceLoc loc = index.declarationOf(caller);
                if (!seenInTree.add(caller)) {
                    f.node.addChild(buildCallerNode(index, caller, loc, true));
                    continue;
                }
                CallNode child = buildCallerNode(index, caller, loc, false);
                f.node.addChild(child);
                // Add `caller` to its descendants' ancestors so a
                // reachable back-edge through `caller` becomes a
                // cycle marker, not a second subtree.
                Set<MethodKey> childAncestors = new HashSet<>(f.ancestors.size() + 1);
                childAncestors.add(caller);
                childAncestors.addAll(f.ancestors);
                // Deeper frames ask "who calls this method?", so their
                // receiver context is the caller's own declaring class.
                queue.addLast(new PathFrame(caller, child, childAncestors, f.depth + 1, false,
                        caller.declaringClass));
                nodes++;
                if (nodes >= maxNodes) {
                    root.markNodeCapTruncated();
                    return new Result(root, true,
                            "Truncated at " + maxNodes + " nodes to prevent runaway expansion. "
                                    + "There may be a deeply-recursive or hot method in the chain.");
                }
            }
            if (hidden > 0) {
                f.node.addChild(CallNode.fanoutMarker(
                        f.key.declaringClass, f.key.methodName, f.key.arity, hidden));
            }
        }
        // Base the diagnostic on the tree's children, not seenInTree: a
        // pure self-recursion produces only a cycle marker (which is not
        // added to seenInTree), and reporting "No callers found" for it
        // would be wrong — the cycle IS the answer.
        if (root.callers.isEmpty()) {
            return new Result(root, true,
                    "No callers found for '" + displayTarget.declaringClass + "#"
                            + displayTarget.methodName + "/" + displayTarget.arity
                            + "' in this project's sources. Verify the FQN and method name; "
                            + "if the method is a library method, it may simply not be called here.");
        }
        return new Result(root, true, "OK; " + seenInTree.size() + " caller(s) in chain.");
    }

    private record PathFrame(MethodKey key, CallNode node, Set<MethodKey> ancestors,
                             int depth, boolean isRoot, String contextClass,
                             Set<MethodKey> localCallersSeen) {
        PathFrame(MethodKey key, CallNode node, Set<MethodKey> ancestors,
                  int depth, boolean isRoot, String contextClass) {
            this(key, node, ancestors, depth, isRoot, contextClass, new HashSet<>());
        }
    }

    /**
     * True if at least one recorded call site of a caller could hold an
     * instance of {@code contextClass} — i.e. some site's receiver type is
     * hierarchy-comparable with {@code contextClass} (issues #7 / #8).
     *
     * <p>The same caller can invoke the same callee from several
     * expressions with different receiver static types (a
     * {@code ChildA}-typed field and a {@code ChildB}-typed field, say).
     * One compatible expression is enough to count the caller, so the
     * per-site receiver type is OR-ed rather than required to be uniform.
     * A null receiver type (unknown) passes — see
     * {@link ProjectIndex#isComparable}.
     */
    private static boolean anySiteMatchesContext(ProjectIndex index, String contextClass,
                                                 List<ProjectIndex.CallSite> sites) {
        for (ProjectIndex.CallSite site : sites) {
            if (index.isComparable(contextClass, site.receiverType())) return true;
        }
        return false;
    }

    /**
     * True if {@code key} has any recorded caller across its
     * method-hierarchy group. Used to decide whether a frame sitting on
     * the {@code maxDepth} boundary should emit a depth marker: a leaf on
     * the boundary has nothing to truncate, so it must not be reported
     * as truncated.
     *
     * <p>Deliberately unfiltered by receiver static type — this is a
     * cheap "is there anything below us at all" probe, and a spurious
     * marker on a fully-filtered frame is far less misleading than
     * silently dropping the "chain continues" signal.
     */
    private static boolean hasAnyCaller(ProjectIndex index, MethodKey key) {
        for (MethodKey relatedKey : index.relatedMethods(key)) {
            if (!index.callersOfSet(relatedKey).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Builds a child node for {@code caller}: a full subtree node, or a
     * {@code dedupedMarker} when {@code deduped} is true (the method
     * already appears elsewhere in the tree). Both shapes carry the
     * method's declaration location, or {@code (null, 0)} if undeclared.
     */
    private static CallNode buildCallerNode(ProjectIndex index, MethodKey caller,
                                            ProjectIndex.SourceLoc loc, boolean deduped) {
        String file = loc == null ? null : loc.file();
        int line = loc == null ? 0 : loc.line();
        CallNode node = deduped
                ? CallNode.dedupedMarker(caller.declaringClass, caller.methodName, caller.arity, file, line)
                : new CallNode(caller.declaringClass, caller.methodName, caller.arity, file, line);
        // Issue #9: a caller chain that ends at a framework entry point
        // says so (spring-mvc / junit5 / main / ...) instead of looking
        // like an unexplored branch.
        node.markEntryPoint(index.entryPointKindOf(caller));
        return node;
    }

    /** Per-frame and per-depth caps remain static because their tests
     * already use real fixtures (a 1200-caller target for the fan-out
     * cap in {@link CallChainFanoutCapTest}). Only the whole-tree node
     * cap needs to be lowerable to exercise the truncation path. */


    /**
     * Safety cap on the number of direct callers expanded per BFS frame.
     * For methods on widely-implemented interfaces, {@code relatedMethods}
     * can return 100+ keys and each can have 1000+ callers, giving
     * 10^5-10^6 iterations per frame — multiply by MAX_NODES frames and
     * the search takes 10+ minutes. Capping at 500 keeps a single frame
     * bounded and the total BFS in seconds; the dropped callers are
     * reported via a {@code fanoutMarker} child carrying the count.
     */
    private static final int MAX_CALLERS_PER_FRAME = 500;
}
