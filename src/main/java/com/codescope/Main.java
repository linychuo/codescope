package com.codescope;

/** Entry point: starts the MCP server on stdio. */
public final class Main {
    public static void main(String[] args) throws Exception {
        McpServer server = new McpServer()
                .register(new TraceCallersTool());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.run();
    }
}
