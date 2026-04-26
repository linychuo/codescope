package com.codescope;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for parsing Maven POM files.
 */
public interface MavenParser {

    /**
     * Parses Maven dependencies from a POM file.
     * @param pom path to pom.xml
     * @return list of dependencies in format "groupId:artifactId:version"
     */
    List<String> parseDependencies(Path pom);

    /**
     * Parses Maven dependencies from POM content string.
     */
    List<String> parseDependencies(String pomContent);
}
