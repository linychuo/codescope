package com.codescope;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class McpServer {
    private static final String VERSION = "1.0.0";
    private AnalysisEngine engine;
    private final Map<String, Tool> tools = new HashMap<>();

    public static void main(String[] args) throws Exception {
        McpServer server = new McpServer();
        server.init();
        server.run();
    }

    public void init() throws Exception {
        Path cwd = Path.of(System.getProperty("user.dir"));
        this.engine = new AnalysisEngine(cwd);
        initTools();
    }

    private void initTools() {
        tools.put("context", new Tool("context", "获取类/方法的语义上下文",
            List.of(new ToolArg("filePath", "string", true, "Java文件路径或类名"),
                    new ToolArg("methodName", "string", false, "方法名（可选）"))));

        tools.put("calls", new Tool("calls", "查看方法调用的其他方法 (callees)",
            List.of(new ToolArg("filePath", "string", true, "Java文件路径或类名"),
                    new ToolArg("methodName", "string", true, "方法名"))));

        tools.put("callers", new Tool("callers", "查找调用某方法的所有位置",
            List.of(new ToolArg("filePath", "string", true, "Java文件路径或类名"),
                    new ToolArg("methodName", "string", true, "方法名"))));

        tools.put("impact", new Tool("impact", "分析方法影响范围 (文本格式)",
            List.of(new ToolArg("filePath", "string", true, "Java文件路径或类名"),
                    new ToolArg("methodName", "string", true, "方法名"))));

        tools.put("impact_dot", new Tool("impact_dot", "生成方法影响范围的 DOT 调用图",
            List.of(new ToolArg("filePath", "string", true, "Java文件路径或类名"),
                    new ToolArg("methodName", "string", true, "方法名"))));

        tools.put("dot", new Tool("dot", "生成完整调用图",
            List.of(new ToolArg("filePath", "string", true, "目录或文件路径"),
                    new ToolArg("noJdk", "boolean", false, "排除JDK调用"),
                    new ToolArg("cycles", "boolean", false, "检测循环"),
                    new ToolArg("heatmap", "boolean", false, "显示热度"))));

        tools.put("ast", new Tool("ast", "显示 AST 结构",
            List.of(new ToolArg("filePath", "string", true, "Java文件路径或类名"))));

        tools.put("index", new Tool("index", "构建项目索引",
            List.of(new ToolArg("filePath", "string", true, "目录路径"),
                    new ToolArg("methodName", "string", false, "搜索方法名"))));

        tools.put("classpath", new Tool("classpath", "显示 Maven 依赖",
            List.of(new ToolArg("dir", "string", true, "项目目录"))));
    }

    public void run() throws Exception {
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line.isEmpty()) continue;

            try {
                String response = handleRequest(line);
                System.out.println(response);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.flush();
        }
    }

    private String handleRequest(String line) throws Exception {
        String method = extractMethod(line);
        String id = extractId(line);

        if ("initialize".equals(method)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"codescope\",\"version\":\"" + VERSION + "\"}}}";
        }

        if ("tools/list".equals(method)) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id).append(",\"result\":{\"tools\":[");
            boolean first = true;
            for (Tool tool : tools.values()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(tool.toJson());
            }
            sb.append("]}}");
            return sb.toString();
        }

        if ("tools/call".equals(method)) {
            return handleToolCall(line, id);
        }

        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"Method not found: " + method + "\"}}";
    }

    private String handleToolCall(String line, String id) throws Exception {
        String toolName = extractToolName(line);
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Error: Tool not found: " + toolName + "\"}]}}";
        }

        Map<String, Object> args = extractArgs(line, tool);

        String content;
        try {
            content = executeTool(toolName, args);
        } catch (Exception e) {
            content = "Error: " + e.getMessage();
        }

        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(content) + "\"}]}}";
    }

    private String executeTool(String toolName, Map<String, Object> args) throws Exception {
        return switch (toolName) {
            case "context" -> {
                String filePath = (String) args.get("filePath");
                String methodName = (String) args.get("methodName");
                Path sourceFile = resolveFile(filePath);
                yield engine.buildContext(sourceFile, methodName);
            }
            case "calls" -> {
                String filePath = (String) args.get("filePath");
                String methodName = (String) args.get("methodName");
                Path sourceFile = resolveFile(filePath);
                yield CommandHandler.buildCalls(engine, sourceFile, methodName);
            }
            case "callers" -> {
                String filePath = (String) args.get("filePath");
                String methodName = (String) args.get("methodName");
                Path sourceFile = resolveFile(filePath);
                yield CommandHandler.buildCallers(engine, sourceFile, methodName);
            }
            case "impact" -> {
                String filePath = (String) args.get("filePath");
                String methodName = (String) args.get("methodName");
                Path sourceFile = resolveFile(filePath);
                yield CommandHandler.buildImpact(sourceFile, methodName);
            }
            case "impact_dot" -> {
                String filePath = (String) args.get("filePath");
                String methodName = (String) args.get("methodName");
                Path sourceFile = resolveFile(filePath);
                yield CommandHandler.buildImpactDot(sourceFile, methodName);
            }
            case "dot" -> {
                String filePath = (String) args.get("filePath");
                boolean noJdk = Boolean.TRUE.equals(args.get("noJdk"));
                boolean cycles = Boolean.TRUE.equals(args.get("cycles"));
                boolean heatmap = Boolean.TRUE.equals(args.get("heatmap"));
                Path path = Path.of(filePath);
                AnalysisEngine dotEngine = new AnalysisEngine(path);
                yield dotEngine.buildDot(noJdk, cycles, heatmap);
            }
            case "ast" -> {
                String filePath = (String) args.get("filePath");
                Path sourceFile = resolveFile(filePath);
                yield CommandHandler.buildAst(engine, sourceFile);
            }
            case "index" -> {
                String filePath = (String) args.get("filePath");
                String methodName = (String) args.get("methodName");
                List<Path> dirs = List.of(Path.of(filePath));
                Index index = new Index(dirs);
                index.build();
                yield methodName != null ? index.findMethod(methodName) : index.summary();
            }
            case "classpath" -> {
                String dir = (String) args.get("dir");
                yield CommandHandler.buildClasspath(Path.of(dir));
            }
            default -> "Error: Unknown tool";
        };
    }

    private Path resolveFile(String input) throws Exception {
        Path path = Path.of(input).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            return path;
        }

        if (!input.contains("/")) {
            List<Path> dirs = List.of(Path.of(".").toAbsolutePath());
            Index index = new Index(dirs);
            index.build();
            List<Path> found = index.findClass(input);
            if (!found.isEmpty()) {
                return found.get(0).toAbsolutePath().normalize();
            }
        }

        throw new Exception("File not found: " + input);
    }

    private String extractMethod(String line) {
        Pattern p = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1) : "";
    }

    private String extractId(String line) {
        Pattern p = Pattern.compile("\"id\"\\s*:\\s*(\\d+|null)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            String id = m.group(1);
            return id.equals("null") ? "null" : "\"" + id + "\"";
        }
        return "null";
    }

    private String extractToolName(String line) {
        Pattern p = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    private Map<String, Object> extractArgs(String line, Tool tool) {
        Map<String, Object> args = new HashMap<>();
        Pattern p = Pattern.compile("\"arguments\"\\s*:\\s*\\{([^}]*)\\}");
        Matcher m = p.matcher(line);
        if (m.find()) {
            String argsStr = m.group(1);
            for (ToolArg arg : tool.args) {
                Pattern argP = Pattern.compile("\"" + arg.name + "\"\\s*:\\s*(?:\"([^\"]*)\"|(\\d+)|(true|false)|null)");
                Matcher argM = argP.matcher(argsStr);
                if (argM.find()) {
                    if (argM.group(1) != null) {
                        args.put(arg.name, argM.group(1));
                    } else if (argM.group(2) != null) {
                        args.put(arg.name, Integer.parseInt(argM.group(2)));
                    } else if (argM.group(3) != null) {
                        args.put(arg.name, Boolean.parseBoolean(argM.group(3)));
                    }
                }
            }
        }
        return args;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static class Tool {
        String name;
        String description;
        List<ToolArg> args;

        Tool(String name, String description, List<ToolArg> args) {
            this.name = name;
            this.description = description;
            this.args = args;
        }

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"name\":\"").append(name).append("\",\"description\":\"").append(description.replace("\"", "\\\""))
              .append("\",\"inputSchema\":{\"type\":\"object\",\"properties\":{");
            StringBuilder props = new StringBuilder();
            List<String> required = new ArrayList<>();
            for (ToolArg arg : args) {
                if (!props.isEmpty()) props.append(",");
                props.append("\"").append(arg.name).append("\":{\"type\":\"")
                     .append(arg.type).append("\",\"description\":\"")
                     .append(arg.description.replace("\"", "\\\"")).append("\"}");
                if (arg.required) required.add(arg.name);
            }
            sb.append(props).append("}");
            if (!required.isEmpty()) {
                sb.append(",\"required\":[");
                sb.append(required.stream().map(r -> "\"" + r + "\"").reduce((a, b) -> a + "," + b).orElse(""));
                sb.append("]");
            }
            sb.append("}}");
            return sb.toString();
        }
    }

    private static class ToolArg {
        String name;
        String type;
        boolean required;
        String description;

        ToolArg(String name, String type, boolean required, String description) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.description = description;
        }
    }
}