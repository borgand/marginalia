#!/usr/bin/env bash
# Marginalia PreToolUse hook — denies the agent's native Edit/Write on files that are
# live co-edited in IntelliJ, redirecting it to mcp__marginalia__apply_edit (PRD F4).
#
# Installed by the IntelliJ plugin (Tools > Marginalia: Install Claude Code Integration).
# Requires jq; without it the hook allows everything (fail-open — the plugin's VFS
# fallback still protects the buffer).

set -u

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
[ -z "$file" ] && exit 0

docs="$HOME/.marginalia/active-docs.json"
[ -f "$docs" ] || exit 0

if jq -e --arg f "$file" '.docs | index($f)' "$docs" >/dev/null 2>&1; then
  echo "This file is live co-edited in IntelliJ. Do not use Edit/Write on it. Use mcp__marginalia__read_doc to get the current buffer + version, then mcp__marginalia__apply_edit." >&2
  exit 2
fi

exit 0
