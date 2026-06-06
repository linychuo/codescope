package com.codescope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reverse call index for a project. Built once by {@link JdtIndexer}, then
 * queried for chains via {@link CallChainAnalyzer}.
 *
 *   calls[target] = list of methods that invoke target
 *   declarations[method] = source location of the method definition
 */
public final class ProjectIndex {
    public record SourceLoc(String file, int line) {}

    private final Map<MethodKey, List<MethodKey>> calls = new HashMap<>();
    private final Map<MethodKey, SourceLoc> declarations = new HashMap<>();

    public void addCall(MethodKey target, MethodKey caller) {
        calls.computeIfAbsent(target, k -> new ArrayList<>()).add(caller);
    }

    public void putDeclaration(MethodKey method, SourceLoc loc) {
        declarations.putIfAbsent(method, loc);
    }

    public List<MethodKey> callersOf(MethodKey target) {
        return calls.getOrDefault(target, Collections.emptyList());
    }

    public SourceLoc declarationOf(MethodKey method) {
        return declarations.get(method);
    }

    public Set<MethodKey> knownMethods() {
        return Collections.unmodifiableSet(declarations.keySet());
    }

    public Map<MethodKey, List<MethodKey>> allCalls() {
        return Collections.unmodifiableMap(calls);
    }

    /** Pick the best-matching declared method for (class, name), preferring exact arity. */
    public MethodKey resolveTarget(String className, String methodName) {
        MethodKey fallback = null;
        for (MethodKey m : declarations.keySet()) {
            if (m.declaringClass.equals(className) && m.methodName.equals(methodName)) {
                return m;          // first match wins
            }
        }
        return null;
    }
}
