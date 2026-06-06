package com.codescope;

/** Entry point: starts the MCP server on stdio. */
public final class Main {
    public static void main(String[] args) throws Exception {
        TraceCallersTool tool = new TraceCallersTool();
        McpServer server = new McpServer().register(tool);

        // The server populates its defaultProjectRoot asynchronously from the host's
        // MCP `roots` after `initialize`. Wire it into the tool so `project` is optional.
        server.onDefaultProjectRoot(tool::setHostDefaultProject);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.run();
    }
}
