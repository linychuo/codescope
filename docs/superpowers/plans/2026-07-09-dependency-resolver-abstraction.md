# Dependency Resolver Abstraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a `DependencyResolver` interface and replace the hardcoded `new MavenClasspathResolver()` in `ProjectLoader` with a factory that dispatches to a new `MvnCliDependencyResolver` (calls `mvn dependency:build-classpath`).

**Architecture:** `ProjectLoader.load()` → `DependencyResolverFactory.create(effective)` → `MvnCliDependencyResolver.resolve(projectRoot)` → shell out to `mvn dependency:build-classpath`, parse output. `MavenClasspathResolver` is left untouched (no longer referenced from `ProjectLoader`).

**Tech Stack:** Java 21, JUnit 5, `@TempDir`

## Global Constraints

- `MavenClasspathResolver` must NOT be deleted or modified
- `discoverEffectiveRoot()` stays unchanged (Gradle adaptation deferred)
- Extra Maven args flow through `DependencyResolverFactory.setMavenExtraArgs(List<String>)` and environment variable `CODESCOPE_MVN_ARGS` as fallback
- No new dependencies added to pom.xml

---

### Task 1: DependencyResolver interface

**Files:**
- Create: `src/main/java/com/codescope/DependencyResolver.java`

**Interfaces:**
- Consumes: nothing
- Produces: `com.codescope.DependencyResolver` interface with single method `List<String> resolve(Path projectRoot) throws IOException`

- [ ] **Step 1: Create the interface**

```java
package com.codescope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface DependencyResolver {
    List<String> resolve(Path projectRoot) throws IOException;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/codescope/DependencyResolver.java
git commit -m "feat: add DependencyResolver interface"
```

---

### Task 2: MvnCliDependencyResolver

**Files:**
- Create: `src/main/java/com/codescope/MvnCliDependencyResolver.java`
- Test: `src/test/java/com/codescope/MvnCliDependencyResolverTest.java`

**Interfaces:**
- Consumes: `DependencyResolver` (from Task 1)
- Produces: `MvnCliDependencyResolver implements DependencyResolver` — resolves Maven dependencies by shelling out to `mvn dependency:build-classpath`

- [ ] **Step 1: Write the failing test**

Tests for output parsing, error handling, and command execution.

```java
package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MvnCliDependencyResolverTest {

    @Test
    void throwsWhenNoPomFound(@TempDir Path tmp) {
        MvnCliDependencyResolver r = new MvnCliDependencyResolver();
        IOException e = assertThrows(IOException.class, () -> r.resolve(tmp));
        assertTrue(e.getMessage().contains("pom.xml"), "got: " + e.getMessage());
    }

    @Test
    void parsesClasspathOutput(@TempDir Path tmp) throws Exception {
        // Simulate the content that mvn dependency:build-classpath writes:
        // a colon-separated list of absolute jar paths.
        Path outputFile = tmp.resolve("classpath.out");
        Files.writeString(outputFile,
                "/home/user/.m2/repository/com/google/guava/guava/32.1.3/guava-32.1.3.jar"
                + File.pathSeparator
                + "/home/user/.m2/repository/org/junit/jupiter/junit-jupiter/5.10.3/junit-jupiter-5.10.3.jar");

        MvnCliDependencyResolver r = new MvnCliDependencyResolver();
        List<String> cp = r.parseClasspathFile(outputFile);
        assertEquals(2, cp.size(), "got: " + cp);
        assertTrue(cp.get(0).endsWith("guava-32.1.3.jar"));
        assertTrue(cp.get(1).endsWith("junit-jupiter-5.10.3.jar"));
    }

    @Test
    void emptyOutputReturnsEmptyList(@TempDir Path tmp) throws Exception {
        Path outputFile = tmp.resolve("empty.cp");
        Files.writeString(outputFile, "");

        MvnCliDependencyResolver r = new MvnCliDependencyResolver();
        List<String> cp = r.parseClasspathFile(outputFile);
        assertTrue(cp.isEmpty(), "expected empty, got: " + cp);
    }

    @Test
    void resolvesInFixtureProject() throws Exception {
        // Integration test: run against the fixture project (no deps, just
        // verify the mvn command succeeds and returns something).
        MvnCliDependencyResolver r = new MvnCliDependencyResolver();
        List<String> cp = r.resolve(Path.of("src/test/resources/fixture-project"));
        // The fixture project has no dependencies, so the classpath
        // should be empty or contain only the project's own output dir.
        assertNotNull(cp);
    }
}
```

