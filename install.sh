#!/usr/bin/env bash
# Install spai globally.
#
# curl -sSL https://raw.githubusercontent.com/semantic-partners/spai/main/install.sh | bash
#
# What it does:
#   1. Downloads spai to ~/.local/share/spai/
#   2. Creates spai and spai-edit wrappers in ~/.local/bin/
#   3. Checks for dependencies (bb, rg)
#   4. Optionally installs Claude Code reminder hook (--claude-hooks)

set -euo pipefail

REPO="SP-Lucky-Goose/spai"
BRANCH="main"
SHARE_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/spai"
BIN_DIR="${HOME}/.local/bin"

# Colors (if terminal supports them)
if [ -t 1 ]; then
  BOLD="\033[1m"
  GREEN="\033[32m"
  YELLOW="\033[33m"
  RED="\033[31m"
  RESET="\033[0m"
else
  BOLD="" GREEN="" YELLOW="" RED="" RESET=""
fi

info()  { echo -e "${BOLD}${GREEN}>>>${RESET} $1"; }
warn()  { echo -e "${BOLD}${YELLOW}>>>${RESET} $1"; }
fail()  { echo -e "${BOLD}${RED}>>>${RESET} $1"; exit 1; }

# Create directories
mkdir -p "$SHARE_DIR" "$BIN_DIR"

if [ -d "$SHARE_DIR/spai.clj" ] || [ -f "$SHARE_DIR/spai.clj" ]; then
  warn "Existing install found. Updating..."
fi

