package com.codescope;

import java.nio.file.Path;
import java.util.Set;
import java.util.List;

/**
 * Interface for discovering and indexing Java source files.
 */
public interface SourceIndexer {

    /**
     * Returns all Java source files under the root.
     */
    Set<Path> getFiles();

    /**
     * Returns all Java source roots (directories).
     */
    Set<Path> getJavaRoots();

    /**
     * Returns the root directory being indexed.
     */
    Path getRoot();

    /**
     * Returns all modules in this index.
     */
    List<ProjectModel> getModels();
}
