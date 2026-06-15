package com.codescope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reverse call index for a project. Built once by {@link JdtIndexer}, then
 * queried for chains via {@link CallChainAnalyzer}.
 *
 *   calls[target] = set of methods that invoke target (insertion-ordered, deduped)
 *   declarations[method] = source location of the method definition
 *
 * <p>Thread-safe: writes from the indexer's parallel parse pass must be safe
 * to issue concurrently. The outer maps are {@link ConcurrentHashMap}; the
 * per-target caller set is a {@link LinkedHashSet} guarded by synchronizing
 * on the set itself (since {@code LinkedHashSet} is not thread-safe, but is
 * much cheaper than {@code ArrayList.contains} for dedup on hot methods).
 */
public final class ProjectIndex {
    public record SourceLoc(String file, int line) {}

    /**
     * A single declared symbol — a type (class/interface/enum/record/annotation),
     * a method, a constructor, or a field. Carries enough metadata for
     * {@code find_symbols} to render a useful search hit without re-walking
     * the AST: the simple name (search key), the FQN (stable identifier),
     * the kind (for filtering), and the source location.
     *
     * <p>For top-level/nested types, {@code fqn} is the type FQN
     * (e.g. {@code com.example.UserRepository}) and {@code container} is
     * the enclosing-type FQN for nested types, or null for top-level
     * types. For methods/constructors, {@code fqn} is
     * {@code Container#name/arity} and {@code container} is the declaring
     * class FQN. For fields, {@code fqn} is {@code Container.fieldName}
     * and {@code container} is the declaring class FQN. {@code signature}
     * carries the method/constructor full parameter-type list, or null
     * for types and fields.
     */
    public record Symbol(
            String name,
            String kind,
            String fqn,
            String container,
            String file,
            int line,
            String signature) {}

    private final Map<MethodKey, Set<MethodKey>> calls = new ConcurrentHashMap<>();
    private final Map<MethodKey, SourceLoc> declarations = new ConcurrentHashMap<>();
    // Method-hierarchy index: for each MethodKey M, the set of other
    // MethodKeys in M's hierarchy group (the methods M overrides in a
    // supertype, plus the methods that implement M in subtypes). Both
    // directions are recorded bidirectionally by recordHierarchy.
    // Populated eagerly by JdtIndexer during visit(MethodDeclaration) —
    // see recordMethodHierarchy there. Used by CallChainAnalyzer to
    // cross interface boundaries during BFS, so a caller that statically
    // references an interface method is reachable when the BFS reaches
    // a concrete implementation (or vice versa).
    private final Map<MethodKey, Set<MethodKey>> hierarchy = new ConcurrentHashMap<>();
    private final List<String> skippedFiles = Collections.synchronizedList(new ArrayList<>());
    // Parallel to `calls` but per (callee, caller) edge carries a list of
    // call-site positions. Populated eagerly at index time so that
    // find_call_sites can answer "on which line" without re-walking the AST.
    // Outer key: callee MethodKey. Inner key: caller MethodKey. Value:
    // ordered list of SourceLoc (the call expression's source position).
    // Same thread-safety pattern as `calls` (ConcurrentHashMap outer,
    // synchronized inner).
    private final Map<MethodKey, Map<MethodKey, List<SourceLoc>>> callSites
            = new ConcurrentHashMap<>();
    // Flat symbol table populated by JdtIndexer. Outer key: lowercase simple
    // name (for case-insensitive lookup); inner: ordered list of matching
    // symbols (a single name can match many types/methods/fields). Each
    // Symbol carries its own FQN + kind, so a search result row is one
    // Symbol object. Same thread-safety pattern as `calls` /
    // `callSites`. We index by lowercase simple name because find_symbols
    // is a substring-on-name search; substring matching still scans the
    // entry set, so the lowercase key only saves us a toLowerCase per
    // comparison.
    private final Map<String, List<Symbol>> symbols = new ConcurrentHashMap<>();

    /** Records a source file that the indexer could not parse, for diagnostic reporting. */
    public void recordSkippedFile(String path, String reason) {
        skippedFiles.add(path + ": " + reason);
    }

