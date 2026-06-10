# Audit: missing-caller sources in non-analysis layers

**Date:** 2026-06-10
**Trigger:** Reports of incomplete `trace_callers` results in production.
**Scope:** Every layer between the AST indexer and the JSON-RPC transport.

## Layers audited

| File | Role | Result |
|---|---|---|
| `McpServer.java` | JSON-RPC framing, dispatch, error mapping | clean |
| `Main.java` | Entry point, tool wiring | clean |
| `Tool.java` | Tool interface contract | clean |
| `TraceCallersTool.java` | `trace_callers` arg parsing & dispatch | clean |
| `FindCallSitesTool.java` | `find_call_sites` arg parsing & dispatch | clean |
| `FindSymbolsTool.java` | `find_symbols` arg parsing & dispatch | clean |
| `TraceCallersService.java` | `trace_callers` orchestration | clean |
| `FindCallSitesService.java` | `find_call_sites` orchestration | clean |
| `FindSymbolsService.java` | `find_symbols` orchestration | clean |
| `MavenSettings.java` | `~/.m2/settings.xml` parsing | clean |

## What was checked

- **Serialization truncation.** Jackson `maxNestingDepth(50_000)` on all
  three service mappers; no result-array limit on the call-tree path.
  The full tree is preserved end-to-end.
- **stdio framing.** `tryParseAndDispatch` + `findObjectEnd` distinguish
  streaming-incomplete from structural error correctly; `writeLine`
  flushes after every response. No buffered response can lose its tail.
- **Result fidelity.** Tool results flow as `{"type":"text","text":"<json>"}`
  with no field filtering or sampling. `ToolResult.error` surfaces as
  `isError=true`, never as an empty `content`.
- **Argument validation.** `*Tool` adapters throw `IllegalArgumentException`
  on bad input; translated to "Invalid arguments" by `McpServer.invokeTool`,
  not silently dropped.
- **Cache safety.** LRU eviction + `refresh=true` ordering
  (`remove` before `computeIfAbsent`) prevent stale reads on concurrent
  calls. Capacity 8 bounds memory.
- **Settings parser.** `MavenSettings` disables XXE, external entities, and
  parameter entities; malformed `settings.xml` falls back to the default
  `~/.m2/repository` rather than throwing.

## Known minor gap (not blocking)

`skippedFiles` is exposed only as a **count** in the `message` field
(`"(skipped N unparseable file(s))"`), not as a list of `(file, reason)`
pairs in the response envelope. A one-line addition to the service
output would surface the per-file reasons; left as-is to keep the
envelope compact.

## Conclusion

**No additional bugs found** in these layers that would cause
"missing caller" symptoms. The real code-level causes of incomplete
results remain the analysis-layer limitations identified earlier:

- JdtIndexer: field-initializer calls and `static {}` / instance
  initializer block calls are silently dropped (the visitor's
  `methodStack` is empty outside `MethodDeclaration`).
- `ProjectLoader`: source-root detection matches `src/<X>/main/java`
  only; custom `<sourceDirectory>` in `pom.xml` is ignored.
- `CallChainAnalyzer`: `MAX_NODES = 50_000` truncates hot methods;
  message contains `"Truncated at 50000 nodes"` when this fires.
- The encoding bug fixed in `3a8f631` (JDT sourcepath reads using the
  JVM default charset while the file was read as UTF-8).

If "missing caller" symptoms persist after ruling out the analysis-layer
causes, the remaining suspects are (a) the LLM orchestration layer in
front of this MCP server, and (b) project-specific patterns outside
static analysis (reflection, Spring AOP, generated code).
