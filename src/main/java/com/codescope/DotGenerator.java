package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Generates Graphviz DOT format output from call graphs.
 * Supports filtering (--no-jdk), cycle detection (--cycles), and heatmap (--heatmap).
 * @param models list of project models
 * @param noJdk exclude JDK method calls
 * @param cycles detect call cycles
 * @param heatmap show call frequency heatmap
 */
public class DotGenerator {

    public static String generate(List<ProjectModel> models, boolean noJdk, boolean cycles, boolean heatmap) {
        Map<String, Integer> edgeCounts = new HashMap<>();
        Set<String> jdkNodes = new HashSet<>();

        for (ProjectModel model : models) {
            for (Path file : model.getFiles()) {
                CompilationUnit cu = model.getAst(file);
                if (cu == null) continue;

                CallGraph cg = new CallGraph(file, model);
                for (Map.Entry<String, Set<CallGraph.CallSite>> entry : cg.callSites.entrySet()) {
                    String caller = entry.getKey();
                    for (CallGraph.CallSite cs : entry.getValue()) {
                        String callee = cs.resolved.isEmpty() ? caller.substring(0, caller.lastIndexOf('.')) + "." + cs.method : cs.resolved;

                        if (noJdk && (callee.startsWith("java.") || callee.contains(".java."))) {
                            jdkNodes.add(caller);
                            continue;
                        }

                        String edge = caller + "->" + callee;
                        edgeCounts.merge(edge, 1, Integer::sum);
                    }
                }
            }
        }

        Set<String> filteredEdges = new HashSet<>();
        if (noJdk) {
            for (String edge : edgeCounts.keySet()) {
                String caller = edge.substring(0, edge.indexOf("->"));
                if (!jdkNodes.contains(caller)) {
                    filteredEdges.add(edge);
                }
            }
        } else {
            filteredEdges = edgeCounts.keySet();
        }

        Set<String> allNodes = new HashSet<>();
        Set<String> allEdges = new TreeSet<>();
        for (String edge : filteredEdges) {
            String[] parts = edge.split("->");
            allNodes.add(parts[0]);
            allNodes.add(parts[1]);
            allEdges.add(edge);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("digraph callgraph {\n");
        sb.append("  rankdir=LR;\n");

        if (heatmap) {
            sb.append("  node [shape=box, style=filled];\n");
            int maxCount = edgeCounts.values().stream().max(Integer::compareTo).orElse(1);
            for (Map.Entry<String, Integer> e : edgeCounts.entrySet()) {
                String[] parts = e.getKey().split("->");
                double ratio = (double) e.getValue() / maxCount;
                String color = getHeatColor(ratio);
                sb.append("  \"").append(CallGraph.escapeDot(parts[0])).append("\" [fillcolor=").append(color).append(", color=black];\n");
            }
        } else {
            sb.append("  node [shape=box];\n");
        }
        sb.append("\n");

        for (String node : allNodes) {
            sb.append("  \"").append(CallGraph.escapeDot(node)).append("\";\n");
        }
        sb.append("\n");

        Set<String> cycleEdges = cycles ? detectCycles(allNodes, allEdges) : Collections.emptySet();
        for (String edge : allEdges) {
            String[] parts = edge.split("->");
            String style = cycleEdges.contains(edge) ? " [style=bold, color=red]" : "";
            sb.append("  \"").append(CallGraph.escapeDot(parts[0])).append("\" -> \"")
              .append(CallGraph.escapeDot(parts[1])).append("\"").append(style).append(";\n");
        }

        if (cycles && !cycleEdges.isEmpty()) {
            sb.append("\n  // Cycles detected:\n");
            for (String ce : cycleEdges) {
                sb.append("  // ").append(ce.replace("->", " -> ")).append("\n");
            }
        }

        if (heatmap) {
            sb.append("\n  // Call counts:\n");
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(edgeCounts.entrySet());
            sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            for (var e : sorted) {
                if (filteredEdges.contains(e.getKey())) {
                    sb.append("  // ").append(e.getKey().replace("->", " -> ")).append(": ").append(e.getValue()).append("\n");
                }
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static String getHeatColor(double ratio) {
        int r = (int) (255 * ratio);
        int b = (int) (255 * (1 - ratio));
        return "\"#" + String.format("%02x%02x%02x", r, 0, b) + "\"";
    }

    private static Set<String> detectCycles(Set<String> nodes, Set<String> edges) {
        Set<String> cycleEdges = new HashSet<>();
        Map<String, List<String>> adj = new HashMap<>();

        for (String edge : edges) {
            String[] parts = edge.split("->");
            adj.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
        }

        Set<String> visited = new HashSet<>();
        Set<String> recursion = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();

        for (String node : nodes) {
            if (detectCycle(node, adj, visited, recursion, path, cycleEdges)) {
                break;
            }
        }
        return cycleEdges;
    }

    private static boolean detectCycle(String node, Map<String, List<String>> adj, 
            Set<String> visited, Set<String> recursion, 
            Deque<String> path, Set<String> cycleEdges) {
        visited.add(node);
        recursion.add(node);
        path.push(node);

        for (String next : adj.getOrDefault(node, Collections.emptyList())) {
            if (!visited.contains(next)) {
                if (detectCycle(next, adj, visited, recursion, path, cycleEdges)) {
                    return true;
                }
            } else if (recursion.contains(next)) {
                return true;
            }
        }

        path.pop();
        recursion.remove(node);
        return false;
    }
}