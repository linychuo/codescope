package com.codescope;

/** Entry point: starts the MCP server on stdio, or the CLI front-end if args are present. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            // CLI mode: bypass JSON-RPC, exit when done. Cli.run returns
            // the exit code; Cli.main wraps it with System.exit.
            Cli.main(args);
            return;
        }
        TraceCallersTool traceCallers = new TraceCallersTool();
        FindCallSitesTool findCallSites = new FindCallSitesTool();
        FindSymbolsTool findSymbols = new FindSymbolsTool();
        McpServer server = new McpServer()
                .register(traceCallers)
                .register(findCallSites)
                .register(findSymbols);

        // The server populates its defaultProjectRoot asynchronously from the host's
        // MCP `roots` after `initialize`. Wire it into all tools so `project` is optional.
        // onDefaultProjectRoot accepts a single sink and overwrites on each call, so we
        // fan out to every tool in one composed consumer.
        server.onDefaultProjectRoot(path -> {
            traceCallers.setHostDefaultProject(path);
            findCallSites.setHostDefaultProject(path);
            findSymbols.setHostDefaultProject(path);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "codescope-shutdown"));

        server.run();
    }
}
