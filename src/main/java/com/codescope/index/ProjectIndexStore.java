package com.codescope.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists a built {@link ProjectIndex} to disk and loads it back (issue #9
 * follow-up: the CLI is a one-shot process, so without a cache every
 * invocation paid the full Maven + JDT build cost).
 *
 * <p>Uses Java serialization: every index structure ({@link ProjectIndex},
 * the {@link MethodKey} keys, and the record values) is {@link
 * java.io.Serializable}, so no bespoke format is needed. The cache is keyed
 * by {@link IndexFingerprint}, which covers the source files, the mapper
 * XML, and the pom contents — a miss simply rebuilds.
 */
public final class ProjectIndexStore {

    private ProjectIndexStore() {}

    /**
     * Writes {@code index} to {@code file}. The parent directory is created
     * if needed. Best-effort: a failure to write leaves the build usable
     * (the in-memory index is still returned).
     */
    public static void save(ProjectIndex index, Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (ObjectOutputStream out = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeObject(index);
        }
    }

    /**
     * Reads an index previously written by {@link #save}. Returns null when
     * the file is missing or cannot be read/parsed — the caller rebuilds.
     */
    public static ProjectIndex load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try (ObjectInputStream in = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            Object o = in.readObject();
            return o instanceof ProjectIndex index ? index : null;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
