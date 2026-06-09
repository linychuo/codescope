package com.codescope;

/** Entry point: starts the MCP server on stdio. */
public final class Main {
    public static void main(String[] args) throws Exception {
        TraceCallersTool traceCallers = new TraceCallersTool();
        FindCallSitesTool findCallSites = new FindCallSitesTool();
        McpServer server = new McpServer()
                .register(traceCallers)
                .register(findCallSites);

        // The server populates its defaultProjectRoot asynchronously from the host's
        // MCP `roots` after `initialize`. Wire it into both tools so `project` is optional.
        // onDefaultProjectRoot accepts a single sink and overwrites on each call, so we
        // fan out to both tools in one composed consumer.
        server.onDefaultProjectRoot(path -> {
            traceCallers.setHostDefaultProject(path);
            findCallSites.setHostDefaultProject(path);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "codescope-shutdown"));

        server.run();
    }
}
