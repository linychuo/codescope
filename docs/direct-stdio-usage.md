# 直接用 stdio 调 codescope（不用 AI）

适用场景：拿到工具结果时想**绕过 AI agent 那一层**，直接看原始 JSON，自己判断有没有"漏 caller"。

跨平台：本文档的协议部分与平台无关；具体命令给出 **bash**（Linux / macOS / Git Bash / WSL）和 **PowerShell 5.1+**（Windows 原生）两个版本。

> **更简单的替代：`java -jar codescope.jar <command> [options]`** —— 不走 JSON-RPC，
> 直接 stdout 出一段 pretty-printed JSON，退出码 0/1/2。子命令有 `trace-callers` /
> `find-call-sites` / `find-symbols`，`--help` 看完整选项。
>
> ```bash
> java -jar target/codescope.jar trace-callers com.example.Foo bar --project C:/projects/foo
> ```
>
> 输出 envelope 和 MCP 工具一字不差。需要"看一次工具原始长什么样"的话优先用这个。
> 本文档剩下的部分是 stdio / JSON-RPC 路径，**仅在写 MCP host 客户端 / 想跑半交互
> session 时才需要看**。

## 启动

```bash
# 任何平台，前提是 JDK 21+ 和 Maven 在 PATH 上
mvn package
# 产出 target/codescope.jar
```

## 三步协议

### 1. `initialize`（任何 session 的第一条消息）

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}
```

`protocolVersion` / `clientInfo` 不校验。

### 2. `tools/call`

`trace_callers`:
```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.example.Foo","method":"bar","project":"C:/projects/codescope"}}}
```

`find_call_sites`:
```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_call_sites","arguments":{"class":"com.example.Foo","method":"bar","project":"C:/projects/codescope"}}}
```

`find_symbols`:
```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find_symbols","arguments":{"query":"bar","project":"C:/projects/codescope","limit":50}}}
```

`project` 必须是**绝对路径**。**用正斜杠 `C:/projects/...` 即可**——Java 在 Windows 上两种分隔符都接受，避免在 JSON 字符串里写反斜杠转义。

重载方法补 `arity` 和 `paramTypes` 消歧。

### 3. 一行跑完

**bash / Git Bash / WSL:**

```bash
printf '%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.McpServer","method":"writeLine","project":"C:/projects/codescope"}}}' \
  | java -jar target/codescope.jar \
  | jq -r 'select(.id==2) | .result.content[0].text | fromjson'
```

**PowerShell 5.1+（Windows 原生）:**

```powershell
$req = @'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.McpServer","method":"writeLine","project":"C:/projects/codescope"}}}
'@
$req | java -jar target/codescope.jar | jq -r 'select(.id==2) | .result.content[0].text | fromjson'
```

要点：
- 每个 JSON 对象占一行，**行尾必须换行**
- 关闭 stdin 后 server 退出（`McpServer.run` 读到 EOF 就停）
- 响应是 JSON-RPC envelope；用 `jq` 把 `result.content[0].text` 解出来——那个 `text` 字段**本身又是 JSON 字符串**
- PowerShell 的 here-string `@'...'@`（单引号）不做变量插值，反斜杠也按字面值处理，最适合放 JSON
- PowerShell 默认 CRLF 换行，server 的分帧逻辑（`McpServer.run`）把 `\n` `\r` 都当 framing whitespace 跳过，**不需要切到 LF**

### Windows 上装 `jq`

`java` 一般已有。`jq` 装一个：

```powershell
# 任选一种
winget install jqlang.jq
choco install jq
scoop install jq

# 或者直接下二进制丢到 PATH：
# https://stedolan.github.io/jq/download/
```

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

排查"是不是 AI 漏报"的标准流程。

**bash:**

```bash
# 1. 拿 ground truth
gt=$(printf '...' | java -jar target/codescope.jar | jq -S 'select(.id==2) | .result.content[0].text | fromjson')

# 2. 让 AI 解读同一个调用
# 3. 手工 diff
diff <(echo "$gt" | jq -S '..') <(echo "$ai_answer" | jq -S '..')
```

**PowerShell:**

```powershell
# 1. 拿 ground truth
$req | java -jar target/codescope.jar | jq -r 'select(.id==2) | .result.content[0].text | fromjson' | Set-Content gt.json

