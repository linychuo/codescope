package com.codescope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DependencyResolverFactory {

    private static List<String> mavenExtraArgs = List.of();

    private DependencyResolverFactory() {}

    public static void setMavenExtraArgs(List<String> args) {
        mavenExtraArgs = args != null ? List.copyOf(args) : List.of();
    }

    public static DependencyResolver create(Path projectRoot) {
        if (hasFile(projectRoot, "pom.xml")) {
            return new MvnCliDependencyResolver(mavenExtraArgs);
        }
        if (hasFile(projectRoot, "build.gradle")
                || hasFile(projectRoot, "build.gradle.kts")) {
            throw new UnsupportedOperationException(
                    "Gradle support is not yet implemented");
        }
        throw new IllegalArgumentException(
                "No recognized build file found under " + projectRoot
                + " (supported: pom.xml, build.gradle)");
    }

    private static boolean hasFile(Path dir, String name) {
        return Files.isRegularFile(dir.resolve(name));
    }
}
