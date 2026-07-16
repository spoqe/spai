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
# When piped (curl | bash) BASH_SOURCE[0] is unset — under `set -u` that would
# abort, so fall back to $0 (which resolves to the CWD, where there's no clone).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd)"

VERSION_HASH="unknown"
VERSION_ORIGIN=""

if [ -f "$SCRIPT_DIR/spai.clj" ]; then
  info "Installing from local clone ($SCRIPT_DIR)..."
  VERSION_HASH="$(git -C "$SCRIPT_DIR" rev-parse HEAD 2>/dev/null || echo "unknown")"
  VERSION_ORIGIN="$(git -C "$SCRIPT_DIR" remote get-url origin 2>/dev/null || echo "")"
  # Don't clobber user data (usage.log). Prefer rsync; fall back to cp where
  # rsync isn't installed (common on minimal Linux images).
  if command -v rsync &>/dev/null; then
    rsync -a --exclude='usage.log' --exclude='.git/' \
      "$SCRIPT_DIR/" "$SHARE_DIR/"
  else
    cp -r "$SCRIPT_DIR"/. "$SHARE_DIR/" 2>/dev/null || true
    rm -rf "$SHARE_DIR/.git" 2>/dev/null || true
    rm -f "$SHARE_DIR/usage.log" 2>/dev/null || true
  fi
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

# A freshly-created ~/.local/bin is often not yet on PATH in this shell.
# Put it there now so a bb we install below is visible to `command -v` and setup.
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) export PATH="$BIN_DIR:$PATH" ;;
esac

if ! command -v bb &>/dev/null; then
  warn "babashka (bb) not found — spai needs it to run."

  OS="$(uname -s)"
  BB_CMD=""
  BB_LABEL=""

  if [ "$OS" = "Darwin" ] && command -v brew &>/dev/null; then
    # macOS with Homebrew: let brew manage it (stays updated with the rest).
    BB_CMD="brew install borkdude/brew/babashka"
    BB_LABEL="brew"
  else
    # Everything else — Linux, or macOS without brew: babashka's official
    # installer into ~/.local/bin. No sudo, no system-wide writes, works as root.
    BB_INSTALLER="$(mktemp)"
    BB_CMD="curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install -o \"$BB_INSTALLER\" && chmod +x \"$BB_INSTALLER\" && \"$BB_INSTALLER\" --dir \"$BIN_DIR\" && rm -f \"$BB_INSTALLER\""
    BB_LABEL="babashka installer (→ $BIN_DIR, no sudo)"
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
    if eval "$BB_CMD"; then
      hash -r 2>/dev/null || true   # forget stale PATH lookups
    fi
    if command -v bb &>/dev/null; then
      info "babashka installed: $(bb --version)"
    else
      warn "babashka install finished but bb is not on PATH yet."
      echo "  Add this to your shell profile, then run 'spai setup':"
      echo "    export PATH=\"$BIN_DIR:\$PATH\""
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
