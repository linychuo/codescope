package com.codescope;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Resolves Maven dependencies to actual JAR file paths in the local repository.
 */
public class ClasspathResolver {

    private static final String DEFAULT_M2_REPO = System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository";

    /**
     * Resolves the classpath for a given directory by looking for a pom.xml and resolving dependencies.
     * @param dir directory to start searching for pom.xml
     * @return array of absolute file paths to JAR files, or empty array if no pom.xml found or error
     */
    public static String[] resolveClasspath(Path dir) {
        try {
            Path pom = findPomXml(dir);
            if (pom == null) {
                return new String[0];
            }
            List<String> dependencies = new DefaultMavenParser().parseDependencies(pom);
            if (dependencies.isEmpty()) {
                return new String[0];
            }

            String localRepo = System.getProperty("maven.repo.local", DEFAULT_M2_REPO);
            List<String> classpath = new ArrayList<>();

            for (String dep : dependencies) {
                String[] parts = dep.split(":");
                if (parts.length < 2) {
                    continue;
                }
                String groupId = parts[0];
                String artifactId = parts[1];
                String version = (parts.length >= 3) ? parts[2] : "unknown";

                if ("unknown".equals(version)) {
                    // We cannot resolve without version, skip
                    continue;
                }

                String groupPath = groupId.replace('.', File.separatorChar);
                String jarPath = localRepo + File.separator + groupPath + File.separator + artifactId + File.separator + version + File.separator + artifactId + "-" + version + ".jar";
                File jarFile = new File(jarPath);
                if (jarFile.exists()) {
                    classpath.add(jarFile.getAbsolutePath());
                } else {
                    // Optionally, we could try to find the jar with different classifiers or extensions, but for now skip
                    System.err.println("Warning: JAR not found for dependency: " + dep);
                }
            }

            return classpath.toArray(new String[0]);
        } catch (Exception e) {
            System.err.println("Error resolving classpath: " + e.getMessage());
            return new String[0];
        }
    }

    private static Path findPomXml(Path startDir) {
        Path current = startDir.toAbsolutePath();
        while (current != null) {
            try {
                Path pom = current.resolve("pom.xml");
                if (Files.exists(pom)) {
                    return pom;
                }
            } catch (Exception e) {
                // Cannot read this directory, stop walking up
                break;
            }
            current = current.getParent();
        }
        return null;
    }
}