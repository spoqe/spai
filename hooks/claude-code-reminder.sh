#!/bin/bash
# spai reminder hook for Claude Code
#
# Nudges Claude toward spai MCP tools when it uses raw Grep/Glob/Bash
# for code exploration. These are the tools Claude ACTUALLY uses —
# not shell grep/find (which Claude rarely invokes directly).
#
# Triggers on: Grep, Glob, Bash (grep/find/sed/awk)
# Installed by: spai setup --claude-hooks (or ./install.sh)
# Remove by deleting the PreToolUse entries from ~/.claude/settings.json

INPUT=$(cat)

# Extract tool name, parameters, and Claude session id from the hook input
TOOL_NAME=""
COMMAND=""
PATTERN=""
SESSION_ID=""

if echo "$INPUT" | grep -q '"tool_name"'; then
    TOOL_NAME=$(echo "$INPUT" | sed -n 's/.*"tool_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
fi
if echo "$INPUT" | grep -q '"command"'; then
    COMMAND=$(echo "$INPUT" | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
fi
if echo "$INPUT" | grep -q '"pattern"'; then
    PATTERN=$(echo "$INPUT" | sed -n 's/.*"pattern"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
fi
if echo "$INPUT" | grep -q '"session_id"'; then
    SESSION_ID=$(echo "$INPUT" | sed -n 's/.*"session_id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
fi

# Skip if already using spai
echo "$COMMAND$PATTERN" | grep -q "spai" && exit 0

# --- Session tracking ---
# Use Claude's session_id so counts persist across hook invocations in the same session.
# Fall back to parent PID (the Claude process) if session_id isn't provided.
TRACK_DIR="/tmp/spai-hooks"
mkdir -p "$TRACK_DIR"
SESSION_KEY="${SESSION_ID:-$(ps -o ppid= -p $$ 2>/dev/null | tr -d ' ')}"
SESSION_FILE="$TRACK_DIR/session-$SESSION_KEY"
COUNT=0
[ -f "$SESSION_FILE" ] && COUNT=$(cat "$SESSION_FILE")

# Detect code exploration patterns by tool type
TRIGGER=false

case "$TOOL_NAME" in
    Grep)
        # Claude is using the Grep MCP tool for code exploration
        if echo "$PATTERN" | grep -qEi "(fn |pub |impl |struct |enum |trait |class |def |function |import |use |mod |require)"; then
            TRIGGER=true
        fi
        ;;
    Glob)
        # Claude is using the Glob MCP tool to find files
        TRIGGER=true
        ;;
    Bash|"")
        # Bash tool or raw input — check for grep/find/sed/awk
        if echo "$COMMAND" | grep -qE "(grep|rg|find|sed|awk)" 2>/dev/null; then
            TRIGGER=true
        fi
        ;;
esac

$TRIGGER || exit 0

COUNT=$((COUNT + 1))
echo "$COUNT" > "$SESSION_FILE"

# --- Escalating responses ---
if [ "$COUNT" -ge 5 ]; then
    cat >&2 << EOF

Search #$COUNT this session. You have spai recon — ONE call for full context:

  spai recon <symbol>   # blast + context + shape + memory in one call
  spai recon <file>     # who + related + shape + memory in one call

If you're repeating this pattern, make a plugin: spai new-plugin <name>

EOF
elif [ "$COUNT" -ge 3 ]; then
    cat >&2 << EOF

Search #$COUNT. Consider spai recon <symbol-or-file> for full context in one call.

EOF
else
    cat >&2 << 'EOF'

spai has dedicated tools for this:

  spai recon <sym|file>    # full situational awareness in one call
  spai shape <path>        # module structure (fns, types, impls)
  spai blast <symbol>      # blast radius (callers, importers, tests, risk)
  spai context <symbol>    # usages WITH enclosing function names
  spai who <file>          # reverse deps

EOF
fi

exit 0
