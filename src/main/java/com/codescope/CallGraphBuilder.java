package com.codescope;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Interface for building call graphs from Java source.
 */
public interface CallGraphBuilder {

    /**
     * Returns call sites for a method (callees of the method).
     */
    Set<CallGraph.CallSite> getCallees(Path file, String methodName);

    /**
     * Returns callers of a method.
     */
    Set<CallGraph.CallSite> getCallers(Path file, String methodName);

    /**
     * Returns callers by method name only (without class qualifier).
     */
    Set<CallGraph.CallSite> getCallersByName(String methodName);

    /**
     * Returns all callers for a given method (qualified or simple name).
     */
    Set<CallGraph.CallSite> getCallersForMethod(String className, String methodName);

    /**
     * Returns all call sites (method -> called methods).
     */
    Map<String, Set<CallGraph.CallSite>> getCallSites();

    /**
     * Returns all callers (reversed: called method -> callers).
     */
    Map<String, Set<CallGraph.CallSite>> getAllCallers();

    /**
     * Returns all methods in the file with their call sites.
     */
    Map<String, Set<CallGraph.CallSite>> getAllMethods(Path file);
}
