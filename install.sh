#!/usr/bin/env bash
# Install spai globally.
#
# curl -sSL https://raw.githubusercontent.com/SP-Lucky-Goose/spai/main/install.sh | bash
#
# Phase 1 (this script): download files, create wrappers. Pure bash, no deps.
# Phase 2 (setup.clj):   PATH, hooks, config. Readable Clojure via bb.

set -euo pipefail

REPO="SP-Lucky-Goose/spai"
SHARE_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/spai"
BIN_DIR="${HOME}/.local/bin"

# Colors
if [ -t 1 ]; then
  info()  { echo -e "\033[1m\033[32m>>>\033[0m $1"; }
  warn()  { echo -e "\033[1m\033[33m>>>\033[0m $1"; }
  fail()  { echo -e "\033[1m\033[31m>>>\033[0m $1"; exit 1; }
else
  info()  { echo ">>> $1"; }
  warn()  { echo ">>> $1"; }
  fail()  { echo ">>> $1"; exit 1; }
fi

# --- Phase 1: Get files on disk ---

mkdir -p "$SHARE_DIR" "$SHARE_DIR/plugins" "$BIN_DIR"

[ -f "$SHARE_DIR/spai.clj" ] && warn "Existing install found. Updating..."

info "Downloading spai..."
if command -v git &>/dev/null; then
  rm -rf "${SHARE_DIR}.tmp"
  git clone --depth 1 "https://github.com/${REPO}.git" "${SHARE_DIR}.tmp" 2>/dev/null
  # Don't clobber user data (usage.log, plugins, etc.)
  rm -f "${SHARE_DIR}.tmp/usage.log" 2>/dev/null
  rm -rf "${SHARE_DIR}.tmp/plugins" 2>/dev/null
  cp -r "${SHARE_DIR}.tmp"/* "$SHARE_DIR/" 2>/dev/null || true
  rm -rf "${SHARE_DIR}.tmp"
elif command -v curl &>/dev/null; then
  rm -rf "${SHARE_DIR}.tmp" && mkdir -p "${SHARE_DIR}.tmp"
  curl -sSL "https://github.com/${REPO}/archive/refs/heads/main.tar.gz" | \
    tar xz --strip-components=1 -C "${SHARE_DIR}.tmp"
  rm -f "${SHARE_DIR}.tmp/usage.log" 2>/dev/null
  rm -rf "${SHARE_DIR}.tmp/plugins" 2>/dev/null
  cp -r "${SHARE_DIR}.tmp"/* "$SHARE_DIR/" 2>/dev/null || true
  rm -rf "${SHARE_DIR}.tmp"
else
  fail "Need git or curl to download. Install one and try again."
fi

chmod +x "$SHARE_DIR"/*.clj 2>/dev/null || true

# Create bin wrappers
info "Creating wrappers in $BIN_DIR..."

cat > "$BIN_DIR/spai" << 'WRAPPER'
#!/usr/bin/env bash
# Global plugins
export PATH="SHARE_DIR_PLACEHOLDER/plugins:$PATH"
# Project-local plugins: walk up from CWD
_d="$PWD"
while [ "$_d" != "/" ]; do
  [ -d "$_d/.spai/plugins" ] && export PATH="$_d/.spai/plugins:$PATH" && break
  _d="$(dirname "$_d")"
done
unset _d
bb "SHARE_DIR_PLACEHOLDER/spai.clj" "$@"
WRAPPER
sed -i '' "s|SHARE_DIR_PLACEHOLDER|$SHARE_DIR|g" "$BIN_DIR/spai"
chmod +x "$BIN_DIR/spai"

cat > "$BIN_DIR/spai-edit" << EOF
#!/usr/bin/env bash
bb "$SHARE_DIR/spai-edit.clj" "\$@"
EOF
chmod +x "$BIN_DIR/spai-edit"

# --- Phase 2: Setup (via bb if available) ---

if command -v bb &>/dev/null; then
  bb "$SHARE_DIR/setup.clj" "$@"
else
  warn "babashka (bb) not found — skipping setup"
  echo "  Install bb: https://babashka.org"
  echo "  Then run:   spai setup"
fi
