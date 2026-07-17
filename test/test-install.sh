#!/usr/bin/env bash
# End-to-end install tests for install.sh, in clean Docker containers.
#
# Each scenario reproduces a real user's starting point (fresh distro, no
# babashka, maybe no git/rsync, running as root with no sudo), then runs the
# CURRENT working-copy install.sh and asserts `spai help` actually works.
#
# Scenarios
#   local-clone : mounts THIS checkout and runs ./install.sh from inside it —
#                 the private/dev install path. Fully hermetic: tests the
#                 checkout's install.sh + setup.clj + cp-fallback (no rsync),
#                 bb auto-install (no sudo, as root), PATH handling. This is the
#                 CI gate — it validates the PR's own code end-to-end.
#   linux       : Ubuntu, curl+git, no bb, root, no sudo. Pipes the working-copy
#                 install.sh on stdin (like `curl … | bash`) and lets it DOWNLOAD
#                 the rest of spai from GitHub main. Exercises the real curl|bash
#                 path — but couples to whatever spai.clj/setup.clj are on main,
#                 so it is NOT a PR gate. Run manually.
#   no-git      : like `linux` but git absent → forces the curl+tarball download.
#
# Usage:  ./test/test-install.sh                 # all scenarios
#         ./test/test-install.sh local-clone     # one scenario by name
#         ./test/test-install.sh ci              # just the hermetic CI gate
#
# Requires: docker (with working network to GitHub for the babashka download).

set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="ubuntu:24.04"

pass=0
fail=0

# Run one scenario. $1=name, $2=container script. The container's output is
# always captured and echoed — a failing container must never fail silently
# (that just hides the real error from CI logs).
run() {
  local name="$1" script="$2"
  shift 2
  echo ""
  echo "════════════════════════════════════════════════════════════"
  echo "  SCENARIO: $name  ($IMAGE)"
  echo "════════════════════════════════════════════════════════════"

  local out rc
  out="$(docker run --rm "$@" "$IMAGE" bash -c "$script" 2>&1)"
  rc=$?
  echo "$out"
  if [ "$rc" -eq 0 ]; then
    echo "  ✅ PASS: $name"
    pass=$((pass + 1))
  else
    echo "  ❌ FAIL: $name (container exited $rc)"
    fail=$((fail + 1))
  fi
}

# Shared verification snippet appended to each scenario.
VERIFY='
  export PATH="$HOME/.local/bin:$PATH"; hash -r 2>/dev/null || true
  command -v bb   >/dev/null || { echo "FAIL: bb not on PATH"; exit 1; }
  command -v spai >/dev/null || { echo "FAIL: spai not on PATH"; exit 1; }
  spai help >/dev/null       || { echo "FAIL: spai help errored"; exit 1; }
  echo "OK: $(bb --version), spai help ran"
'

only="${1:-}"
# `ci` selects the hermetic scenarios that test the checked-out code from scratch.
ci_scenario() { case "$1" in local-clone|ripgrep) return 0;; *) return 1;; esac; }
want() { [ -z "$only" ] || [ "$only" = "$1" ] || { [ "$only" = "ci" ] && ci_scenario "$1"; }; }

# CI gate — hermetic, tests the checked-out code from scratch (no bb/rsync/sudo).
if want local-clone; then
  run "local-clone" '
    set -e
    apt-get update -qq >/dev/null 2>&1
    apt-get install -y -qq curl git ca-certificates >/dev/null 2>&1
    cp -r /src /clone && cd /clone
    echo "=== running ./install.sh from clone ==="
    ./install.sh
    '"$VERIFY" \
    -v "$REPO_DIR:/src:ro"
fi

# Sudo-free ripgrep install: reproduce install-ripgrep-binary's shell (version
# read from setup.clj so the test can't drift) in a root container with NO sudo
# present. If the code ever reached for sudo/apt, this would fail.
if want ripgrep; then
  run "ripgrep" '
    set -e
    apt-get update -qq >/dev/null 2>&1
    apt-get install -y -qq curl ca-certificates >/dev/null 2>&1
    command -v sudo >/dev/null && { echo "FAIL: sudo present, test invalid"; exit 1; }
    V=$(grep -oE "ripgrep-version \"[0-9.]+\"" /src/setup.clj | grep -oE "[0-9.]+")
    echo "ripgrep version from setup.clj: $V"
    case "$(uname -m)" in aarch64|arm64) T=aarch64-unknown-linux-musl;; *) T=x86_64-unknown-linux-musl;; esac
    BIN=$HOME/.local/bin; TMP=/tmp/spai-rg
    rm -rf $TMP; mkdir -p $TMP $BIN
    curl -sSfL "https://github.com/BurntSushi/ripgrep/releases/download/$V/ripgrep-$V-$T.tar.gz" | tar xz -C $TMP --strip-components=1
    cp $TMP/rg $BIN/rg && chmod +x $BIN/rg && rm -rf $TMP
    export PATH="$BIN:$PATH"
    rg --version | head -1
    echo hello | rg hello >/dev/null && echo "OK: ripgrep installed sudo-free"
    ' \
    -v "$REPO_DIR:/src:ro"
fi

# Real curl|bash path (downloads spai from GitHub main — couples to main, so
# not a CI gate). install.sh arrives on fd 0 via redirect, like a pipe.
if want linux; then
  run "linux" '
    set -e
    apt-get update -qq >/dev/null 2>&1
    apt-get install -y -qq curl git ca-certificates >/dev/null 2>&1
    echo "=== running install (piped, like curl | bash) ==="
    bash /dev/stdin < /install.sh
    '"$VERIFY" \
    -v "$REPO_DIR/install.sh:/install.sh:ro"
fi

if want no-git; then
  run "no-git" '
    set -e
    apt-get update -qq >/dev/null 2>&1
    apt-get install -y -qq curl ca-certificates >/dev/null 2>&1
    echo "=== running install (piped, no git → tarball path) ==="
    bash /dev/stdin < /install.sh
    '"$VERIFY" \
    -v "$REPO_DIR/install.sh:/install.sh:ro"
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  RESULTS: $pass passed, $fail failed"
echo "════════════════════════════════════════════════════════════"
[ "$fail" -eq 0 ]
