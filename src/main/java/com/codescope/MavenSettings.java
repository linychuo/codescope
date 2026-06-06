package com.codescope;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads Maven user settings (~/.m2/settings.xml). */
public final class MavenSettings {

    private MavenSettings() {}

    /** @return local repository path from settings.xml, or null if unset. */
    public static Path readLocalRepository() {
        return readLocalRepository(defaultSettingsPath());
    }

    static Path readLocalRepository(Path settingsFile) {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) return null;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Disable external entity resolution to avoid XXE on untrusted settings.xml.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(settingsFile.toFile());
            NodeList list = doc.getElementsByTagName("localRepository");
            if (list.getLength() == 0) return null;
            Node first = list.item(0);
            if (first.getNodeType() != Node.ELEMENT_NODE) return null;
            String text = ((Element) first).getTextContent();
            if (text == null) return null;
            text = text.trim();
            return text.isEmpty() ? null : Path.of(text);
        } catch (Exception e) {
            return null;   // malformed settings.xml — fall back to default
        }
    }

    private static Path defaultSettingsPath() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".m2", "settings.xml");
    }
}
