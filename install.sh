#!/usr/bin/env bash
# Install spai globally.
#
# Public:  curl -sSL https://raw.githubusercontent.com/spoqe/spai/main/install.sh | bash
# Private: git clone git@github.com:spoqe/spai.git && cd spai && ./install.sh
#
# Phase 1 (this script): download files, create wrappers. Pure bash, no deps.
# Phase 2 (setup.clj):   PATH, hooks, config. Readable Clojure via bb.

set -euo pipefail

REPO="spoqe/spai"
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

# Detect: are we running from inside a clone?
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VERSION_HASH="unknown"
VERSION_ORIGIN=""

if [ -f "$SCRIPT_DIR/spai.clj" ]; then
  info "Installing from local clone ($SCRIPT_DIR)..."
  VERSION_HASH="$(git -C "$SCRIPT_DIR" rev-parse HEAD 2>/dev/null || echo "unknown")"
  VERSION_ORIGIN="$(git -C "$SCRIPT_DIR" remote get-url origin 2>/dev/null || echo "")"
  # Don't clobber user data (usage.log)
  rsync -a --exclude='usage.log' --exclude='.git/' \
    "$SCRIPT_DIR/" "$SHARE_DIR/"
elif command -v git &>/dev/null; then
  info "Downloading spai..."
  rm -rf "${SHARE_DIR}.tmp"
  git clone --depth 1 "https://github.com/${REPO}.git" "${SHARE_DIR}.tmp" 2>/dev/null
  VERSION_HASH="$(git -C "${SHARE_DIR}.tmp" rev-parse HEAD 2>/dev/null || echo "unknown")"
  VERSION_ORIGIN="https://github.com/${REPO}.git"
  rm -f "${SHARE_DIR}.tmp/usage.log" 2>/dev/null
  cp -r "${SHARE_DIR}.tmp"/* "$SHARE_DIR/" 2>/dev/null || true
  rm -rf "${SHARE_DIR}.tmp"
elif command -v curl &>/dev/null; then
  info "Downloading spai..."
  rm -rf "${SHARE_DIR}.tmp" && mkdir -p "${SHARE_DIR}.tmp"
  curl -sSL "https://github.com/${REPO}/archive/refs/heads/main.tar.gz" | \
    tar xz --strip-components=1 -C "${SHARE_DIR}.tmp"
  rm -f "${SHARE_DIR}.tmp/usage.log" 2>/dev/null
  cp -r "${SHARE_DIR}.tmp"/* "$SHARE_DIR/" 2>/dev/null || true
  rm -rf "${SHARE_DIR}.tmp"
  VERSION_ORIGIN="https://github.com/${REPO}.git"
else
  fail "Need git or curl to download. Install one and try again."
fi

chmod +x "$SHARE_DIR"/*.clj 2>/dev/null || true

# Write version for update checking
cat > "$SHARE_DIR/.version" << VEOF
{:commit "${VERSION_HASH}" :installed "$(date -u +%Y-%m-%dT%H:%M:%SZ)" :repo "${REPO}" :origin "${VERSION_ORIGIN}"}
VEOF

# Create bin wrappers
info "Creating wrappers in $BIN_DIR..."

cat > "$BIN_DIR/spai" << 'WRAPPER'
#!/usr/bin/env bash
# Check for babashka
if ! command -v bb &>/dev/null; then
  echo "spai needs babashka (bb) to run." >&2
  echo "Install it: https://babashka.org" >&2
  echo "Then try again." >&2
  exit 1
fi
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
sed "s|SHARE_DIR_PLACEHOLDER|$SHARE_DIR|g" "$BIN_DIR/spai" > "$BIN_DIR/spai.tmp" && mv "$BIN_DIR/spai.tmp" "$BIN_DIR/spai"
chmod +x "$BIN_DIR/spai"

cat > "$BIN_DIR/spai-edit" << EOF
#!/usr/bin/env bash
if ! command -v bb &>/dev/null; then
  echo "spai-edit needs babashka (bb) to run." >&2
  echo "Install it: https://babashka.org" >&2
  exit 1
fi
bb "$SHARE_DIR/spai-edit.clj" "\$@"
EOF
chmod +x "$BIN_DIR/spai-edit"

# --- Phase 1.5: Ensure babashka (bb) is available ---

if ! command -v bb &>/dev/null; then
  warn "babashka (bb) not found — spai needs it to run."

  # Detect platform and package manager
  BB_CMD=""
  BB_LABEL=""
  OS="$(uname -s)"

  if [ "$OS" = "Darwin" ]; then
    if command -v brew &>/dev/null; then
      BB_CMD="brew install borkdude/brew/babashka"
      BB_LABEL="brew"
    fi
  elif [ "$OS" = "Linux" ]; then
    if command -v apt-get &>/dev/null; then
      BB_CMD="sudo bash -c 'curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && chmod +x install && ./install && rm install'"
      BB_LABEL="babashka installer (requires sudo)"
    elif command -v dnf &>/dev/null; then
      BB_CMD="sudo bash -c 'curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && chmod +x install && ./install && rm install'"
      BB_LABEL="babashka installer (requires sudo)"
    elif command -v pacman &>/dev/null; then
      BB_CMD="sudo bash -c 'curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && chmod +x install && ./install && rm install'"
      BB_LABEL="babashka installer (requires sudo)"
    elif command -v nix-env &>/dev/null; then
      BB_CMD="nix-env -i babashka"
      BB_LABEL="nix"
    fi
  fi

  # Fallback: babashka's own installer to ~/.local/bin (no sudo)
  if [ -z "$BB_CMD" ]; then
    BB_CMD="bash <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install) --dir $BIN_DIR"
    BB_LABEL="babashka installer (to $BIN_DIR)"
  fi

  echo ""
  echo "  Will run: $BB_CMD"
  echo ""

  if [ -t 0 ]; then
    # Interactive — ask the user
    printf "  Install babashka via %s? [Y/n] " "$BB_LABEL"
    read -r REPLY
    REPLY="${REPLY:-Y}"
  else
    # Non-interactive (piped curl | bash) — default yes, show what's happening
    info "Non-interactive mode — installing babashka via $BB_LABEL"
    REPLY="Y"
  fi

  if [[ "$REPLY" =~ ^[Yy] ]]; then
    eval "$BB_CMD"
    if command -v bb &>/dev/null; then
      info "babashka installed: $(bb --version)"
    else
      warn "babashka install may have succeeded but bb is not on PATH yet."
      echo "  Try opening a new terminal, then run: spai setup"
    fi
  else
    warn "Skipping babashka install."
    echo "  Install manually: https://babashka.org"
    echo "  Then run: spai setup"
  fi
fi

# --- Phase 2: Setup (via bb if available) ---

if command -v bb &>/dev/null; then
  bb "$SHARE_DIR/setup.clj" "$@"
else
  warn "babashka (bb) still not found — skipping setup"
  echo "  Once bb is on your PATH, run: spai setup"
fi
