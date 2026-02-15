#!/bin/bash
# spai reminder hook for Claude Code
#
# Detects when Claude uses grep/find for code exploration
# and nudges toward spai instead.
#
# Installed by: spai install --claude-hooks
# Remove by deleting the hook entry from ~/.claude/settings.json

# Read hook input from stdin (tool parameters as JSON)
INPUT=$(cat)

# Extract command — try JSON first, fall back to raw input
COMMAND=""
if echo "$INPUT" | grep -q '"command"'; then
    COMMAND=$(echo "$INPUT" | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
fi
[ -z "$COMMAND" ] && COMMAND="$INPUT"
[ -z "$COMMAND" ] && exit 0

# Skip if already using spai
echo "$COMMAND" | grep -q "spai" && exit 0

# Detect code exploration patterns
if echo "$COMMAND" | grep -qE "(grep.*-r|grep.*fn |grep.*pub |grep.*impl |grep.*struct |grep.*class |grep.*def |grep.*function|grep.*use |grep.*import|grep.*::|find.*\.(rs|py|js|ts|clj))"; then
    cat >&2 << 'EOF'

spai can do this. Try:

  spai shape <path>          # Module structure: functions, types, impls by file
  spai def <symbol> [path]   # Where is this defined?
  spai usages <sym> [path]   # Where is this used?
  spai context <sym> [path]  # Call sites with enclosing function name
  spai sig <path>            # Function signatures (API surface)
  spai who <file> [path]     # Reverse deps: who imports this file?
  spai blast <sym> [path]    # Full blast radius before refactoring

Run `spai help` for all 21 commands.

EOF
fi

exit 0
