package com.codescope;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class McpServer {

    public static void main(String[] args) throws Exception {
        System.err.println("CodeScope MCP Server starting...");
        System.err.println("Use: echo '{\"method\":\"context\",\"filePath\":\"/path/to/File.java\",\"method\":\"main\"}' | java -jar codescope.jar mcp");
        
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String line;
        
        while ((line = stdin.readLine()) != null) {
            try {
                String result = help();
                
                if (line.contains("\"method\":")) {
                    Map<String, String> params = new HashMap<>();
                    
                    Matcher m = Pattern.compile("\"method\":\"([^\"]+)\"").matcher(line);
                    String method = m.find() ? m.group(1) : "help";
                    
                    Matcher pm = Pattern.compile("\"([^\"]+)\":\"([^\"]*)\"").matcher(line);
                    while (pm.find()) {
                        params.put(pm.group(1), pm.group(2));
                    }
                    
                    switch (method) {
                        case "context":
                            result = runContext(params);
                            break;
                        case "calls":
                            result = runCalls(params);
                            break;
                        case "callers":
                            result = runCallers(params);
                            break;
                        case "impact":
                            result = runImpact(params);
                            break;
                        case "dot":
                            result = runDot(params);
                            break;
                        case "classpath":
                            result = runClasspath(params);
                            break;
                        case "index":
                            result = runIndex(params);
                            break;
                        case "ast":
                            result = runAst(params);
                            break;
                        default:
                            result = help();
                    }
                }
                
                System.out.println(result);
                System.err.flush();
            } catch (Exception e) {
                System.out.println("{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    private static String runContext(Map<String, String> params) throws Exception {
        String filePath = params.get("filePath");
        String method = params.get("method");
        
        if (filePath == null) return "{\"error\":\"filePath required\"}";
        
        Path dir = Paths.get(filePath).getParent();
        ContextBuilder cb = new ContextBuilder(dir);
        return cb.build(Paths.get(filePath), method);
    }
    
    private static String runCalls(Map<String, String> params) throws Exception {
        String filePath = params.get("filePath");
        String method = params.get("method");
        
        if (filePath == null) return "{\"error\":\"filePath required\"}";
        
        Path dir = Paths.get(filePath).getParent();
        ContextBuilder cb = new ContextBuilder(dir);
        
        ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(Paths.get(filePath), cb.getModels().get(0));
        Set<ContextBuilder.CallGraph.CallSite> calls = cg.getCallees(Paths.get(filePath), method);
        
        List<String> results = new ArrayList<>();
        for (ContextBuilder.CallGraph.CallSite call : calls) {
            results.add(call.method + " -> " + call.resolved + " (line " + call.line + ")");
        }
        return "{\"calls\":" + results + "}";
    }
    
    private static String runCallers(Map<String, String> params) throws Exception {
        String filePath = params.get("filePath");
        String method = params.get("method");
        
        if (filePath == null) return "{\"error\":\"filePath required\"}";
        
        Path dir = Paths.get(filePath).getParent();
        ContextBuilder cb = new ContextBuilder(dir);
        
        ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(Paths.get(filePath), cb.getModels().get(0));
        Set<ContextBuilder.CallGraph.CallSite> callers = cg.getCallersByName(method);
        
        List<String> results = new ArrayList<>();
        for (ContextBuilder.CallGraph.CallSite caller : callers) {
            results.add(caller.resolved + " at line " + caller.line);
        }
        return "{\"callers\":" + results + "}";
    }
    
    private static String runImpact(Map<String, String> params) throws Exception {
        String filePath = params.get("filePath");
        String method = params.get("method");
        
        if (filePath == null) return "{\"error\":\"filePath required\"}";
        
        Path path = Paths.get(filePath);
        Path dir = Files.isDirectory(path) ? path : path.getParent();
        
        List<String> results = new ArrayList<>();
        
        ContextBuilder cb = new ContextBuilder(dir);
        for (Path f : cb.getFiles()) {
            ContextBuilder.CallGraph cg = new ContextBuilder.CallGraph(f, cb.getModels().get(0));
            Set<ContextBuilder.CallGraph.CallSite> callers = cg.getCallersByName(method);
            if (!callers.isEmpty()) {
                for (ContextBuilder.CallGraph.CallSite caller : callers) {
                    results.add(f.getFileName() + ":" + caller.line + " - " + caller.resolved);
                }
            }
        }
        
        return "{\"impact\":" + results + "}";
    }
    
    private static String runDot(Map<String, String> params) throws Exception {
        String filePath = params.get("filePath");
        boolean noJdk = "true".equals(params.get("noJdk"));
        boolean cycles = "true".equals(params.get("cycles"));
        boolean heatmap = "true".equals(params.get("heatmap"));
        
        if (filePath == null || filePath.isEmpty()) {
            return "{\"error\":\"filePath required\"}";
        }
        
        Path path = Paths.get(filePath);
        Path dir = Files.isDirectory(path) ? path : path.getParent();
        ContextBuilder cb = new ContextBuilder(dir);
        
        return cb.buildDot(noJdk, cycles, heatmap);
    }
    
    private static String runClasspath(Map<String, String> params) throws Exception {
        String filePath = params.get("filePath");
        
        if (filePath == null) return "{\"error\":\"filePath required\"}";
        
        Path dir = Paths.get(filePath);
        if (!Files.isDirectory(dir)) {
            dir = dir.getParent();
        }
        
        Path pom = dir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            return "{\"error\":\"No pom.xml found\"}";
        }
        
        String content = Files.readString(pom);
        Pattern pattern = Pattern.compile(
            "<dependency>.*?<groupId>(.*?)</groupId>.*?<artifactId>(.*?)</artifactId>.*?</dependency>",
            Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
        
        List<String> deps = new ArrayList<>();
        while (matcher.find()) {
            deps.add(matcher.group(1).trim() + ":" + matcher.group(2).trim());
        }
        
        return "{\"classpath\":" + deps + "}";
    }
    
    private static String runIndex(Map<String, String> params) throws Exception {
        String filePath = params.get("filePath");
        String method = params.get("method");
        
        if (filePath == null) return "{\"error\":\"filePath required\"}";
        
        Path dir = Paths.get(filePath);
        List<Path> dirs = List.of(dir);
        
        Index index = new Index(dirs);
        index.build();
        
        if (method != null) {
            return index.findMethod(method);
        } else {
            return index.summary();
        }
    }
    
    private static String runAst(Map<String, String> params) throws Exception {
        String filePath = params.get("filePath");
        
        if (filePath == null) return "{\"error\":\"filePath required\"}";
        
        Path dir = Paths.get(filePath).getParent();
        ContextBuilder cb = new ContextBuilder(dir);
        
        return cb.build(Paths.get(filePath), null);
    }
    
    private static String help() {
        return "{\"help\":{" +
            "\"methods\":[\"context\",\"calls\",\"callers\",\"impact\",\"dot\",\"classpath\",\"index\",\"ast\"]," +
            "\"example\":{\"method\":\"context\",\"params\":{\"filePath\":\"/path/to/File.java\",\"method\":\"main\"}}" +
            "}}";
    }
}