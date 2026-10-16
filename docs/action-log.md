# Action log

QuPath keeps no record of what is run in it, so the extension keeps one: every action the agent takes is written to a file, so you can check a session afterwards or redo it.

## What it records

Every MCP tool call, `/groovy` request and start-up script is appended as one JSON object per line to `mcp-<date>-<time>.jsonl`. Each QuPath session gets one file, created on its first action and written whatever **Show agent actions** is set to. A tool call is the smallest action the agent takes (it asks for a click on a named control rather than moving a pointer), so the log is complete at that grain.

One line, from a test session:

```json
{"time":"2026-10-10T20:32:14.139-05:00","source":"mcp","client":"Claude Code","tool":"qupath_set","args":{"control":"Size:","value":7.0},"window":"Cell detection","target":"set “Size:” to 7","ok":true,"ms":31,"result":"set Spinner Size: to 7\nNo new window opened."}
```

- `time`: when the call started, ISO 8601 with the local offset.
- `source`: `mcp` for a tool call, `http` for a `/groovy` request, `script` for a `-Dqupath.driver.script` file.
- `client`: the client's title from its MCP `initialize` request, or its name when it gives no title; the caller's address for a `/groovy` request; absent for a start-up script.
- `tool` and `args`: the tool and its arguments verbatim, so the Groovy source for `qupath_run_groovy`. A start-up script is logged as `script` with its `path` only, not its source.
- `window` and `target`: the title of the window the action landed in, and what the driver did there in the words of the on-screen marks: `click “Apply”`, `set “Size:” to 7`, `select “Nucleus”`, `tick “Image 1”`, `File ▸ Open URI...`, `key Enter`, `type “…”`, `close “Measurements”`, `open slide.svs`. A Groovy run that acts several times lists each, separated by `; `. Absent for a call that only reads.
- `ok` and `ms`: whether it succeeded, and how long it took in milliseconds.
- `result`: the text the tool returned, cut at 4000 characters; an image is replaced by a placeholder giving its type and encoded length. A failed call has `error` instead.
- `project` and `image`: the paths of the project and image that were open, when there were any.

The log holds the Groovy the agent ran and the text it read back, which can include file paths and anything shown in a dialog. Treat the folder like the project data it describes. On macOS and Linux the extension creates the folder and its files readable by you only.

The log is a record of what the agent did, not a tamper-proof audit trail: an agent that can run Groovy can also turn logging off or edit the files. The call that turns logging off is still recorded.

## Size

Most entries are under 1 KB. An entry that returns a long dialog outline can reach about 5 KB, since the returned text is cut at 4000 characters; the Groovy source of a `qupath_run_groovy` call is kept in full. A session of a few hundred tool calls writes roughly 0.2 to 2 MB.

Logs are kept until you delete them, because they are the record of past sessions. To bound them, set **Delete action logs after (days)**: when a session starts logging, older logs are deleted. When the logs in the folder add up to more than **Warn when action logs exceed (MB)**, QuPath shows a notification and the agent's next successful tool call carries a one-line note asking it to tell you. Both run when a session opens its log file, and again if the folder preference changes. Only the extension's own logs, named like `mcp-20261016-143723.jsonl`, are counted or deleted; other files in the folder are left alone.

## Preferences

Under **Edit ▸ Preferences ▸ MCP server**:

| Preference | Default | |
|---|---|---|
| **Log agent actions** | on | Turns the log off. |
| **Action log folder** | empty | Where the logs go; empty means `mcp-logs` in QuPath's user directory. A change applies from the next action. |
| **Delete action logs after (days)** | 0 | Deletes the extension's logs older than this when a session starts logging; 0 keeps them all. |
| **Warn when action logs exceed (MB)** | 1024 | Total size of the logs that triggers the warning; 0 never warns. |

## Redoing a session

There is no replay command. An entry has what you need to redo the call by hand, and the `code` of a `qupath_run_groovy` entry can be sent back through that tool as it is. A start-up script can be rerun only while its file is unchanged.
