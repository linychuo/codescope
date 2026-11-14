package com.codescope.analysis;

import com.codescope.index.ProjectIndex;
import com.codescope.model.MethodKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Downward companion to {@link CallChainAnalyzer}: given a target method,
 * builds a tree of the methods it invokes, transitively (issue #9 item 2 —
 * the tool used to answer only "who calls this?", never "what does this
 * call?").
 *
 * <p>The forward edges come from {@link ProjectIndex#invokesOf(MethodKey)},
 * maintained write-through by {@code recordInvocation} alongside the
 * reverse index. Expansion mirrors {@code trace_callers}: a frame's
 * callee set is the union of {@code invokesOf} over the frame key's
 * {@link ProjectIndex#relatedMethods(MethodKey)} group, so an interface
 * method's tree includes the bodies of its implementations and a class
 * method's tree includes its overrides.
 *
 * <p>No receiver-static-type filter is applied in this direction: the
 * question is "does this call site invoke the target's dispatch family?",
 * which is answered by the resolved callee key alone. (The receiver filter
 * exists to keep a caller from being attributed to a target it cannot
 * dispatch to; a callee is by definition the method the call resolves to.)
 */
public final class CalleeChainAnalyzer {

    public record Result(CallNode root, boolean found, String message) {}

    /** Same default node budget as {@link CallChainAnalyzer}. */
    public static final int DEFAULT_MAX_NODES = CallChainAnalyzer.DEFAULT_MAX_NODES;

    /** Same default depth budget as {@link CallChainAnalyzer}. */
    public static final int DEFAULT_MAX_DEPTH = CallChainAnalyzer.DEFAULT_MAX_DEPTH;

    /**
     * Safety cap on direct callees expanded per BFS frame — the downward
     * mirror of {@code CallChainAnalyzer.MAX_CALLERS_PER_FRAME}. A method
     * with a huge body (or a framework method on a widely-implemented
     * interface) would otherwise fan out without bound.
     */
    static final int MAX_CALLEES_PER_FRAME = 500;

    private final int maxNodes;
    private final int maxDepth;

    /** Production constructor — uses {@link #DEFAULT_MAX_NODES} / {@link #DEFAULT_MAX_DEPTH}. */
    public CalleeChainAnalyzer() {
        this(DEFAULT_MAX_NODES, DEFAULT_MAX_DEPTH);
    }

    CalleeChainAnalyzer(int maxNodes) {
        this(maxNodes, DEFAULT_MAX_DEPTH);
    }

    /**
     * Full-control constructor: {@code maxNodes} bounds the whole tree,
     * {@code maxDepth} bounds how many callee levels are expanded
     * (0 = the target only, 1 = direct callees, ...).
     */
    public CalleeChainAnalyzer(int maxNodes, int maxDepth) {
        this.maxNodes = maxNodes;
        this.maxDepth = maxDepth;
    }

    /**
     * BFS over the forward call index starting from {@code displayTarget}.
     * {@code seeds} are the recorded call-edge keys the user's selector
     * resolved to (library overloads); when empty, the display key itself
     * is the only seed.
     *
     * <p>Each path carries its own ancestor set so a true back-edge is a
     * cycle marker while a diamond (a callee reached via two paths) is a
     * real node on both. Same contract as {@link CallChainAnalyzer}.
     */
    public Result traceCallees(ProjectIndex index, MethodKey displayTarget, List<MethodKey> seeds) {
        return traceCallees(index, displayTarget, seeds, displayTarget.declaringClass);
    }

    /**
     * As {@link #traceCallees(ProjectIndex, MethodKey, List)}, with an
     * explicit <em>query class</em> used as the context for the root
     * frame's hierarchy expansion (issue #9 follow-up, the callee-side
     * counterpart of #8).
     *
     * <p>A family member other than the frame key contributes its callees
     * only when its declaring class is a subtype of the context: the
     * implementations/overrides in the queried class's subtree. A sibling
     * override of an inherited method must not leak in.
     */
    public Result traceCallees(ProjectIndex index, MethodKey displayTarget, List<MethodKey> seeds,
                               String queryClass) {
        List<MethodKey> effectiveSeeds = seeds.isEmpty() ? List.of(displayTarget) : seeds;
        ProjectIndex.SourceLoc rootLoc = index.declarationOf(displayTarget);
        CallNode root = new CallNode(
                displayTarget.declaringClass, displayTarget.methodName, displayTarget.arity,
                rootLoc != null ? rootLoc.file() : null,
                rootLoc != null ? rootLoc.line() : 0);
        root.markEntryPoint(index.entryPointKindOf(displayTarget));
        root.markTransactional(index.isTransactional(displayTarget));

        String rootContext = queryClass != null ? queryClass : displayTarget.declaringClass;
        Set<MethodKey> seedAncestors = new HashSet<>(effectiveSeeds);
        Deque<PathFrame> queue = new ArrayDeque<>();
        Set<MethodKey> directCalleesSeen = new HashSet<>();
        Set<MethodKey> rootCollected = new LinkedHashSet<>();
        for (MethodKey seed : effectiveSeeds) {
            queue.addLast(new PathFrame(seed, root, seedAncestors, 0, true, rootContext));
        }

        int nodes = 1;
        Set<MethodKey> seenInTree = new HashSet<>();
        // MyBatis mapper statements are leaves without a MethodKey, so they
        // are counted separately for the summary message.
        int[] sqlLeaves = {0};
        while (!queue.isEmpty()) {
            PathFrame f = queue.removeFirst();
            if (f.depth >= maxDepth) {
                if (hasAnyCallee(index, f.key, f.contextClass)) {
                    f.node.addChild(CallNode.depthMarker(
                            f.key.declaringClass, f.key.methodName, f.key.arity));
                }
                continue;
            }
            // Union of the callees over the frame key's hierarchy group, in
            // a stable order (relatedMethods returns a sorted set). Only the
            // subtree implementations of the context class contribute: a
            // sibling override of an inherited method is not part of the
            // queried class's family.
            Set<MethodKey> frameCallees = new LinkedHashSet<>();
            for (MethodKey relatedKey : index.relatedMethods(f.key)) {
                if (!relatedKey.equals(f.key)
                        && !index.isSubtypeOf(relatedKey.declaringClass, f.contextClass)) {
                    continue;
                }
                frameCallees.addAll(index.invokesOf(relatedKey));
            }
            Set<MethodKey> collected = f.isRoot ? rootCollected : new LinkedHashSet<>();
            int hidden = 0;
            for (MethodKey callee : frameCallees) {
                if (f.ancestors.contains(callee)) {
                    if (f.localCalleesSeen.add(callee)) {
                        f.node.addChild(CallNode.cycleMarker(
                                callee.declaringClass, callee.methodName, callee.arity));
                    }
                    continue;
                }
                if (collected.size() >= MAX_CALLEES_PER_FRAME) {
                    hidden++;
                    continue;
                }
                if (f.isRoot && !directCalleesSeen.add(callee)) continue;
                if (!f.localCalleesSeen.add(callee)) continue;
                if (!collected.add(callee)) continue;
                ProjectIndex.SourceLoc loc = index.declarationOf(callee);
                if (!seenInTree.add(callee)) {
                    f.node.addChild(buildCalleeNode(index, callee, loc, true));
                    continue;
                }
                CallNode child = buildCalleeNode(index, callee, loc, false);
                f.node.addChild(child);
                Set<MethodKey> childAncestors = new HashSet<>(f.ancestors.size() + 1);
                childAncestors.add(callee);
                childAncestors.addAll(f.ancestors);
                // Deeper frames ask "what does this method call?", so their
                // context is the callee's own declaring class.
                queue.addLast(new PathFrame(callee, child, childAncestors, f.depth + 1, false,
                        callee.declaringClass));
                nodes++;
                if (nodes >= maxNodes) {
                    root.markNodeCapTruncated();
                    return new Result(root, true,
                            "Truncated at " + maxNodes + " nodes to prevent runaway expansion. "
                                    + "There may be a deeply-recursive or hot method in the tree.");
                }
            }
            if (hidden > 0) {
                f.node.addChild(CallNode.fanoutMarker(
                        f.key.declaringClass, f.key.methodName, f.key.arity, hidden));
            }
            // Issue #9 item 4: a MyBatis mapper interface method's
            // implementation is its XML statement(s). Attach them as leaves
            // so `trace_callees` answers "what does this mapper method
            // actually execute?".
            for (ProjectIndex.MapperStatement st
                    : index.mapperStatementsFor(f.key.declaringClass, f.key.methodName)) {
                CallNode sqlNode = new CallNode(
                        st.namespace(), st.id(), 0, st.file(), st.line());
                sqlNode.markSql(st.type(), st.sql());
                f.node.addChild(sqlNode);
                sqlLeaves[0]++;
            }
        }
        if (root.callers.isEmpty()) {
            return new Result(root, true,
                    "No callees found for '" + displayTarget.declaringClass + "#"
                            + displayTarget.methodName + "/" + displayTarget.arity
                            + "' in this project's sources. Verify the FQN and method name; "
                            + "if the method is a library method its body is not in this "
                            + "project, so there is nothing below it here.");
        }
        return new Result(root, true, "OK; " + (seenInTree.size() + sqlLeaves[0])
                + " callee(s) in tree.");
    }

    private static CallNode buildCalleeNode(ProjectIndex index, MethodKey callee,
                                            ProjectIndex.SourceLoc loc, boolean deduped) {
        String file = loc == null ? null : loc.file();
        int line = loc == null ? 0 : loc.line();
        CallNode node = deduped
                ? CallNode.dedupedMarker(callee.declaringClass, callee.methodName, callee.arity, file, line)
                : new CallNode(callee.declaringClass, callee.methodName, callee.arity, file, line);
        node.markEntryPoint(index.entryPointKindOf(callee));
        node.markTransactional(index.isTransactional(callee));
        return node;
    }

    /**
     * True if {@code key} has any recorded callee across the part of its
     * hierarchy group that belongs to {@code contextClass}'s subtree —
     * used to decide whether a frame on the {@code maxDepth} boundary
     * should emit a depth marker (a leaf on the boundary has nothing to
     * truncate).
     */
    private static boolean hasAnyCallee(ProjectIndex index, MethodKey key,
                                       String contextClass) {
        for (MethodKey relatedKey : index.relatedMethods(key)) {
            if (!relatedKey.equals(key)
                    && !index.isSubtypeOf(relatedKey.declaringClass, contextClass)) {
                continue;
            }
            if (!index.invokesOf(relatedKey).isEmpty()) return true;
        }
        return false;
    }

    private record PathFrame(MethodKey key, CallNode node, Set<MethodKey> ancestors,
                             int depth, boolean isRoot, String contextClass,
                             Set<MethodKey> localCalleesSeen) {
        PathFrame(MethodKey key, CallNode node, Set<MethodKey> ancestors,
                  int depth, boolean isRoot, String contextClass) {
            this(key, node, ancestors, depth, isRoot, contextClass, new HashSet<>());
        }
    }
}
