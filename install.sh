#!/usr/bin/env bash
# Install spai globally.
#
# curl -sSL https://raw.githubusercontent.com/semantic-partners/spai/main/install.sh | bash
#
# What it does:
#   1. Downloads spai.clj and spai-edit.clj to ~/.local/share/spai/
#   2. Creates spai and spai-edit wrappers in ~/.local/bin/
#   3. Checks for dependencies (bb, rg)

set -euo pipefail

REPO="semantic-partners/spai"
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

# Check PATH
if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
  warn "$BIN_DIR is not in your PATH"
  echo "  Add to your shell config:"
  echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
  echo ""
fi

# Done
echo ""
info "Installed! Try:"
echo "  spai help"
echo "  spai-edit help"
echo ""
echo "  Per-project config: create .spai.edn"
echo "  Docs: $SHARE_DIR/README.md"