    /** Source files the indexer could not parse (e.g. read errors, syntax errors). */
    public List<String> skippedFiles() {
        synchronized (skippedFiles) {
            return List.copyOf(skippedFiles);
        }
    }

    /**
     * Records that {@code caller} invokes {@code callee}, i.e. the
     * call-edge {@code caller -> callee}. Argument order matches the
     * direction of the call (caller first, callee second) so the call
     * site reads the same as the JDT visit: "this method calls that
     * method".
     */
    public void recordInvocation(MethodKey caller, MethodKey callee) {
        // Dedupe: the same (caller, callee) pair can come from a hot method
        // being called from many sites in the same caller body. LinkedHashSet
        // gives O(1) add/contains while preserving insertion order.
        Set<MethodKey> set = calls.computeIfAbsent(callee, k -> new LinkedHashSet<>());
        synchronized (set) {
            set.add(caller);
        }
    }

    /**
     * Records that {@code caller} invokes {@code callee} at source location
     * {@code loc}. Multiple calls to this method for the same
     * (caller, callee) pair append — they are NOT deduplicated, because
     * two call expressions at different lines are distinct facts (a caller
     * can invoke the same callee from many sites, and the user wants all
     * of them).
     */
    public void recordCallSite(MethodKey caller, MethodKey callee, SourceLoc loc) {
        Map<MethodKey, List<SourceLoc>> byCaller =
                callSites.computeIfAbsent(callee, k -> new ConcurrentHashMap<>());
        List<SourceLoc> sites = byCaller.computeIfAbsent(caller, k ->
                Collections.synchronizedList(new ArrayList<>()));
        synchronized (sites) {
            sites.add(loc);
        }
    }

    public void putDeclaration(MethodKey method, SourceLoc loc) {
        declarations.putIfAbsent(method, loc);
    }

    /**
     * Records that {@code child} and {@code parent} belong to the same
     * method-hierarchy group — i.e. they have the same name + arity and
     * are linked by an implements / extends relationship through their
     * declaring types. Stored bidirectionally: callers of either can find
     * the other via {@link #relatedMethods}.
     *
     * <p>Self-pairs are silently skipped. Both keys must already be
     * declared in {@link #declarations} (the indexer records hierarchy
     * from {@code visit(MethodDeclaration)}, which also calls
     * {@code putDeclaration} — so this is naturally true for project
     * methods; library methods recorded via {@code findInvokedKeys} never
     * get hierarchy entries because they are not declared in project
     * sources).
     */
    public void recordHierarchy(MethodKey child, MethodKey parent) {
        if (child == null || parent == null || child.equals(parent)) return;
        hierarchy.computeIfAbsent(child, k -> ConcurrentHashMap.newKeySet()).add(parent);
        hierarchy.computeIfAbsent(parent, k -> ConcurrentHashMap.newKeySet()).add(child);
    }

    /**
     * Returns the set of MethodKeys that share {@code m}'s method-hierarchy
     * group, including {@code m} itself. Iteration order is sorted by
     * {@link MethodKey#toString()} for deterministic BFS expansion.
     *
     * <p>Returns a singleton {@code Set.of(m)} if {@code m} has no
     * hierarchy entries (the common case for plain class methods that
     * don't override anything).
     */
    public Set<MethodKey> relatedMethods(MethodKey m) {
        if (m == null) return Set.of();
        Set<MethodKey> group = hierarchy.get(m);
        if (group == null || group.isEmpty()) {
            return Set.of(m);
        }
        java.util.TreeSet<MethodKey> out = new java.util.TreeSet<>(
                (a, b) -> a.toString().compareTo(b.toString()));
        out.add(m);
        out.addAll(group);
        return out;
    }