# 2. 让 AI 解读同一个调用，把它写的存为 ai.json
# 3. 手工 diff（任选一种）
Compare-Object (Get-Content gt.json) (Get-Content ai.json)
git diff --no-index gt.json ai.json   # 前提是装了 git
```

如果 AI 解读**缺了**某个 `callers` 条目、**漏了** `cycle: true` 节点、**吞了** `Truncated` 字面量——**那是 AI 的漏报，不是工具的 bug**。

## 改完代码后的关键参数

索引是**进程级 LRU 缓存**，不会自动失效。改完源码后传 `refresh: true`：

```json
"arguments":{"class":"com.codescope.A","method":"x","project":"C:/projects/codescope","refresh":true}
```

不传 `refresh` 看到的是改之前的索引——这是"明明加了 caller 看不到"的第一号嫌疑。

## 故障排查

| 现象 | 原因 / 修法 |
|---|---|
| server 启动没响应 | bash：检查 `printf '%s\n'` 别漏了 `\n`；PowerShell：检查 here-string 头尾是 `@'`  `'@`（单引号） |
| 响应是 `Parse error` | JSON 不合法；用 `jq` 单独验下请求字符串 |
| `Missing or non-string required argument: class` | `class` / `method` / `project` 漏了或不是 string |
| `Project root is not a directory` | `project` 路径错了 |
| `No pom.xml at ...` | 项目用 Gradle / Ant——只支持 Maven |
| 大量 `(skipped N unparseable file(s))` | 看 `java -jar` 启动时 stderr 的 `parse error:` / `read error:` |
| PowerShell: `术语 'jq' 不是 ...` | `jq` 没装或没在 PATH，按上面"装 jq"那一节处理 |
| PowerShell: JSON 里的 `\` 被吃了 | 用 `@'...'@` 单引号 here-string；双引号 `"..."@` 会做变量插值和反斜杠转义 |
| PowerShell: 反斜杠路径报"非法转义" | 改用正斜杠 `C:/projects/...`——JSON 和 Java 都吃 |
| Windows 控制台输出乱码 | `chcp 65001` 切到 UTF-8 代码页（一次性，不持久） |

## 高级用法

### 一个 session 里发多个独立查询

**bash:**

```bash
printf '%s\n%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.A","method":"x","project":"C:/projects/codescope"}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.B","method":"y","project":"C:/projects/codescope","refresh":true}}}' \
  | java -jar target/codescope.jar
```

**PowerShell:**

```powershell
$req = @'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.A","method":"x","project":"C:/projects/codescope"}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"trace_callers","arguments":{"class":"com.codescope.B","method":"y","project":"C:/projects/codescope","refresh":true}}}
'@
$req | java -jar target/codescope.jar
```

每个响应以对应 `id` 标识，独立匹配。

### 半交互模式（用文件做"管道"）

需要反复加请求 / 看响应、又不想每次重起 server 时用。

**bash（用 `mkfifo`，Git Bash / WSL 可用）:**

```bash
mkfifo /tmp/cs-in
java -jar target/codescope.jar < /tmp/cs-in > /tmp/cs-out &
# 另一个终端：
exec 3>/tmp/cs-in
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}' >&3
# /tmp/cs-out 里看响应
```

**PowerShell（用文件 + `Get-Content -Wait`，Windows 原生，不依赖 mkfifo）:**

```powershell
# 终端 A：起 server
'' | Set-Content requests.txt
Get-Content requests.txt -Wait | java -jar target/codescope.jar > responses.txt

# 终端 B：追加请求
Add-Content requests.txt '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}'
# 终端 A 或 Get-Content responses.txt -Wait 看响应
```

## 怎么从这套用法自然地接 AI

等你用 stdio 直接调过几次、建立了"工具原始输出长这样"的直觉后，**再让 AI 在原始 JSON 之上做总结**——这时候你能**直接发现 AI 漏了什么**。比直接信 AI 一层解读强得多。
