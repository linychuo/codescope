package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Direct tests for {@link MavenSettings} — XXE defense, edge cases, malformed input. */
class MavenSettingsTest {

    @Test
    void returnsNullForMissingFile(@TempDir Path tmp) {
        assertNull(MavenSettings.readLocalRepository(tmp.resolve("nope.xml")));
    }

    @Test
    void returnsNullForNullPath() {
        assertNull(MavenSettings.readLocalRepository(null));
    }

    @Test
    void extractsLocalRepository(@TempDir Path tmp) throws Exception {
        Path s = tmp.resolve("settings.xml");
        Files.writeString(s, """
                <settings>
                  <localRepository>/var/maven/repo</localRepository>
                </settings>
                """);
        assertEquals(Path.of("/var/maven/repo"),
                MavenSettings.readLocalRepository(s));
    }

    @Test
    void trimsWhitespace(@TempDir Path tmp) throws Exception {
        Path s = tmp.resolve("settings.xml");
        Files.writeString(s, "<settings><localRepository>   /spaced/repo   </localRepository></settings>");
        assertEquals(Path.of("/spaced/repo"), MavenSettings.readLocalRepository(s));
    }

    @Test
    void emptyLocalRepositoryReturnsNull(@TempDir Path tmp) throws Exception {
        Path s = tmp.resolve("settings.xml");
        Files.writeString(s, "<settings><localRepository></localRepository></settings>");
        assertNull(MavenSettings.readLocalRepository(s));
    }

    @Test
    void missingLocalRepositoryElementReturnsNull(@TempDir Path tmp) throws Exception {
        Path s = tmp.resolve("settings.xml");
        Files.writeString(s, "<settings><other>value</other></settings>");
        assertNull(MavenSettings.readLocalRepository(s));
    }

    @Test
    void malformedXmlReturnsNull(@TempDir Path tmp) throws Exception {
        Path s = tmp.resolve("settings.xml");
        Files.writeString(s, "<not-xml");
        assertNull(MavenSettings.readLocalRepository(s));
    }

    @Test
    void doctypeDeclarationIsRejected(@TempDir Path tmp) throws Exception {
        // DOCTYPE declarations are a classic XXE vector. We disabled them
        // via the disallow-doctype-decl feature; verify that an attacker
        // can't smuggle a DOCTYPE in.
        Path s = tmp.resolve("settings.xml");
        Files.writeString(s, """
                <?xml version="1.0"?>
                <!DOCTYPE settings [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <settings>
                  <localRepository>&xxe;</localRepository>
                </settings>
                """);
        // Must not resolve the entity — either throws and we return null, or
        // we get the literal "&xxe;" back. The point is no file disclosure.
        Path result = MavenSettings.readLocalRepository(s);
        if (result != null) {
            String s2 = result.toString();
            assertFalse(s2.contains("root:"), "XXE leaked system file: " + s2);
            assertFalse(s2.contains("/etc/"), "XXE leaked system file: " + s2);
        }
    }

    @Test
    void externalEntityInElementIsNotResolved(@TempDir Path tmp) throws Exception {
        // Even without a DOCTYPE, the parser is told to keep external entity
        // resolution off, so a reference like &xxe; would be untouched.
        Path s = tmp.resolve("settings.xml");
        Files.writeString(s, """
                <settings>
                  <localRepository>&nonexistent;</localRepository>
                </settings>
                """);
        // Either it throws (and we return null) or it returns the literal
        // string. The crucial guarantee: we never dereference external URIs.
        Path result = MavenSettings.readLocalRepository(s);
        if (result != null) {
            assertFalse(result.toString().startsWith("file:"),
                    "external entity should not have been resolved: " + result);
        }
    }
}