    /**
     * Records one symbol (type, method, constructor, or field) in the
     * symbol table, keyed by its lowercase simple name. Duplicate
     * (name, kind, fqn) entries are coalesced — the indexer may visit the
     * same declaration more than once in pathological cases (e.g. an
     * ImplicitTypeDeclaration wrapping a top-level record in JDT 3.45)
     * and we don't want a noisy result. Different FQNs under the same
     * simple name (a common case, e.g. {@code equals} in many classes)
     * are kept distinct.
     */
    public void recordSymbol(Symbol s) {
        if (s == null || s.name == null || s.name.isEmpty()) return;
        List<Symbol> bucket = symbols.computeIfAbsent(s.name.toLowerCase(),
                k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (bucket) {
            for (Symbol existing : bucket) {
                if (existing.kind.equals(s.kind)
                        && existing.fqn.equals(s.fqn)
                        && java.util.Objects.equals(existing.signature, s.signature)) {
                    return;  // duplicate, skip
                }
            }
            bucket.add(s);
        }
    }

    /**
     * Result of a {@link #searchSymbols} call: the sorted, capped list of
     * matches plus the total number of matches that existed before the
     * cap was applied. {@code matches.size()} may be less than
     * {@code totalCount} when the caller passed a finite {@code limit}
     * — callers use {@code totalCount > matches.size()} as the
     * "capped/truncated" signal without needing a second pass.
     */
    public record SymbolSearchResult(List<Symbol> matches, int totalCount) {}

    /**
     * Returns every recorded symbol whose simple name contains
     * {@code query} (case-insensitive substring match), optionally
     * filtered to a single kind. Results are sorted by (fqn, signature)
     * for stable output. {@code limit} caps the number of returned
     * symbols; pass {@link Integer#MAX_VALUE} for "no cap" (caller's
     * responsibility). The returned {@link SymbolSearchResult#totalCount}
     * is the match count *before* the cap is applied, so a caller that
     * needs to know whether the result was truncated can do so without a
     * second scan over the index.
     *
     * <p>Sort cost is bounded: only the kept prefix (up to {@code limit})
     * is fully sorted. Excess matches beyond {@code limit} are counted
     * but not ordered, since they would be discarded anyway. This keeps
     * the worst-case complexity at {@code O(N log L)} where L is the
     * limit, not {@code O(N log N)}.
     */
    public SymbolSearchResult searchSymbols(String query, String kindFilter, int limit) {
        if (query == null || query.isEmpty()) {
            return new SymbolSearchResult(List.of(), 0);
        }
        String needle = query.toLowerCase();
        List<Symbol> hits = new ArrayList<>();
        int total = 0;
        for (List<Symbol> bucket : symbols.values()) {
            List<Symbol> snap;
            synchronized (bucket) {
                snap = List.copyOf(bucket);
            }
            for (Symbol s : snap) {
                if (kindFilter != null && !kindFilter.equals(s.kind)) continue;
                if (s.name.toLowerCase().contains(needle)) {
                    total++;
                    if (hits.size() < limit) hits.add(s);
                }
            }
        }
        if (hits.size() > 1) {
            hits.sort((a, b) -> {
                int byFqn = a.fqn.compareTo(b.fqn);
                if (byFqn != 0) return byFqn;
                String sa = a.signature == null ? "" : a.signature;
                String sb = b.signature == null ? "" : b.signature;
                return sa.compareTo(sb);
            });
        }
        return new SymbolSearchResult(hits, total);
    }

    public List<MethodKey> callersOf(MethodKey target) {
        Set<MethodKey> set = calls.get(target);
        if (set == null) return Collections.emptyList();
        synchronized (set) {
            return List.copyOf(set);
        }
    }

    /**
     * Returns a defensive snapshot of call sites for {@code target}, keyed
     * by the caller MethodKey. Each value is the ordered list of call
     * sites within that caller's body (one entry per AST node that
     * resolves to {@code target}). Returns an empty map if {@code target}
     * has no recorded call sites.
     */
    public Map<MethodKey, List<SourceLoc>> callSitesOf(MethodKey target) {
        Map<MethodKey, List<SourceLoc>> m = callSites.get(target);
        if (m == null) return Collections.emptyMap();
        Map<MethodKey, List<SourceLoc>> snap = new java.util.LinkedHashMap<>(m.size());
        for (Map.Entry<MethodKey, List<SourceLoc>> e : m.entrySet()) {
            synchronized (e.getValue()) {
                snap.put(e.getKey(), List.copyOf(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(snap);
    }

    public SourceLoc declarationOf(MethodKey method) {
        return declarations.get(method);
    }

    public Set<MethodKey> knownMethods() {
        return Collections.unmodifiableSet(declarations.keySet());
    }

    /**
     * Returns an immutable snapshot of the reverse call index. Each inner
     * set is a defensive copy: callers can iterate without seeing concurrent
     * mutations to {@link #recordInvocation} updates, and they cannot
     * accidentally reach into a still-live {@link LinkedHashSet} and
     * corrupt the indexer's data structure.
     */
    public Map<MethodKey, Set<MethodKey>> allCalls() {
        Map<MethodKey, Set<MethodKey>> snap = new java.util.LinkedHashMap<>(calls.size());
        for (Map.Entry<MethodKey, Set<MethodKey>> e : calls.entrySet()) {
            synchronized (e.getValue()) {
                snap.put(e.getKey(), Set.copyOf(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(snap);
    }

    /**
     * Resolve (class, name) to a declared method, with optional arity and parameter types
     * for overload disambiguation.
     *
     * @return the unique matching method, or null if none
     * @throws AmbiguousMethodException if more than one method matches the selector
     */
    public MethodKey resolveTarget(String className, String methodName) throws AmbiguousMethodException {
        return resolveTarget(className, methodName, null, null);
    }

    public MethodKey resolveTarget(String className, String methodName, Integer arity)
            throws AmbiguousMethodException {
        return resolveTarget(className, methodName, arity, null);
    }

    public MethodKey resolveTarget(String className, String methodName,
                                   Integer arity, List<String> paramTypes)
            throws AmbiguousMethodException {
        MethodKey match = null;
        for (MethodKey m : declarations.keySet()) {
            if (!m.declaringClass.equals(className) || !m.methodName.equals(methodName)) continue;
            if (arity != null && m.arity != arity.intValue()) continue;
            if (paramTypes != null && !paramTypes.equals(m.parameterTypes)) continue;
            if (match != null) {
                throw new AmbiguousMethodException(className, methodName, arity, paramTypes);
            }
            match = m;
        }
        return match;
    }

    /** Lists every declared method with the given name, regardless of arity. */
    public List<MethodKey> findOverloads(String className, String methodName) {
        List<MethodKey> out = new ArrayList<>();
        for (MethodKey m : declarations.keySet()) {
            if (m.declaringClass.equals(className) && m.methodName.equals(methodName)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Find every method KEY in the call-edge map (i.e. methods that this
     * project's sources invoke) matching {@code className} and
     * {@code methodName}, optionally narrowed by {@code arity} and
     * {@code paramTypes}. Used to resolve library-method targets: those
     * methods have no project declaration, so {@link #resolveTarget} sees
     * nothing, but {@link JdtIndexer} still recorded the call edges with
     * the exact signature from the JDT binding (e.g.
     * {@code java.io.PrintStream#println/1(java.lang.String)}). The
     * caller can then union {@link #callersOf} across the returned keys.
     *
     * <p>Returns an empty list if nothing matches. The order is
     * undefined.
     */
    public List<MethodKey> findInvokedKeys(String className, String methodName,
                                           Integer arity, List<String> paramTypes) {
        List<MethodKey> out = new ArrayList<>();
        for (MethodKey k : calls.keySet()) {
            if (!k.declaringClass.equals(className) || !k.methodName.equals(methodName)) continue;
            if (arity != null && k.arity != arity.intValue()) continue;
            if (paramTypes != null && !paramTypes.equals(k.parameterTypes)) continue;
            out.add(k);
        }
        return out;
    }

    public static final class AmbiguousMethodException extends Exception {
        public AmbiguousMethodException(String className, String methodName,
                                        Integer arity, List<String> paramTypes) {
            super(buildMessage(className, methodName, arity, paramTypes));
        }
        private static String buildMessage(String className, String methodName,
                                           Integer arity, List<String> paramTypes) {
            if (paramTypes != null) {
                return "Multiple methods named '" + methodName + "' with parameter types "
                        + paramTypes + " in " + className;
            }
            if (arity != null) {
                return "Multiple methods named '" + methodName + "' with arity " + arity
                        + " in " + className + "; pass `paramTypes` to disambiguate.";
            }
            return "Multiple methods named '" + methodName + "' in " + className
                    + "; pass `arity` (and optionally `paramTypes`) to disambiguate.";
        }
    }
}
