package com.codescope;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestClasspathResolver {
    public static void main(String[] args) throws Exception {
        Path testDir = Paths.get("/tmp/testproj");
        Path pom = testDir.resolve("pom.xml");
        System.out.println("POM exists: " + pom.toFile().exists());
        String content = new String(java.nio.file.Files.readAllBytes(pom));
        System.out.println("POM content:");
        System.out.println(content);
        System.out.println("---");
        
        // Test the regex from CommandHandler
        String patternStr = "<dependency>.*?<groupId>(.*?)</groupId>.*?<artifactId>(.*?)</artifactId>.*?(?:<version>(.*?)</version>)?.*?</dependency>";
        Pattern p = Pattern.compile(patternStr, Pattern.DOTALL);
        Matcher m = p.matcher(content);
        List<String> deps = new ArrayList<>();
        while (m.find()) {
            System.out.println("Found match:");
            System.out.println("  group(0): " + m.group(0));
            System.out.println("  group(1): [" + m.group(1) + "]");
            System.out.println("  group(2): [" + m.group(2) + "]");
            System.out.println("  group(3): [" + m.group(3) + "]");
            String groupId = m.group(1).trim();
            String artifactId = m.group(2).trim();
            String version = m.group(3) != null ? m.group(3).trim() : "unknown";
            System.out.println("  version: " + version);
            deps.add(groupId + ":" + artifactId + ":" + version);
        }
        System.out.println("Parsed dependencies: " + deps);
        
        String[] classpath = ClasspathResolver.resolveClasspath(testDir);
        System.out.println("Classpath entries:");
        for (String entry : classpath) {
            System.out.println("  " + entry);
        }
        System.out.println("Total: " + classpath.length);
    }
}