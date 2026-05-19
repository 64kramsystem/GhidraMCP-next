#!/usr/bin/env bash
#
# GUI smoke test: run the *packaged* GhidraMCP extension inside real Ghidra GUI
# under Xvfb, open the imported fixture in CodeBrowser, then exercise one HTTP
# endpoint of the running plugin.
#
# Usage:
#   bash test/gui-smoke/run.sh [path-to-extension-zip]
#
# Env:
#   GHIDRA_HOME    Ghidra distribution root. Falls back to the path written by
#                  the 12.1 verification flow at /tmp/ghidra-mcp-next-ghidra-home.
#   ARTIFACTS_DIR  Where to drop ghidra.log + screenshot on failure. Falls back
#                  to $RUNNER_TEMP/gui-smoke-artifacts, then /tmp/gui-smoke-artifacts.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EXTENSION_ZIP="${1:-}"
if [ -z "$EXTENSION_ZIP" ]; then
  # Pick the freshest snapshot zip.
  EXTENSION_ZIP=$(ls -t "$REPO_ROOT"/target/GhidraMCP-next-*-SNAPSHOT.zip 2>/dev/null | head -1)
fi

if [ -z "${GHIDRA_HOME:-}" ] && [ -f /tmp/ghidra-mcp-next-ghidra-home ]; then
  GHIDRA_HOME=$(cat /tmp/ghidra-mcp-next-ghidra-home)
fi

ARTIFACTS_DIR="${ARTIFACTS_DIR:-${RUNNER_TEMP:-/tmp}/gui-smoke-artifacts}"
mkdir -p "$ARTIFACTS_DIR"

TMP_BASE=$(mktemp -d /tmp/ghidra-gui-smoke.XXXXXX)
GHIDRA_LOG="$TMP_BASE/ghidra.log"
XVFB_LOG="$TMP_BASE/xvfb.log"
PROJECT_PARENT="$TMP_BASE/project"
PROJECT_NAME="gui_smoke"
USER_DIR_RELATIVE=".config/ghidra/ghidra_12.1_PUBLIC"

export HOME="$TMP_BASE/home"
export XDG_CONFIG_HOME="$HOME/.config"
export XDG_CACHE_HOME="$HOME/.cache"
export XDG_DATA_HOME="$HOME/.local/share"
export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
USER_SETTINGS="$HOME/$USER_DIR_RELATIVE"

XVFB_PID=
WM_PID=
GHIDRA_PID=
PHASE="init"
FAIL=0

log()  { printf '[%s] %s\n' "$PHASE" "$*"; }
die()  { FAIL=1; log "FAIL: $*"; }

dump_diagnostics() {
  log "Dumping diagnostics to $ARTIFACTS_DIR ..."
  [ -f "$GHIDRA_LOG" ] && cp "$GHIDRA_LOG" "$ARTIFACTS_DIR/" 2>/dev/null || true
  [ -f "$XVFB_LOG" ]   && cp "$XVFB_LOG"   "$ARTIFACTS_DIR/" 2>/dev/null || true
  if [ -n "${DISPLAY:-}" ]; then
    import -display "$DISPLAY" -window root "$ARTIFACTS_DIR/screen.png" 2>/dev/null || true
  fi
  # Grep interesting lines if log exists
  if [ -f "$GHIDRA_LOG" ]; then
    {
      echo "--- GhidraMCP / class loader lines ---"
      grep -E 'GhidraMCP|ClassNotFound|NoClassDef|extension' "$GHIDRA_LOG" || true
    } > "$ARTIFACTS_DIR/grep.log"
  fi
}

teardown() {
  PHASE="teardown"
  if [ -n "$GHIDRA_PID" ] && kill -0 "$GHIDRA_PID" 2>/dev/null; then
    kill "$GHIDRA_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
      kill -0 "$GHIDRA_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$GHIDRA_PID" 2>/dev/null || true
  fi
  pkill -P $$ 2>/dev/null || true
  if [ -n "$WM_PID" ] && kill -0 "$WM_PID" 2>/dev/null; then
    kill "$WM_PID" 2>/dev/null || true
  fi
  if [ -n "$XVFB_PID" ] && kill -0 "$XVFB_PID" 2>/dev/null; then
    kill "$XVFB_PID" 2>/dev/null || true
  fi
  wait 2>/dev/null || true
}

trap '[ $FAIL -ne 0 ] && dump_diagnostics; teardown' EXIT

# --- Phase 1: resolve_env --------------------------------------------------
PHASE="resolve_env"
for bin in Xvfb xdotool nc curl unzip import java; do
  command -v "$bin" >/dev/null 2>&1 || { die "missing dep: $bin"; exit 1; }
done
# Pick a window manager. Without one, Java/AWT focus management does not work
# under bare Xvfb, so Swing widgets never receive mouse/keystroke events.
WM_BIN=
for wm in marco metacity mutter openbox fluxbox xfwm4 matchbox-window-manager twm; do
  if command -v "$wm" >/dev/null 2>&1; then WM_BIN="$wm"; break; fi
