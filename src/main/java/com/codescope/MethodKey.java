package com.codescope;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Unique identity of a method declaration: declaring class + name + parameter types. */
public final class MethodKey {
    public final String declaringClass;
    public final String methodName;
    public final int arity;
    public final List<String> parameterTypes;   // FQNs, e.g. ["int", "java.lang.String"]

    public MethodKey(String declaringClass, String methodName, int arity, List<String> parameterTypes) {
        this.declaringClass = Objects.requireNonNull(declaringClass);
        this.methodName = Objects.requireNonNull(methodName);
        this.arity = arity;
        this.parameterTypes = parameterTypes == null
                ? List.of()
                : Collections.unmodifiableList(parameterTypes);
    }

    /** Convenience for callers that don't care about parameter types. */
    public MethodKey(String declaringClass, String methodName, int arity) {
        this(declaringClass, methodName, arity, null);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MethodKey k)) return false;
        return arity == k.arity
                && declaringClass.equals(k.declaringClass)
                && methodName.equals(k.methodName)
                && parameterTypes.equals(k.parameterTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(declaringClass, methodName, arity, parameterTypes);
    }

    /** Short signature for tool output: pkg.Cls#method/arity */
    public String shortSignature() {
        return declaringClass + "#" + methodName + "/" + arity;
    }

    /** Full signature including parameter types: pkg.Cls#method/arity(int,java.lang.String) */
    public String fullSignature() {
        if (parameterTypes.isEmpty()) return shortSignature();
        return shortSignature() + "(" + parameterTypes.stream().collect(Collectors.joining(",")) + ")";
    }

    @Override
    public String toString() {
        return fullSignature();
    }
}
