package com.codescope.index;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A content-and-mtime fingerprint of everything the index depends on:
 * every {@code .java} and {@code .xml} source under the project root, the
 * {@code pom.xml} contents, and the {@code includeTests} flag (issue #9
 * follow-up).
 *
 * <p>Used as the disk-cache key for {@link ProjectIndexStore}: a hit means
 * the serialized index is still valid, a miss rebuilds. Build output
 * ({@code target/}), VCS and other hidden directories are excluded, so
 * generated files do not churn the fingerprint.
 */
final class IndexFingerprint {

    /**
     * Bump when the serialized index layout changes, so caches written by
     * an older build are invalidated instead of failing to deserialize.
     */
    private static final String VERSION = "codescope-index-v1";

    private static final int MAX_DEPTH = 12;

    private IndexFingerprint() {}

    /**
     * @return the hex fingerprint, or null if it could not be computed
     *         (a null key disables the cache for that call — building is
     *         always the safe fallback).
     */
    static String compute(Path projectRoot, boolean includeTests) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(VERSION.getBytes(StandardCharsets.UTF_8));
            md.update(Boolean.toString(includeTests).getBytes(StandardCharsets.UTF_8));

            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(projectRoot, MAX_DEPTH)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            files.sort(Comparator.comparing(Path::toString));

            // Sources and mapper XML: path + mtime + size is enough to
            // detect edits without hashing every file's content.
            for (Path p : files) {
                String rel = projectRoot.relativize(p).toString();
                if (isExcluded(rel)) continue;
                String name = p.getFileName().toString();
                if (name.endsWith(".java") || name.endsWith(".xml")) {
                    md.update(rel.getBytes(StandardCharsets.UTF_8));
                    md.update(Long.toString(Files.getLastModifiedTime(p).toMillis())
                            .getBytes(StandardCharsets.UTF_8));
                    md.update(Long.toString(Files.size(p)).getBytes(StandardCharsets.UTF_8));
                }
            }
            // Pom contents: a dependency change does not always move the
            // mtime in a way that matters (and the classpath is the
            // expensive part of a build), so hash the bytes.
            for (Path p : files) {
                if (p.getFileName().toString().equals("pom.xml")) {
                    md.update(Files.readAllBytes(p));
                }
            }

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Mirrors the mapper XML scan's exclusions (build output, hidden dirs). */
    private static boolean isExcluded(String relativePath) {
        Path prev = null;
        for (Path part : Paths.get(relativePath)) {
            String name = part.toString();
            if (name.startsWith(".") || name.equals("target") || name.equals("node_modules")) {
                return true;
            }
            prev = part;
        }
        return false;
    }

    /** The cache directory, overridable for tests. */
    static Path cacheDir() {
        String override = System.getProperty("codescope.cache.dir");
        if (override != null && !override.isBlank()) return Paths.get(override);
        String home = System.getProperty("user.home");
        return Paths.get(home == null ? "." : home, ".cache", "codescope");
    }
}