done
[ -n "$WM_BIN" ] || { die "no window manager on PATH (need one of: marco, metacity, openbox, fluxbox, xfwm4, matchbox-window-manager, twm)"; exit 1; }
[ -n "${GHIDRA_HOME:-}" ] && [ -d "$GHIDRA_HOME" ] || { die "GHIDRA_HOME not set or missing: ${GHIDRA_HOME:-<unset>}"; exit 1; }
[ -n "$EXTENSION_ZIP" ] && [ -f "$EXTENSION_ZIP" ] || { die "extension zip not found: ${EXTENSION_ZIP:-<unset>}"; exit 1; }
log "GHIDRA_HOME=$GHIDRA_HOME"
log "EXTENSION_ZIP=$EXTENSION_ZIP"
log "TMP_BASE=$TMP_BASE"
log "WM=$WM_BIN"

# --- Phase 2: setup_user_dir ----------------------------------------------
PHASE="setup_user_dir"
mkdir -p "$USER_SETTINGS/tools" "$USER_SETTINGS/Extensions" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_DATA_HOME"
cat > "$USER_SETTINGS/preferences" <<'PREF'
#User Preferences
USER_AGREEMENT=ACCEPT
GhidraShowWhatsNew=false
SHOW_TIPS=false
PREF
log "user dir staged at $USER_SETTINGS"

# --- Phase 3: install_extension -------------------------------------------
PHASE="install_extension"
unzip -qo "$EXTENSION_ZIP" -d "$USER_SETTINGS/Extensions/"
ls "$USER_SETTINGS/Extensions/" | head -5
EXT_PROPS=$(find "$USER_SETTINGS/Extensions" -maxdepth 2 -name extension.properties | head -1)
[ -n "$EXT_PROPS" ] && [ -f "$EXT_PROPS" ] || { die "extension.properties not present after unzip"; exit 1; }
log "extension installed: $EXT_PROPS"
grep -E '^(name|version|ghidraVersion)=' "$EXT_PROPS" | sed 's/^/  /'

# --- Phase 4: stage_tool_chest --------------------------------------------
PHASE="stage_tool_chest"
TEMPLATE="$REPO_ROOT/test/gui-smoke/CodeBrowser.tcd.template"
[ -f "$TEMPLATE" ] || { die "template missing: $TEMPLATE"; exit 1; }
cp "$TEMPLATE" "$USER_SETTINGS/tools/CodeBrowser.tcd"
log "tool chest: $USER_SETTINGS/tools/CodeBrowser.tcd"

# --- Phase 5: import_fixture ----------------------------------------------
PHASE="import_fixture"
mkdir -p "$PROJECT_PARENT"
"$GHIDRA_HOME"/support/analyzeHeadless \
  "$PROJECT_PARENT" "$PROJECT_NAME" \
  -import "$REPO_ROOT/test/fixtures/test_6502.bin" \
  -processor "6502:LE:16:default" \
  >"$TMP_BASE/analyzeHeadless.log" 2>&1
if [ ! -f "$PROJECT_PARENT/$PROJECT_NAME.gpr" ]; then
  die "project file not created"
  tail -40 "$TMP_BASE/analyzeHeadless.log"
  exit 1
fi
log "project: $PROJECT_PARENT/$PROJECT_NAME.gpr"

# --- Phase 6: assert_port_free --------------------------------------------
PHASE="assert_port_free"
if nc -z 127.0.0.1 8080 2>/dev/null; then
  die "port 8080 already in use"
  exit 1
fi

# --- Phase 7: launch_ghidra (Xvfb + WM, manual) ---------------------------
PHASE="launch_ghidra"
# Pick a likely-unused DISPLAY number to avoid clashing with the user's session.
DISPLAY_NUM=$(( ( RANDOM % 50 ) + 90 ))
DISPLAY=":$DISPLAY_NUM"
export DISPLAY
Xvfb "$DISPLAY" -screen 0 1280x1024x24 >"$XVFB_LOG" 2>&1 &
XVFB_PID=$!
sleep 1
if ! kill -0 "$XVFB_PID" 2>/dev/null; then
  die "Xvfb failed to start on $DISPLAY"
  exit 1
fi
log "Xvfb $DISPLAY (pid=$XVFB_PID)"

# Start a minimal window manager so Swing focus and xdotool windowactivate work.
"$WM_BIN" --display "$DISPLAY" --sm-disable >"$TMP_BASE/wm.log" 2>&1 &
WM_PID=$!
sleep 1
if ! kill -0 "$WM_PID" 2>/dev/null; then
  # Some WMs do not understand --sm-disable; retry without it.
  "$WM_BIN" --display "$DISPLAY" >"$TMP_BASE/wm.log" 2>&1 &
  WM_PID=$!
  sleep 1
