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
