package com.codescope.dependency;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Disk cache for the Maven-resolved classpath (issue #9 follow-up, found in the
 * large-project review).
 *
 * <p>The CLI is a one-shot process, so without a cache every invocation pays
 * the full {@code mvn dependency:build-classpath} cost — measured at 27.6s of
 * a 33.8s warm run on MyBatis-3 (1406 Java files), and the first run can be
 * far worse while dependencies download. The cache is keyed by the project's
 * pom contents plus the extra Maven args, so a dependency change invalidates
 * it; a SNAPSHOT overwritten without a pom change does not (same limitation
 * as the index cache — use {@code refresh} then).
 *
 * <p>All operations are best-effort: a missing or unreadable entry yields a
 * miss, and a write failure is ignored — the build must never fail because of
 * the cache.
 */
final class ClasspathCache {

    private static final int MAX_ENTRIES = 100;
    private static final int MAX_DIRECTORY_DEPTH = 12;

    private ClasspathCache() {}

    /**
     * @return the hex fingerprint for {@code projectRoot}, or null if it could
     *         not be computed (a null key disables the cache for that call).
     */
    static String fingerprint(Path projectRoot, List<String> extraArgs,
                              String settingsDigest) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<Path> poms = new ArrayList<>();
            collectPoms(projectRoot, poms);
            poms.sort(Comparator.comparing(Path::toString));
            for (Path pom : poms) {
                md.update(projectRoot.relativize(pom).toString()
                        .getBytes(StandardCharsets.UTF_8));
                md.update(Files.readAllBytes(pom));
            }
            md.update(String.join("\u0000", extraArgs == null ? List.<String>of() : extraArgs)
                    .getBytes(StandardCharsets.UTF_8));
            // Settings / local repository can change which jars are resolved
            // without any pom edit; include their digest so the cached
            // classpath is invalidated too.
            md.update((settingsDigest == null ? "" : settingsDigest)
                    .getBytes(StandardCharsets.UTF_8));
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the cached classpath, or null on a miss / unreadable entry. */
    static List<String> load(String fingerprint) {
        Path file = file(fingerprint);
        if (!Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return List.of();
            return Arrays.stream(content.split(java.util.regex.Pattern.quote(
                            File.pathSeparator)))
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Deletes every cached classpath. Test-only hook: tests that assert the
     * Maven invocation itself (timeout handling, argument plumbing) must not
     * be short-circuited by an entry another test cached.
     */
    static void clear() {
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().startsWith("classpath-"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
        } catch (IOException | RuntimeException e) {
            // best-effort
        }
    }

    /** Stores {@code classpath}; any failure is ignored. */
    static void save(String fingerprint, List<String> classpath) {
        if (fingerprint == null || classpath == null) return;
        Path file = file(fingerprint);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            // Temp + atomic move: a concurrent reader must never observe a
            // half-written classpath (which would parse as a valid but
            // incomplete list and poison the index cache).
            Path tmp = Files.createTempFile(parent, "classpath-", ".tmp");
            try {
                Files.writeString(tmp, String.join(File.pathSeparator, classpath),
                        StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            evictOldEntries(parent);
        } catch (IOException | RuntimeException e) {
            // Best-effort.
        }
    }

    private static Path file(String fingerprint) {
        return cacheDir().resolve("classpath-" + fingerprint + ".txt");
    }

    /**
     * Shared cache root with the index cache. Kept in sync with
     * {@code IndexFingerprint}'s root — both honour {@code
     * -Dcodescope.cache.dir} and default to {@code ~/.cache/codescope}.
     */
    private static Path cacheDir() {
        String override = System.getProperty("codescope.cache.dir");
        if (override != null && !override.isBlank()) return Paths.get(override);
        String home = System.getProperty("user.home");
        return Paths.get(home == null ? "." : home, ".cache", "codescope");
    }

    private static void collectPoms(Path root, List<Path> out) throws IOException {
        try (Stream<Path> walk = Files.walk(root, MAX_DIRECTORY_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !isUnderBuildOutput(p, root))
                    .forEach(out::add);
        }
    }

    /** Build output trees are not inputs to the classpath. */
    private static boolean isUnderBuildOutput(Path path, Path root) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("target") || name.equals("node_modules")
                    || name.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static void evictOldEntries(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> cached = entries
                    .filter(p -> p.getFileName().toString().startsWith("classpath-"))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .toList();
            int excess = cached.size() + 1 - MAX_ENTRIES;
            for (int i = 0; i < excess && i < cached.size(); i++) {
                Files.deleteIfExists(cached.get(i));
            }
        } catch (IOException | RuntimeException e) {
            // Best-effort.
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
