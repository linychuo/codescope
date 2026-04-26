package com.codescope;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Interface for building call graphs from Java source.
 */
public interface CallGraphBuilder {

    /**
     * Represents a method call site with line number and resolved type.
     */
    class CallSite implements Comparable<CallSite> {
        public final String method;
        public final int line;
        public final String resolved;

        public CallSite(String method, int line, String resolved) {
            this.method = method;
            this.line = line;
            this.resolved = resolved;
        }

        public static CallSite create(String method, int startPosition, String resolved, org.eclipse.jdt.core.dom.CompilationUnit cu) {
            int line = 1;
            if (cu != null && startPosition > 0) {
                line = cu.getLineNumber(startPosition);
            }
            return new CallSite(method, line, resolved);
        }

        @Override
        public int compareTo(CallSite o) {
            int c = method.compareTo(o.method);
            return c != 0 ? c : Integer.compare(line, o.line);
        }

        @Override
        public String toString() {
            return method + (resolved.isEmpty() ? "" : " -> " + resolved) + " (line " + line + ")";
        }

        public String toFullString() {
            return resolved.isEmpty() ? method + " at line " + line : resolved + " at line " + line;
        }
    }

    /**
     * Returns call sites for a method (callees of the method).
     */
    Set<CallSite> getCallees(Path file, String methodName);

    /**
     * Returns callers of a method.
     */
    Set<CallSite> getCallers(Path file, String methodName);

    /**
     * Returns callers by method name only (without class qualifier).
     */
    Set<CallSite> getCallersByName(String methodName);

    /**
     * Returns all callers for a given method (qualified or simple name).
     */
    Set<CallSite> getCallersForMethod(String className, String methodName);

    /**
     * Returns all call sites (method -> called methods).
     */
    Map<String, Set<CallSite>> getCallSites();

    /**
     * Returns all callers (reversed: called method -> callers).
     */
    Map<String, Set<CallSite>> getAllCallers();

    /**
     * Returns all methods in the file with their call sites.
     */
    Map<String, Set<CallSite>> getAllMethods(Path file);
}
