# 直接用 stdio 调 codescope（不用 AI）

适用场景：拿到工具结果时想**绕过 AI agent 那一层**，直接看原始 JSON，自己判断有没有"漏 caller"。

## 为什么走 stdio

codescope 的 MCP server 在 stdio 上跑 JSON-RPC 2.0。任何能写字节到 stdin、读字节到 stdout 的程序都能驱动它——**不需要 MCP host、不需要 LLM**。这一层 agent 可能做的"参数选错 / 自动 summarize / 截断大响应"全部跳过。

## 启动

```bash
mvn package
# 产出 target/codescope.jar,自带所有依赖
```

## 三步协议

### 1. `initialize`（任何 session 的第一条消息）

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}
```

`protocolVersion` / `clientInfo` 不校验，可填可不填。**不先发 initialize 后续请求可能异常**（MCP 协议要求 client 端声明能力）。

### 2. `tools/call`

`trace_callers`:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.example.Foo","method":"bar","project":"/abs/path/to/project"}}}
```

`find_call_sites`:

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_call_sites","arguments":{"class":"com.example.Foo","method":"bar","project":"/abs/path/to/project"}}}
```

`find_symbols`:

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find_symbols","arguments":{"query":"bar","project":"/abs/path/to/project","limit":50}}}
```

`project` 必须是**绝对路径**。重载方法补 `arity` 和 `paramTypes` 消歧。

### 3. 一行跑完

```bash
printf '%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.McpServer","method":"writeLine","project":"/home/ivan/codescope"}}}' \
  | java -jar target/codescope.jar \
  | jq -r 'select(.id==2) | .result.content[0].text | fromjson'
```

要点：
- 每个 JSON 对象占一行，**行尾必须换行**
- 关闭 stdin 后 server 退出（`McpServer.run` 读到 EOF 就停）
- 响应是 JSON-RPC envelope；用 `jq` 把 `result.content[0].text` 解出来——那个 `text` 字段**本身又是 JSON 字符串**

## 响应里要看的字面量

工具返回的 `message` 字段是 ground truth。几个关键字直接定位根因：

| 字面量 | 含义 |
|---|---|
| `OK; N caller(s) in chain.` | 成功，N 个 caller |
| `Truncated at 50000 nodes` | 调用图过大被截断（热门方法才触发） |
| `No callers found` | 项目里没人调这个方法 |
| `(skipped N unparseable file(s))` | N 个文件没 parse 成功（被静默丢的边） |
| `AmbiguousMethodException` | 重载未消歧，补 `arity` / `paramTypes` |
| `(combined callers across N library overloads: ...)` | target 是库方法，跨多个 overload union 出的 |

**AI 那层最容易吞的就是 `Truncated` 这个字面量**——一吞，看着就像"自然只有这些 caller"。

## 验证模式：和 AI 解读对照

排查"是不是 AI 漏报"的标准流程：

```bash
# 1. 拿 ground truth（你自己直接调出来的 raw JSON）
gt=$(printf '...' | java -jar target/codescope.jar | jq -S 'select(.id==2) | .result.content[0].text | fromjson')

# 2. 让 AI 解读同一个调用，把它的回答也存为 JSON
# 3. 手工 diff
diff <(echo "$gt" | jq -S '..') <(echo "$ai_answer" | jq -S '..')
```

如果 AI 解读**缺了**某个 `callers` 条目、**漏了** `cycle: true` 节点、**吞了** `Truncated` 字面量——**那是 AI 的漏报，不是工具的 bug**。

## 改完代码后的关键参数

索引是**进程级 LRU 缓存**，不会自动失效。改完源码后传 `refresh: true`：

```json
"arguments":{"class":"com.codescope.A","method":"x","project":"/abs","refresh":true}
```

不传 `refresh` 看到的是改之前的索引——这是"明明加了 caller 看不到"的第一号嫌疑。

## 故障排查

| 现象 | 原因 |
|---|---|
| server 启动没响应 | `printf` 没加 `\n`；检查 shell 单引号里没未转义的双引号 |
| 响应是 `Parse error` | JSON 不合法；用 `jq` 单独验一下请求字符串 |
| `Missing or non-string required argument: class` | `class` / `method` / `project` 漏了或不是 string |
| `Project root is not a directory` | `project` 路径错了，或不是 Maven 项目（缺 `pom.xml`） |
| `No pom.xml at ...` | 项目用 Gradle / Ant——只支持 Maven |
| 大量 `(skipped N unparseable file(s))` | 看 `target/codescope.jar` 启动时的 stderr，文件级 parse 失败原因在那里 |

## 高级用法

### 一个 session 里发多个独立查询

```bash
printf '%s\n%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.A","method":"x","project":"/abs"}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.B","method":"y","project":"/abs","refresh":true}}}' \
  | java -jar target/codescope.jar
```

每个响应以对应 `id` 标识，独立匹配。

### 进交互模式（持续 stdin）

```bash
mkfifo /tmp/cs-in
java -jar target/codescope.jar < /tmp/cs-in > /tmp/cs-out &
# 另一个终端：
exec 3>/tmp/cs-in
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}' >&3
# /tmp/cs-out 里看响应
```

适合需要多次重跑同一查询、加 `refresh: true` 反复验证的场景。

## 怎么从这套用法自然地接 AI

等你用 stdio 直接调过几次、建立了"工具原始输出长这样"的直觉后，**再让 AI 在原始 JSON 之上做总结**——这时候你能**直接发现 AI 漏了什么**。比直接信 AI 一层解读强得多。
