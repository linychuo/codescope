package com.codescope.index;

import java.io.IOException;
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

            // Prune excluded directories during the walk rather than after:
            // a build-output tree (target/) can hold hundreds of thousands
            // of files that the index never reads.
            List<Path> files = new ArrayList<>();
            collectFiles(projectRoot, files);
            files.sort(Comparator.comparing(Path::toString));

            // Sources and mapper XML: path + mtime + size is enough to
            // detect edits without hashing every file's content.
            for (Path p : files) {
                String rel = projectRoot.relativize(p).toString();
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

            // The tool's own bytecode: a rebuild that changes index
            // semantics must invalidate the cache too, not just source
            // edits. Uses the code-source location (the shaded jar, or
            // target/classes under `mvn test`).
            try {
                java.net.URL location = IndexFingerprint.class.getProtectionDomain()
                        .getCodeSource().getLocation();
                Path codeSource = Paths.get(location.toURI());
                if (Files.isDirectory(codeSource)) {
                    // `mvn test` runs from target/classes: a directory's
                    // mtime only moves when entries are added/removed, so
                    // hash every class file's mtime instead.
                    try (Stream<Path> classes = Files.walk(codeSource)) {
                        for (Path c : classes.filter(Files::isRegularFile).sorted().toList()) {
                            md.update(Long.toString(Files.getLastModifiedTime(c).toMillis())
                                    .getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } else {
                    // Shaded jar: its own mtime and size.
                    md.update(Long.toString(Files.getLastModifiedTime(codeSource).toMillis())
                            .getBytes(StandardCharsets.UTF_8));
                    md.update(Long.toString(Files.size(codeSource)).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // No code source (exotic class loading): the rest of the
                // fingerprint still applies.
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

    /** Collects the regular files under {@code root}, pruning excluded trees. */
    private static void collectFiles(Path root, List<Path> out) {
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_DEPTH, new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(
                                Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (dir.equals(root)) return java.nio.file.FileVisitResult.CONTINUE;
                            return isExcludedDir(dir.getFileName().toString())
                                    ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                                    : java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(
                                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                            out.add(file);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(Path file,
                                                                            IOException exc) {
                            // Unreadable entries are skipped, like the rest of
                            // the loader's best-effort walks.
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            // best-effort: an unreadable tree just omits those files
        }
    }

    /** Mirrors the mapper XML scan's exclusions (build output, hidden dirs). */
    private static boolean isExcludedDir(String name) {
        return name.startsWith(".") || name.equals("target") || name.equals("node_modules");
    }

    /** The cache directory, overridable for tests. */
    static Path cacheDir() {
        String override = System.getProperty("codescope.cache.dir");
        if (override != null && !override.isBlank()) return Paths.get(override);
        String home = System.getProperty("user.home");
        return Paths.get(home == null ? "." : home, ".cache", "codescope");
    }
}
