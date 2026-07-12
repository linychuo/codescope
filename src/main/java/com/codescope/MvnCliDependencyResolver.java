package com.codescope;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MvnCliDependencyResolver implements DependencyResolver {

    private static final String ENV_MVN_ARGS = "CODESCOPE_MVN_ARGS";
    private static final boolean IS_WINDOWS = System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("win");

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
            // Read stdout to avoid blocking
            String stdout = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("mvn dependency:build-classpath failed (exit="
                        + exitCode + "): " + stdout);
            }
            if (!Files.isRegularFile(outputFile)) {
                return List.of();  // no output file -> no dependencies
            }
            return parseClasspathFile(outputFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("mvn process was interrupted", e);
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

    private List<String> buildCommand(Path projectRoot, Path outputFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveMvnCommand(projectRoot));
        cmd.add("-f");
        cmd.add(projectRoot.resolve("pom.xml").toString());
        // User-provided extra args (from CLI / factory)
        cmd.addAll(extraArgs);
        // Env var fallback
        if (extraArgs.isEmpty()) {
            String envArgs = System.getenv(ENV_MVN_ARGS);
            if (envArgs != null && !envArgs.isBlank()) {
                cmd.addAll(Arrays.asList(envArgs.split("\\s+")));
            }
        }
        cmd.add("dependency:build-classpath");
        cmd.add("-Dmdep.outputFile=" + outputFile.toAbsolutePath());
        cmd.add("-Dmdep.outputAbsoluteArtifactFilename=true");
        cmd.add("-Dmdep.includeScope=compile");
        cmd.add("-q");  // quiet mode - less noise
        return cmd;
    }
}
