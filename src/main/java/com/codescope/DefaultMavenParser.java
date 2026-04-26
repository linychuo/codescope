package com.codescope;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.InputSource;

/**
 * XML-based Maven POM parser.
 */
public class DefaultMavenParser implements MavenParser {
    private static final Logger logger = Logger.getLogger("MavenParser");

    @Override
    public List<String> parseDependencies(Path pom) {
        if (pom == null || !Files.exists(pom)) {
            return Collections.emptyList();
        }
        try {
            String content = Files.readString(pom);
            return parseDependencies(content);
        } catch (IOException e) {
            logger.warning("Failed to read pom.xml: " + pom, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> parseDependencies(String pomContent) {
        List<String> deps = new ArrayList<>();
        if (pomContent == null || pomContent.isEmpty()) {
            return deps;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(pomContent)));

            NodeList dependencies = doc.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dep = (Element) dependencies.item(i);

                String groupId = getTextContent(dep, "groupId");
                String artifactId = getTextContent(dep, "artifactId");
                String version = getTextContent(dep, "version");

                if (groupId != null && artifactId != null) {
                    String depStr = groupId + ":" + artifactId + ":" + (version != null ? version : "latest");
                    deps.add(depStr);
                }
            }
        } catch (SAXException | IOException e) {
            logger.warning("Failed to parse POM content", e);
        } catch (Exception e) {
            logger.warning("Unexpected error parsing POM", e);
        }

        return deps;
    }

    private String getTextContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String content = nodes.item(0).getTextContent();
            return content != null ? content.trim() : null;
        }
        return null;
    }
}
