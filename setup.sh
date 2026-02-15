#!/usr/bin/env bash
# Create local ./spai and ./spai-edit wrappers for development.
# Run from the spai source repo: ./spai/setup.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Create ./spai wrapper
cat > "$PROJECT_ROOT/spai" << WRAPPER
#!/usr/bin/env bash
bb "$(basename "$SCRIPT_DIR")/spai.clj" "\$@"
WRAPPER
chmod +x "$PROJECT_ROOT/spai"

# Create ./spai-edit wrapper
cat > "$PROJECT_ROOT/spai-edit" << WRAPPER
#!/usr/bin/env bash
bb "$(basename "$SCRIPT_DIR")/spai-edit.clj" "\$@"
WRAPPER
chmod +x "$PROJECT_ROOT/spai-edit"

echo "Installed:"
echo "  $PROJECT_ROOT/spai"
echo "  $PROJECT_ROOT/spai-edit"
echo ""
echo "Try: ./spai help"
echo "     ./spai-edit help"

# Check dependencies
if ! command -v bb &>/dev/null; then
  echo ""
  echo "Warning: babashka (bb) not found. Install: https://babashka.org/"
fi
if ! command -v rg &>/dev/null; then
  echo ""
  echo "Note: ripgrep (rg) not found. spai will use grep (slower)."
fi
