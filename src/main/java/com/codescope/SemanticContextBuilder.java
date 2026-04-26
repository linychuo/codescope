package com.codescope;

import java.nio.file.Path;

/**
 * Interface for building semantic context for Java code.
 */
public interface SemanticContextBuilder {

    /**
     * Builds semantic context for a file (full class context).
     */
    String buildContext(Path file);

    /**
     * Builds semantic context for a specific method in a file.
     * @param file source file
     * @param methodNameOrLine method name or line number
     */
    String buildContext(Path file, String methodNameOrLine);

    /**
     * Builds call graph DOT output.
     */
    String buildDot();

    /**
     * Builds call graph DOT with options.
     */
    String buildDot(boolean noJdk, boolean cycles, boolean heatmap);
}
