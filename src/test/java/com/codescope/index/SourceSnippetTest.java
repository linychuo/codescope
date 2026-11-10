package com.codescope.index;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SourceSnippet}, the helper behind the issue #9
 * source-context fields. The snippet path is best-effort: a missing file,
 * an out-of-range line, or an unreadable source must yield null (the key
 * is omitted), never an exception in the middle of formatting a result.
 */
class SourceSnippetTest {

    @Test
    void readsRelativeAndAbsolutePaths(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("A.java"), "line1\nline2\nline3\n");
        SourceSnippet snippet = new SourceSnippet(tmp);
        assertEquals("line1", snippet.line("src/main/java/com/example/A.java", 1));
        assertEquals("line3", snippet.line("src/main/java/com/example/A.java", 3));
        assertEquals("line1", snippet.line(src.resolve("A.java").toString(), 1));
    }

    @Test
    void preservesIndentationAndStripsTrailingWhitespace(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("B.java"), "    int x = 1;   \n");
        SourceSnippet snippet = new SourceSnippet(tmp);
        assertEquals("    int x = 1;", snippet.line("src/B.java", 1));
    }

    @Test
    void missingFileOrLineReturnsNull(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("C.java"), "only\n");
        SourceSnippet snippet = new SourceSnippet(tmp);
        assertNull(snippet.line("src/C.java", 2), "line past EOF");
        assertNull(snippet.line("src/Missing.java", 1), "missing file");
        assertNull(snippet.line(null, 1), "null file");
        assertNull(snippet.line("src/C.java", 0), "line 0 = unknown");
        assertNull(snippet.line("src/C.java", -3), "negative line");
    }

    @Test
    void unreadableFileIsCachedAndDoesNotThrow(@TempDir Path tmp) throws Exception {
        SourceSnippet snippet = new SourceSnippet(tmp);
        assertNull(snippet.line("does/not/exist.java", 1));
        // Second call must not throw either (the miss is cached).
        assertNull(snippet.line("does/not/exist.java", 1));
    }

    @Test
    void parentRelativePathResolvesAgainstRoot(@TempDir Path tmp) throws Exception {
        // Multi-module sources can be relativized with a ".." prefix. The
        // root itself must exist: the OS resolves ".." component-wise, so a
        // missing root makes the whole path unresolvable.
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Path outside = Files.createDirectories(tmp.resolve("other"));
        Files.writeString(outside.resolve("D.java"), "outside\n");
        SourceSnippet snippet = new SourceSnippet(root);
        assertEquals("outside", snippet.line("../other/D.java", 1));
    }
}
