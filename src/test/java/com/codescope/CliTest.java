package com.codescope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI front-end tests. Drives {@link Cli#run} directly (not {@code main})
 * so {@code System.exit} doesn't tear down the test JVM. Stdout/stderr
 * are captured per-test via {@link System#setOut} / {@link System#setErr}.
 */
class CliTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream origOut;
    private PrintStream origErr;

    @BeforeEach
    void setUpStreams() {
        origOut = System.out;
        origErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(origOut);
        System.setErr(origErr);
    }

    @Test
    void noArgsExitsWithUsageToStderr() {
        int code = Cli.run(new String[]{});
        assertEquals(2, code);
        assertTrue(err.toString().contains("Usage:"),
                "expected usage hint on stderr, got: " + err);
    }

    @Test
    void helpExitsZeroAndPrintsToStdout() {
        int code = Cli.run(new String[]{"--help"});
        assertEquals(0, code);
        assertTrue(out.toString().contains("Usage:"),
                "expected usage to stdout, got: " + out);
    }

    @Test
    void unknownCommandExitsTwo(@TempDir Path tmp) {
        // Any project path works — we want dispatch to fail on the
        // command name, not on the project validation. tmp exists but
        // has no pom.xml, which the service layer would reject; we get
        // to the dispatch first because the project path is non-null.
        int code = Cli.run(new String[]{
                "nope",
                "--project", tmp.toString()});
        assertEquals(2, code);
        assertTrue(err.toString().contains("Unknown command"),
                "expected 'Unknown command' in stderr, got: " + err);
    }

    @Test
    void missingProjectExitsTwo() {
        int code = Cli.run(new String[]{"trace-callers", "com.x.Foo", "bar"});
        assertEquals(2, code);
        assertTrue(err.toString().contains("--project is required"),
                "expected '--project is required' in stderr, got: " + err);
    }

    @Test
    void missingPositionalForTraceCallersExitsTwo() {
        int code = Cli.run(new String[]{
                "trace-callers", "com.x.Foo",
                "--project", "/some/path"});
        assertEquals(2, code);
        assertTrue(err.toString().contains("Missing required positional argument: method"),
                "expected missing method error, got: " + err);
    }

    @Test
    void unknownOptionExitsTwo() {
        int code = Cli.run(new String[]{
                "trace-callers", "com.x.Foo", "bar",
                "--project", "/some/path", "--bogus", "value"});
        assertEquals(2, code);
        assertTrue(err.toString().contains("Unknown option: --bogus"),
                "expected unknown option error, got: " + err);
    }

    @Test
    void nonNumericArityExitsTwo() {
        // --arity used to throw NumberFormatException → "internal error" exit 1.
        // Now it should be a usage error → exit 2 with a clear message.
        int code = Cli.run(new String[]{
                "trace-callers", "com.x.Foo", "bar",
                "--project", "/some/path", "--arity", "abc"});
        assertEquals(2, code, "stderr: " + err);
        assertTrue(err.toString().contains("--arity requires an integer"),
                "expected integer-required message, got: " + err);
    }

    @Test
    void negativeArityExitsTwo() {
        // Negative arity silently matches nothing downstream; surface it
        // at the CLI layer so the user notices instead of getting an empty
        // trace.
        int code = Cli.run(new String[]{
                "trace-callers", "com.x.Foo", "bar",
                "--project", "/some/path", "--arity", "-1"});
        assertEquals(2, code, "stderr: " + err);
        assertTrue(err.toString().contains("--arity must be >= 0"),
                "expected >=0 message, got: " + err);
    }

    @Test
    void nonNumericLimitExitsTwo() {
        int code = Cli.run(new String[]{
                "find-symbols", "x",
                "--project", "/some/path", "--limit", "lots"});
        assertEquals(2, code, "stderr: " + err);
        assertTrue(err.toString().contains("--limit requires an integer"),
                "expected integer-required message, got: " + err);
    }

    @Test
    void badProjectPathExitsOne(@TempDir Path tmp) {
        // tmp exists but has no pom.xml — the service rejects it.
        int code = Cli.run(new String[]{
                "trace-callers", "com.x.Foo", "bar",
                "--project", tmp.toString()});
        assertEquals(1, code, "stderr: " + err);
        assertTrue(err.toString().contains("No pom.xml"),
                "expected 'No pom.xml' error, got: " + err);
    }

    @Test
    void traceCallersOnCodescopeProjectItself() {
        // The codescope project has a real pom.xml and plenty of cross-file
        // calls, so a known method gives a non-empty, well-formed response.
        // Run from any CWD — we resolve relative to the working directory
        // here, and the surefire test runs from the project root by default.
        Path project = Path.of(".").toAbsolutePath();
        if (!Files.isRegularFile(project.resolve("pom.xml"))) {
            // Skip if test isn't running from the project root (e.g. IDE
            // configured to run from elsewhere). The CLI behavior is the
            // same regardless; we just can't assert output shape.
            return;
        }
        int code = Cli.run(new String[]{
                "trace-callers", "com.codescope.McpServer", "writeLine",
                "--project", project.toString()});
        assertEquals(0, code, "stderr: " + err);
        String outStr = out.toString();
        // Pretty-printed JSON envelope: top-level keys "target", "status", "message".
        assertTrue(outStr.contains("\"status\""), "expected status field, got: " + outStr);
        assertTrue(outStr.contains("\"message\""), "expected message field, got: " + outStr);
        assertTrue(outStr.contains("com.codescope.McpServer"),
                "expected target class in output, got: " + outStr);
    }

    @Test
    void findCallSitesOnCodescopeProject() {
        Path project = Path.of(".").toAbsolutePath();
        if (!Files.isRegularFile(project.resolve("pom.xml"))) return;
        int code = Cli.run(new String[]{
                "find-call-sites", "com.codescope.McpServer", "writeLine",
                "--project", project.toString()});
        assertEquals(0, code, "stderr: " + err);
        String outStr = out.toString();
        assertTrue(outStr.contains("\"call_sites\""),
                "expected call_sites array, got: " + outStr);
    }

    @Test
    void findSymbolsOnCodescopeProject() {
        Path project = Path.of(".").toAbsolutePath();
        if (!Files.isRegularFile(project.resolve("pom.xml"))) return;
        int code = Cli.run(new String[]{
                "find-symbols", "writeLine",
                "--project", project.toString(),
                "--kind", "method",
                "--limit", "5"});
        assertEquals(0, code, "stderr: " + err);
        String outStr = out.toString();
        assertTrue(outStr.contains("\"symbols\""),
                "expected symbols array, got: " + outStr);
    }

    @Test
    void refreshFlagIsAccepted() {
        // Doesn't need to do anything different from no-refresh for this
        // assertion — we just need to verify the flag is plumbed through
        // without a usage error. Real refresh semantics are covered by
        // the service-layer tests.
        Path project = Path.of(".").toAbsolutePath();
        if (!Files.isRegularFile(project.resolve("pom.xml"))) return;
        int code = Cli.run(new String[]{
                "find-symbols", "writeLine",
                "--project", project.toString(),
                "--refresh"});
        assertEquals(0, code, "stderr: " + err);
    }

    @Test
    void unknownSymbolKindExitsOne() {
        Path project = Path.of(".").toAbsolutePath();
        if (!Files.isRegularFile(project.resolve("pom.xml"))) return;
        int code = Cli.run(new String[]{
                "find-symbols", "x",
                "--project", project.toString(),
                "--kind", "bogus"});
        assertEquals(1, code, "stderr: " + err);
        assertTrue(err.toString().contains("Unknown kind"),
                "expected 'Unknown kind' in stderr, got: " + err);
    }
}
