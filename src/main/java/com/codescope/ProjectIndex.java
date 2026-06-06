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

    private final Map<MethodKey, Set<MethodKey>> calls = new ConcurrentHashMap<>();
    private final Map<MethodKey, SourceLoc> declarations = new ConcurrentHashMap<>();
    private final List<String> skippedFiles = Collections.synchronizedList(new ArrayList<>());

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

    public void putDeclaration(MethodKey method, SourceLoc loc) {
        declarations.putIfAbsent(method, loc);
    }

    public List<MethodKey> callersOf(MethodKey target) {
        Set<MethodKey> set = calls.get(target);
        if (set == null) return Collections.emptyList();
        synchronized (set) {
            return List.copyOf(set);
        }
    }

    public SourceLoc declarationOf(MethodKey method) {
        return declarations.get(method);
    }

    public Set<MethodKey> knownMethods() {
        return Collections.unmodifiableSet(declarations.keySet());
    }

    public Map<MethodKey, Set<MethodKey>> allCalls() {
        return Collections.unmodifiableMap(calls);
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