# Download
info "Downloading spai..."
if command -v git &>/dev/null; then
  git clone --depth 1 --filter=blob:none --sparse \
    "https://github.com/${REPO}.git" "${SHARE_DIR}.tmp" 2>/dev/null
  cd "${SHARE_DIR}.tmp"
  git sparse-checkout set . 2>/dev/null
  cd ..
  cp "${SHARE_DIR}.tmp"/*.clj "$SHARE_DIR/" 2>/dev/null || true
  cp "${SHARE_DIR}.tmp"/*.md "$SHARE_DIR/" 2>/dev/null || true
  cp -r "${SHARE_DIR}.tmp"/src "$SHARE_DIR/" 2>/dev/null || true
  cp -r "${SHARE_DIR}.tmp"/hooks "$SHARE_DIR/" 2>/dev/null || true
  rm -rf "${SHARE_DIR}.tmp"
elif command -v curl &>/dev/null; then
  curl -sSL "https://github.com/${REPO}/archive/refs/heads/${BRANCH}.tar.gz" | \
    tar xz --strip-components=1 -C "$SHARE_DIR"
else
  fail "Need git or curl to download. Install one and try again."
fi

chmod +x "$SHARE_DIR"/*.clj 2>/dev/null || true

# Create bin wrappers
info "Creating wrappers in $BIN_DIR..."

cat > "$BIN_DIR/spai" << EOF
#!/usr/bin/env bash
bb "$SHARE_DIR/spai.clj" "\$@"
EOF
chmod +x "$BIN_DIR/spai"

cat > "$BIN_DIR/spai-edit" << EOF
#!/usr/bin/env bash
bb "$SHARE_DIR/spai-edit.clj" "\$@"
EOF
chmod +x "$BIN_DIR/spai-edit"

# Check dependencies
echo ""
if command -v bb &>/dev/null; then
  info "babashka $(bb --version 2>/dev/null || echo '(installed)')"
else
  warn "babashka (bb) not found"
  echo "  Install: https://babashka.org"
  echo "  brew install borkdude/brew/babashka"
  echo ""
fi

if command -v rg &>/dev/null; then
  info "ripgrep $(rg --version 2>/dev/null | head -1 || echo '(installed)')"
else
  warn "ripgrep (rg) not found — spai will use grep (slower)"
  echo "  Install: brew install ripgrep"
  echo ""
fi

# Check PATH and offer to fix
if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
  # Detect shell rc file
  RC_FILE=""
  SHELL_NAME="$(basename "$SHELL")"
  case "$SHELL_NAME" in
    zsh)  RC_FILE="$HOME/.zshrc" ;;
    bash)
      # macOS uses .bash_profile for login shells, Linux uses .bashrc
      if [ -f "$HOME/.bash_profile" ]; then
        RC_FILE="$HOME/.bash_profile"
      else
        RC_FILE="$HOME/.bashrc"
      fi ;;
    fish) RC_FILE="$HOME/.config/fish/config.fish" ;;
  esac

  PATH_LINE='export PATH="$HOME/.local/bin:$PATH"'
  [ "$SHELL_NAME" = "fish" ] && PATH_LINE='set -gx PATH $HOME/.local/bin $PATH'

  if [ -n "$RC_FILE" ] && [ -t 0 ] && [ -t 1 ]; then
    # Interactive — check if already there, then offer to add
    if [ -f "$RC_FILE" ] && grep -qF '.local/bin' "$RC_FILE" 2>/dev/null; then
      info "$BIN_DIR already in $RC_FILE (restart shell or: source $RC_FILE)"
    else
      warn "$BIN_DIR is not in your PATH"
      read -p "  Add to $RC_FILE? [Y/n] " -n 1 -r
      echo ""
      if [[ ! $REPLY =~ ^[Nn]$ ]]; then
        echo "" >> "$RC_FILE"
        echo "# spai" >> "$RC_FILE"
        echo "$PATH_LINE" >> "$RC_FILE"
        info "Added to $RC_FILE — restart shell or: source $RC_FILE"
      else
        echo "  Add manually: $PATH_LINE"
        echo ""
      fi
    fi
  else
    # Non-interactive or unknown shell
    warn "$BIN_DIR is not in your PATH"
    if [ -n "$RC_FILE" ]; then
      echo "  Add to $RC_FILE:"
    else
      echo "  Add to your shell config:"
    fi
    echo "  $PATH_LINE"
    echo ""
  fi
fi

# -------------------------------------------------------------------
# Claude Code hook (optional)
# -------------------------------------------------------------------
# Detects ~/.claude → offers to install a Bash tool hook that reminds
# Claude agents to use spai instead of chaining grep.

CLAUDE_DIR="$HOME/.claude"
HOOK_SRC="$SHARE_DIR/hooks/claude-code-reminder.sh"
HOOK_DST="$CLAUDE_DIR/hooks/spai-reminder.sh"
SETTINGS="$CLAUDE_DIR/settings.json"

install_claude_hook() {
  mkdir -p "$CLAUDE_DIR/hooks"
  cp "$HOOK_SRC" "$HOOK_DST"
  chmod +x "$HOOK_DST"

  # Wire into settings.json
  if [ -f "$SETTINGS" ]; then
    # Check if hooks already configured
    if grep -q "spai-reminder" "$SETTINGS" 2>/dev/null; then
      info "Claude Code hook already configured"
      return
    fi
    # Append hooks to existing settings (simple: backup + python/jq)
    if command -v python3 &>/dev/null; then
      python3 -c "
import json, sys
with open('$SETTINGS') as f:
    s = json.load(f)
s.setdefault('hooks', {}).setdefault('Bash', {})['command'] = '$HOOK_DST'
with open('$SETTINGS', 'w') as f:
    json.dump(s, f, indent=2)
" 2>/dev/null && info "Claude Code hook configured in settings.json" && return
    fi
    warn "Could not update settings.json automatically."
    echo "  Add manually to $SETTINGS:"
    echo '  "hooks": { "Bash": { "command": "'$HOOK_DST'" } }'
  else
    # Create minimal settings
    mkdir -p "$CLAUDE_DIR"
    cat > "$SETTINGS" << EOJSON
{
  "hooks": {
    "Bash": {
      "command": "$HOOK_DST"
    }
  }
}
EOJSON
    info "Created $SETTINGS with hook"
  fi
  info "Claude Code hook installed"
}

if [ -d "$CLAUDE_DIR" ]; then
  # Claude Code detected — check for --claude-hooks flag or ask
  if [[ " $* " == *" --claude-hooks "* ]]; then
    install_claude_hook
  elif [ -t 0 ] && [ -t 1 ]; then
    # Interactive terminal — ask
    echo ""
    info "Claude Code detected!"
    echo "  spai includes a hook that reminds Claude agents to use spai"
    echo "  instead of chaining grep commands for code exploration."
    echo ""
    read -p "  Install Claude Code hook? [y/N] " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
      install_claude_hook
    else
      echo "  Skipped. Run again with --claude-hooks to install later."
    fi
  else
    # Non-interactive (piped install) — mention it
    echo ""
    info "Claude Code detected. To install the spai reminder hook:"
    echo "  Re-run with: install.sh --claude-hooks"
    echo "  Or manually: cp $HOOK_SRC $HOOK_DST"
  fi
fi

# Done
echo ""
info "Installed! Try:"
echo "  spai help"
echo "  spai-edit help"
echo ""
echo "  Per-project config: create .spai.edn"
echo "  Docs: $SHARE_DIR/README.md"