fi
kill -0 "$WM_PID" 2>/dev/null || { die "$WM_BIN failed to start"; exit 1; }
log "$WM_BIN (pid=$WM_PID)"

"$GHIDRA_HOME"/support/launch.sh fg jdk Ghidra "" "" ghidra.GhidraRun "$PROJECT_PARENT/$PROJECT_NAME.gpr" >"$GHIDRA_LOG" 2>&1 &
GHIDRA_PID=$!
log "ghidraRun (pid=$GHIDRA_PID)"

# --- Phase 8: open_program ------------------------------------------------
PHASE="open_program"
WID=
for i in $(seq 1 60); do
  WID=$(xdotool search --name "^Ghidra: $PROJECT_NAME\$" 2>/dev/null | head -1 || true)
  [ -n "$WID" ] && break
  if ! kill -0 "$GHIDRA_PID" 2>/dev/null; then
    die "Ghidra died before FrontEnd window appeared"
    tail -40 "$GHIDRA_LOG"
    exit 1
  fi
  sleep 1
done
[ -n "$WID" ] || { die "FrontEnd window not found within 60s"; exit 1; }
log "FrontEnd window id=$WID"

# Let the project tree finish populating after window appears.
sleep 5

# Bring the FrontEnd to the front then double-click on the project tree where
# the first child (the imported program) sits. We use window-relative
# coordinates so it does not depend on the absolute window placement.
xdotool windowactivate --sync "$WID" 2>>"$TMP_BASE/xdotool.log" || true
sleep 1
xdotool mousemove --window "$WID" 100 200 2>>"$TMP_BASE/xdotool.log" || true
sleep 0.3
xdotool click --window "$WID" --repeat 2 --delay 200 1 \
  2>>"$TMP_BASE/xdotool.log" || true
log "sent double-click at window-relative (100,200)"

# After double-click, Ghidra may pop a "New Plugins Found!" dialog because the
# extension has never been seen by this fresh project state. Dismiss it (No)
# so the launching CodeBrowser tool finishes and opens the program.
NPW=
for i in $(seq 1 30); do
  NPW=$(xdotool search --name '^New Plugins Found!$' 2>/dev/null | head -1 || true)
  [ -n "$NPW" ] && break
  sleep 1
done
if [ -n "$NPW" ]; then
  xdotool windowactivate --sync "$NPW" 2>>"$TMP_BASE/xdotool.log" || true
  sleep 0.3
  xdotool key --window "$NPW" --clearmodifiers Escape 2>>"$TMP_BASE/xdotool.log" || true
  log "dismissed 'New Plugins Found!' dialog"
else
  log "no 'New Plugins Found!' dialog appeared"
fi

# --- Phase 9: wait_for_port -----------------------------------------------
PHASE="wait_for_port"
deadline=$((SECONDS + 180))
while ! nc -z 127.0.0.1 8080 2>/dev/null; do
  if ! kill -0 "$GHIDRA_PID" 2>/dev/null; then
    die "Ghidra died waiting for port 8080"
    tail -60 "$GHIDRA_LOG"
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    die "port 8080 never opened within 180s"
    tail -60 "$GHIDRA_LOG"
    exit 1
  fi
  sleep 1
done
log "port 8080 open"

# --- Phase 10: assert_endpoint --------------------------------------------
PHASE="assert_endpoint"
# Diagnostic screenshot regardless of outcome.
import -display "$DISPLAY" -window root "$TMP_BASE/pre-curl.png" 2>/dev/null || true
RESP=$(curl -sf http://127.0.0.1:8080/list_functions 2>"$TMP_BASE/curl.err" || true)
if [ -z "$RESP" ]; then
  die "/list_functions returned empty"
  cat "$TMP_BASE/curl.err" || true
  exit 1
fi
log "/list_functions response (first 200 chars):"
printf '  %s\n' "${RESP:0:200}"
# "No program loaded" means the plugin loaded in a tool without a
# ProgramManager (typically the FrontEnd). The smoke test contract is that the
# packaged plugin runs in a tool that has the imported program.
case "$RESP" in
  "No program loaded"*|*"No program loaded"*)
    die "/list_functions reports 'No program loaded' — plugin not bound to CodeBrowser with the imported program"
    exit 1 ;;
esac

# Optional plugin-loaded grep (warn-only).
if grep -q 'GhidraMCPPlugin loaded' "$GHIDRA_LOG"; then
  log "ghidra.log confirms GhidraMCPPlugin loaded"
else
  log "WARN: ghidra.log did not log 'GhidraMCPPlugin loaded' (continuing)"
fi

PHASE="result"
log "PASS list_functions"
# Shut down via plugin shutdown endpoint if it exists; otherwise let teardown kill it.
curl -sf http://127.0.0.1:8080/shutdown >/dev/null 2>&1 || true
exit 0
