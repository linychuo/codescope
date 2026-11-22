package com.codescope.dependency;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MvnCliDependencyResolver implements DependencyResolver {

    private static final String ENV_MVN_ARGS = "CODESCOPE_MVN_ARGS";
    private static final String PROP_MVN_ARGS = "codescope.mvn.args";
    private static final String PROP_MVN_TIMEOUT = "codescope.mvn.timeout";
    /** Default cap on one mvn invocation (10 minutes). */
    private static final long DEFAULT_TIMEOUT_SECONDS = 600;
    private static final boolean IS_WINDOWS = System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("win");

    /**
     * Cap on one {@code mvn dependency:build-classpath} invocation, in
     * seconds. Without it a Maven that hangs (network fetch, prompt, lock
     * contention) would block the whole tool indefinitely. Override with
     * {@code -Dcodescope.mvn.timeout=<seconds>}.
     */
    static long timeoutSeconds() {
        String v = System.getProperty(PROP_MVN_TIMEOUT);
        if (v == null || v.isBlank()) return DEFAULT_TIMEOUT_SECONDS;
        try {
            long n = Long.parseLong(v.trim());
            return n < 0 ? DEFAULT_TIMEOUT_SECONDS : n;
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    private final List<String> extraArgs;

    public MvnCliDependencyResolver(List<String> extraArgs) {
        this.extraArgs = extraArgs != null ? List.copyOf(extraArgs) : List.of();
    }

    public MvnCliDependencyResolver() {
        this(List.of());
    }

    @Override
    public List<String> resolve(Path projectRoot) throws IOException {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException("pom.xml not found under " + projectRoot);
        }

        // Disk cache first: the CLI is a one-shot process, so without this
        // every invocation pays the full Maven cost (measured at 27.6s of a
        // 33.8s warm run on MyBatis-3, most of it dependency metadata
        // resolution rather than the index build). Keyed by the pom contents
        // plus the extra args, so a dependency change is picked up.
        List<String> effectiveArgs = effectiveExtraArgs();
        String fingerprint = ClasspathCache.fingerprint(projectRoot, effectiveArgs,
                settingsDigest(effectiveArgs));
        if (fingerprint != null) {
            List<String> cached = ClasspathCache.load(fingerprint);
            if (cached != null) return cached;
        }
        List<String> resolved = resolveViaMaven(projectRoot);
        if (fingerprint != null) ClasspathCache.save(fingerprint, resolved);
        return resolved;
    }

    /**
     * Resolves the classpath for {@code projectRoot}. When the pom is an
     * aggregator ({@code <modules>}), the goal is invoked once per module
     * and the results are merged: a single {@code dependency:build-classpath}
     * on the aggregator runs the goal for every module, and each module
     * overwrites the same {@code -Dmdep.outputFile}, so only the last
     * module's classpath would survive.
     */
    private List<String> resolveViaMaven(Path projectRoot) throws IOException {
        List<Path> modules = moduleDirs(projectRoot);
        if (modules.isEmpty()) return resolveSingleModule(projectRoot);
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        for (Path module : modules) merged.addAll(resolveViaMaven(module));
        return new ArrayList<>(merged);
    }

    /**
     * Direct sub-module directories declared by {@code projectRoot/pom.xml},
     * or an empty list for a standalone project. Best-effort: a malformed
     * or missing module pom is skipped rather than aborting the whole
     * resolution.
     */
    private static List<Path> moduleDirs(Path projectRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) return List.of();
        try (InputStream in = Files.newInputStream(pom)) {
            org.apache.maven.model.Model model =
                    new org.apache.maven.model.io.xpp3.MavenXpp3Reader().read(in);
            List<Path> out = new ArrayList<>();
            for (String rel : model.getModules()) {
                if (rel == null || rel.isBlank()) continue;
                Path dir = projectRoot.resolve(rel).toAbsolutePath().normalize();
                if (Files.isRegularFile(dir.resolve("pom.xml"))) out.add(dir);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> resolveSingleModule(Path projectRoot) throws IOException {
        // Create temp file for maven output
        Path outputFile = Files.createTempFile("codescope-mvn-cp-", ".tmp");
        try {
            List<String> cmd = buildCommand(projectRoot, outputFile);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // Drain stdout on a separate virtual thread: a chatty Maven must
            // not fill the pipe and deadlock us while we wait for exit. The
            // try-with-resources closes the pipe when the drain finishes (or
            // when the process is killed and the blocked read unblocks).
            CompletableFuture<String> stdoutFuture = new CompletableFuture<>();
            Thread.ofVirtual().name("mvn-stdout").start(() -> {
                try (InputStream in = process.getInputStream()) {
                    stdoutFuture.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    stdoutFuture.complete("");
                }
            });
            IOException failure = null;
            try {
                if (!process.waitFor(timeoutSeconds(), TimeUnit.SECONDS)) {
                    failure = new IOException("mvn dependency:build-classpath timed out after "
                            + timeoutSeconds() + "s");
                }
            } catch (InterruptedException e) {
                failure = new IOException("mvn process was interrupted", e);
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                // Kill the whole descendant tree (destroyForcibly only signals
                // the direct child; on Windows mvn.cmd doesn't exec, so the
                // java.exe running Maven would otherwise survive) and wait for
                // it to die before the finally block deletes the output file.
                destroyTreeAndAwait(process);
                throw failure;
            }
            String stdout = stdoutFuture.join();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("mvn dependency:build-classpath failed (exit="
                        + exitCode + "): " + stdout);
            }
            if (!Files.isRegularFile(outputFile)) {
                return List.of();  // no output file -> no dependencies
            }
            return parseClasspathFile(outputFile);
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException e) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Destroy the whole descendant tree of {@code process} and wait briefly
     * for termination. {@code Process.destroyForcibly()} only signals the
     * direct child; on Windows (and for wrappers that don't {@code exec}),
     * the JVM running Maven survives unless its parent is killed too. The
     * bounded wait keeps the caller from hanging if a descendant ignores
     * the signal; the OS reaps whatever is left. The caller's interrupt
     * status is preserved across the wait.
     */
    private static void destroyTreeAndAwait(Process process) {
        try {
            process.toHandle().descendants().forEach(p -> {
                try { p.destroyForcibly(); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) {
            // descendant enumeration can fail on some platforms
        }
        process.destroyForcibly();
        boolean wasInterrupted = Thread.interrupted();   // clear so waitFor can run
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Spurious wakeup; the process has already been signalled.
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
    }

    List<String> parseClasspathFile(Path outputFile) throws IOException {
        String content = Files.readString(outputFile).trim();
        if (content.isEmpty()) return List.of();
        return Arrays.stream(content.split(File.pathSeparator))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Resolve the mvn executable path, with Maven wrapper and Windows support. */
    static String resolveMvnCommand(Path projectRoot) {
        // 1. Maven wrapper: mvnw (Linux/macOS) or mvnw.cmd (Windows)
        if (IS_WINDOWS) {
            Path wrapper = projectRoot.resolve("mvnw.cmd");
            if (Files.isRegularFile(wrapper)) return wrapper.toAbsolutePath().toString();
        } else {
            Path wrapper = projectRoot.resolve("mvnw");
            // A wrapper checked out of an archive / mounted volume can lose
            // its exec bit; ProcessBuilder would then fail with "Permission
            // denied" with no fallback. Only use it when actually executable.
            if (Files.isRegularFile(wrapper) && Files.isExecutable(wrapper)) {
                return wrapper.toAbsolutePath().toString();
            }
        }

        // 2. MAVEN_HOME environment variable
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isBlank()) {
            Path mvn = Path.of(mavenHome, "bin", IS_WINDOWS ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(mvn)) return mvn.toAbsolutePath().toString();
        }

        // 3. Fallback: rely on PATH — on Windows, use mvn.cmd so ProcessBuilder resolves it
        return IS_WINDOWS ? "mvn.cmd" : "mvn";
    }

    List<String> buildCommand(Path projectRoot, Path outputFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveMvnCommand(projectRoot));
        cmd.add("-f");
        cmd.add(projectRoot.resolve("pom.xml").toString());
        for (String a : effectiveExtraArgs()) {
            if (!a.isEmpty()) cmd.add(a);
        }
        cmd.add("dependency:build-classpath");
        cmd.add("-Dmdep.outputFile=" + outputFile.toAbsolutePath());
        cmd.add("-Dmdep.outputAbsoluteArtifactFilename=true");
        cmd.add("-Dmdep.includeScope=compile");
        cmd.add("-q");  // quiet mode - less noise
        return cmd;
    }

    /**
     * The extra Maven arguments actually in effect: the factory/CLI list if
     * set, else {@code -Dcodescope.mvn.args}, else {@code CODESCOPE_MVN_ARGS}.
     * Both fallback sources are parsed with {@link #splitShellWords} so a
     * quoted path containing spaces survives.
     */
    List<String> effectiveExtraArgs() {
        if (!extraArgs.isEmpty()) return extraArgs;
        String propArgs = System.getProperty(PROP_MVN_ARGS);
        if (propArgs != null && !propArgs.isBlank()) return splitShellWords(propArgs);
        String envArgs = System.getenv(ENV_MVN_ARGS);
        if (envArgs != null && !envArgs.isBlank()) return splitShellWords(envArgs);
        return List.of();
    }

    /**
     * A digest of everything that can change the resolved classpath without
     * touching a pom: the user {@code ~/.m2/settings.xml}, any {@code -gs}/
     * {@code --settings} file referenced by the extra args, and an explicit
     * {@code -Dmaven.repo.local=...}. Empty when none of them are present.
     * Feeds {@link ClasspathCache} so a settings/local-repository change
     * invalidates the cached classpath.
     */
    String settingsDigest(List<String> args) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            Path userSettings = Path.of(System.getProperty("user.home", "."),
                    ".m2", "settings.xml");
            if (Files.isRegularFile(userSettings)) {
                md.update(Files.readAllBytes(userSettings));
            }
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i);
                if (("-gs".equals(a) || "--settings".equals(a)) && i + 1 < args.size()) {
                    Path p = Path.of(args.get(i + 1));
                    if (Files.isRegularFile(p)) md.update(Files.readAllBytes(p));
                } else if (a.startsWith("-Dmaven.repo.local=")) {
                    md.update(a.getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Splits a shell-style argument string on whitespace, honoring single and
     * double quotes so a path with spaces stays one argument:
     * {@code -gs "/x/My Settings/settings.xml"} → {@code ["-gs",
     * "/x/My Settings/settings.xml"]}. Quote characters are stripped; an
     * explicitly quoted empty string yields one empty argument.
     */
    static List<String> splitShellWords(String args) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false, started = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                started = true;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                started = true;
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (started) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    started = false;
                }
            } else {
                cur.append(c);
                started = true;
            }
        }
        if (started) out.add(cur.toString());
        return out;
    }
}
