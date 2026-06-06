package com.codescope;

import java.util.Objects;

/** Unique identity of a method declaration: declaring class + name + arity. */
public final class MethodKey {
    public final String declaringClass;
    public final String methodName;
    public final int arity;

    public MethodKey(String declaringClass, String methodName, int arity) {
        this.declaringClass = Objects.requireNonNull(declaringClass);
        this.methodName = Objects.requireNonNull(methodName);
        this.arity = arity;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MethodKey k)) return false;
        return arity == k.arity
                && declaringClass.equals(k.declaringClass)
                && methodName.equals(k.methodName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(declaringClass, methodName, arity);
    }

    @Override
    public String toString() {
        return declaringClass + "#" + methodName + "/" + arity;
    }
}
