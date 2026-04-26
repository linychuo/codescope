package com.codescope;

import java.nio.file.Path;

/**
 * Deprecated alias for DefaultCallGraphBuilder.
 * @deprecated Use {@link DefaultCallGraphBuilder} instead.
 */
@Deprecated
public class CallGraph extends DefaultCallGraphBuilder {

    public CallGraph(Path file, ProjectModel model) {
        super(file, model);
    }
}
