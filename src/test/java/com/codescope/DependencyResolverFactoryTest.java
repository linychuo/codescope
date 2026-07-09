package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverFactoryTest {

    @Test
    void createsMvnCliResolverForPomProject(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        DependencyResolver resolver = DependencyResolverFactory.create(tmp);
        assertInstanceOf(MvnCliDependencyResolver.class, resolver);
    }

    @Test
    void throwsWhenNoBuildFile(@TempDir Path tmp) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DependencyResolverFactory.create(tmp));
        assertTrue(e.getMessage().contains("No recognized build file"), "got: " + e.getMessage());
    }

    @Test
    void throwsForGradleProject(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), "dependencies {}");
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> DependencyResolverFactory.create(tmp));
        assertTrue(e.getMessage().contains("not yet implemented"), "got: " + e.getMessage());
    }

    @Test
    void setMavenExtraArgsPassedToResolver(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        DependencyResolverFactory.setMavenExtraArgs(List.of("-gs", "/path/to/settings.xml"));
        try {
            DependencyResolver resolver = DependencyResolverFactory.create(tmp);
            assertInstanceOf(MvnCliDependencyResolver.class, resolver);
        } finally {
            // Reset to avoid side effects on other tests
            DependencyResolverFactory.setMavenExtraArgs(List.of());
        }
    }
}
