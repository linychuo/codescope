package com.codescope;

import com.codescope.cli.Cli;
import com.codescope.index.ProjectIndexCache;
import com.codescope.mcp.FindCallSitesTool;
import com.codescope.mcp.FindSymbolsTool;
import com.codescope.mcp.McpServer;
import com.codescope.mcp.TraceCalleesTool;
import com.codescope.mcp.TraceCallersTool;

/** Entry point: starts the MCP server on stdio, or the CLI front-end if args are present. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            // CLI mode: bypass JSON-RPC, exit when done. Cli.run returns
            // the exit code; Cli.main wraps it with System.exit.
            Cli.main(args);
            return;
        }
        ProjectIndexCache shared = new ProjectIndexCache();
        TraceCallersTool traceCallers = new TraceCallersTool(shared);
        TraceCalleesTool traceCallees = new TraceCalleesTool(shared);
        FindCallSitesTool findCallSites = new FindCallSitesTool(shared);
        FindSymbolsTool findSymbols = new FindSymbolsTool(shared);
        McpServer server = new McpServer(shared)
                .register(traceCallers)
                .register(traceCallees)
                .register(findCallSites)
                .register(findSymbols);

        // The server populates its defaultProjectRoot asynchronously from the host's
        // MCP `roots` after `initialize`. Wire it into all tools so `project` is optional.
        // onDefaultProjectRoot accepts a single sink and overwrites on each call, so we
        // fan out to every tool in one composed consumer.
        server.onDefaultProjectRoot(path -> {
            traceCallers.setHostDefaultProject(path);
            traceCallees.setHostDefaultProject(path);
            findCallSites.setHostDefaultProject(path);
            findSymbols.setHostDefaultProject(path);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "codescope-shutdown"));

        server.run();
    }
}
