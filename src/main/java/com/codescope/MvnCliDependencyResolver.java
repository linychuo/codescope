package com.codescope;

import java.io.File;
import java.io.IOException;
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

        // Create temp file for maven output
        Path outputFile = Files.createTempFile("codescope-mvn-cp-", ".tmp");
        try {
            List<String> cmd = buildCommand(projectRoot, outputFile);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // Drain stdout on a separate virtual thread: a chatty Maven must
            // not fill the pipe and deadlock us while we wait for exit.
            CompletableFuture<String> stdoutFuture = new CompletableFuture<>();
            Thread.ofVirtual().name("mvn-stdout").start(() -> {
                try {
                    stdoutFuture.complete(new String(
                            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    stdoutFuture.complete("");
                }
            });
            try {
                if (!process.waitFor(timeoutSeconds(), TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("mvn dependency:build-classpath timed out after "
                            + timeoutSeconds() + "s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("mvn process was interrupted", e);
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
            if (Files.isRegularFile(wrapper)) return wrapper.toAbsolutePath().toString();
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
        // User-provided extra args (from CLI / factory)
        extraArgs.stream().filter(s -> !s.isEmpty()).forEach(cmd::add);
        // System property fallback: -Dcodescope.mvn.args=...
        if (extraArgs.isEmpty()) {
            String propArgs = System.getProperty(PROP_MVN_ARGS);
            if (propArgs != null && !propArgs.isBlank()) {
                addShellWords(cmd, propArgs);
            }
        }
        // Env var fallback: CODESCOPE_MVN_ARGS
        if (extraArgs.isEmpty() && System.getProperty(PROP_MVN_ARGS) == null) {
            String envArgs = System.getenv(ENV_MVN_ARGS);
            if (envArgs != null && !envArgs.isBlank()) {
                addShellWords(cmd, envArgs);
            }
        }
        cmd.add("dependency:build-classpath");
        cmd.add("-Dmdep.outputFile=" + outputFile.toAbsolutePath());
        cmd.add("-Dmdep.outputAbsoluteArtifactFilename=true");
        cmd.add("-Dmdep.includeScope=compile");
        cmd.add("-q");  // quiet mode - less noise
        return cmd;
    }

    /**
     * Append whitespace-separated words to {@code cmd}, dropping empty
     * segments. {@code "  -gs /x "} splits to {@code ["", "-gs", "/x"]};
     * an empty argument passed through to Maven is at best ignored and at
     * worst a malformed invocation.
     */
    private static void addShellWords(List<String> cmd, String args) {
        for (String word : args.split("\\s+")) {
            if (!word.isEmpty()) cmd.add(word);
        }
    }
}