Note: `parseClasspathFile` is a package-private helper we expose for testing. The method reads the file, splits on `File.pathSeparator`, filters empty strings.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=MvnCliDependencyResolverTest -Dsurefire.useFile=false`
Expected: compilation error (class not found)

- [ ] **Step 3: Write minimal implementation**

```java
package com.codescope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MvnCliDependencyResolver implements DependencyResolver {

    private static final String ENV_MVN_ARGS = "CODESCOPE_MVN_ARGS";

    private final List<String> extraArgs;

    public MvnCliDependencyResolver(List<String> extraArgs) {
        this.extraArgs = extraArgs != null ? List.copyOf(extraArgs) : List.of();
    }

    public MvnCliDependencyResolver() {
        this(List.of());
    }

    @Override
    public List<String> resolve(Path projectRoot) throws IOException {
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
                return List.of();  // no output file → no dependencies
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

    private List<String> buildCommand(Path projectRoot, Path outputFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
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
        cmd.add("-q");  // quiet mode — less noise
        return cmd;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=MvnCliDependencyResolverTest -Dsurefire.useFile=false`
Expected: unit tests pass (the `resolvesInFixtureProject` integration test may be skipped if `mvn` isn't available — we can add `@EnabledIf` or just accept it may fail on CI without Maven)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codescope/MvnCliDependencyResolver.java src/test/java/com/codescope/MvnCliDependencyResolverTest.java
git commit -m "feat: add MvnCliDependencyResolver using mvn dependency:build-classpath"
```

---

### Task 3: DependencyResolverFactory

**Files:**
- Create: `src/main/java/com/codescope/DependencyResolverFactory.java`
- Test: `src/test/java/com/codescope/DependencyResolverFactoryTest.java`

**Interfaces:**
- Consumes: `DependencyResolver`, `MvnCliDependencyResolver` (from Tasks 1-2)
- Produces: `DependencyResolverFactory` — static factory that auto-detects build tool and returns the right resolver

- [ ] **Step 1: Write the failing test**

```java
package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=DependencyResolverFactoryTest -Dsurefire.useFile=false`
Expected: compilation error (class not found)

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=DependencyResolverFactoryTest -Dsurefire.useFile=false`
Expected: all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/codescope/DependencyResolverFactory.java src/test/java/com/codescope/DependencyResolverFactoryTest.java
git commit -m "feat: add DependencyResolverFactory with auto-detection"
```

---

### Task 4: Wire into ProjectLoader

**Files:**
- Modify: `src/main/java/com/codescope/ProjectLoader.java` (line 49)

**Interfaces:**
- Consumes: `DependencyResolverFactory.create()` (from Task 3)
- Produces: `ProjectLoader.load()` now uses `DependencyResolverFactory` instead of `new MavenClasspathResolver()`

- [ ] **Step 1: Change the one line**

In `ProjectLoader.java`, line 49, replace:

```java
List<String> classpath = new MavenClasspathResolver().resolve(effective);
```

with:

```java
List<String> classpath = DependencyResolverFactory.create(effective).resolve(effective);
```

- [ ] **Step 2: Run existing tests to verify nothing broke**

Run: `mvn test -Dsurefire.useFile=false`
Expected: all existing tests pass (the fixture project tests that use `ProjectLoader` will now go through `MvnCliDependencyResolver`, which shells out to `mvn`)

Note: If the integration test environment doesn't have `mvn` available, some tests that use `ProjectLoader.load()` against the fixture project may fail. In that case we need to handle it — see the next step.

- [ ] **Step 3: Handle Maven-less environments (if tests fail)**

If tests fail because `mvn` isn't available in the test environment:

Option A: Add `@EnabledIf` to skip integration tests that need `mvn`
Option B: Set `CODESCOPE_MVN_ARGS` in CI if needed

Check the actual test failures and apply the minimal fix.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/codescope/ProjectLoader.java
git commit -m "refactor: wire DependencyResolverFactory into ProjectLoader"
```

---

### Task 5: Verify full test suite

- [ ] **Step 1: Run full test suite**

```bash
mvn test -Dsurefire.useFile=false
```

Expected: all tests pass (or only tests that genuinely need `mvn` are skipped)

- [ ] **Step 2: Commit any remaining changes**

```bash
git add -A
git commit -m "test: add MvnCliDependencyResolver and DependencyResolverFactory tests"
```
