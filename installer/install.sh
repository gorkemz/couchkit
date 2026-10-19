#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# CouchKit installer: sets up the screen receiver and the home screen on an Android TV over adb.
# Every change is verified right after it is made and recorded in an undo journal on this computer.
# Works with the bash 3.2 that ships with macOS.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

CAST_PKG=io.couchkit.cast
CAST_MAIN=$CAST_PKG/io.github.jqssun.airplay.MainActivity
CAST_SETTINGS=$CAST_PKG/io.github.jqssun.airplay.SettingsReceiver
CAST_SET_ACTION=io.github.jqssun.airplay.SET_SETTING
HOME_PKG=io.couchkit.home
HOME_ACT=$HOME_PKG/.HomeActivity
HOME_A11Y=$HOME_PKG/$HOME_PKG.HomeKeyService
HOME_DREAM=$HOME_PKG/.GlassDream
STOCK_HOME_PKG=com.google.android.apps.tv.launcherx
STOCK_HOME_ACT=$STOCK_HOME_PKG/$STOCK_HOME_PKG.home.HomeActivity
SETUP_PKG=com.google.android.tungsten.setupwraith
STATE_DIR="${COUCHKIT_STATE:-$HOME/.couchkit}"

usage() {
  cat <<'EOF'
Usage: ./install.sh --host TV_IP [options]

Default steps: receiver, autostart, launcher, home, homekey
  --only LIST            run only these steps (comma separated), e.g. --only receiver,autostart
  --skip LIST            skip these steps
  --replace-stock-home   also disable the stock home screen + setup wizard (verified devices only)
  --debloat[=safe|full]  disable vendor telemetry/ads (safe) or more (full); verified devices only
  --screensaver          make the CouchKit screensaver the system screensaver
  --name NAME            receiver name shown on sender devices (default: "Living Room TV")
  --config FILE          push a launcher config.json (see launcher/config.example.json)
  --receiver-apk FILE    receiver APK (default: dist/couchkit-cast.apk)
  --launcher-apk FILE    launcher APK (default: dist/couchkit-home.apk)
  --allow-unverified     allow --replace-stock-home / --debloat on an unverified TCL model or firmware
  --yes                  do not ask before each change
  --dry-run              print every command, change nothing, do not even connect
  --simulate PROFILE     device used by --dry-run: tcl-smart-tv-pro (default), generic-android-tv, phone

Other modes:
  --status               show what is installed / changed on the TV (read only)
  --undo                 revert everything this installer recorded for this TV, newest first
  --rescue               bring the stock home screen back (no journal needed)

Steps: receiver autostart launcher home homekey replace-stock-home debloat screensaver
EOF
}

# ---------- output ----------
if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; D=$'\033[2m'; N=$'\033[0m'; else B= G= Y= R= D= N=; fi
say()  { printf '%s\n' "$*"; }
ok()   { printf '  %s✓%s %s\n' "$G" "$N" "$*"; }
warn() { printf '  %s!%s %s\n' "$Y" "$N" "$*"; }
die()  { printf '%s✗ %s%s\n' "$R" "$*" "$N" >&2; exit 1; }
hdr()  { printf '\n%s== %s ==%s\n' "$B" "$*" "$N"; }

# ---------- arguments ----------
HOST=""; DRY=0; SIM=tcl-smart-tv-pro; YES=0; MODE=install
ONLY=""; SKIP=""; REPLACE=0; DEBLOAT=""; SAVER=0; ALLOW_UNVERIFIED=0
NAME="Living Room TV"; CONFIG=""; RECEIVER_APK=""; LAUNCHER_APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="${2:?}"; shift 2 ;;
    --host=*) HOST="${1#*=}"; shift ;;
    --only) ONLY="${2:?}"; shift 2 ;;
    --skip) SKIP="${2:?}"; shift 2 ;;
    --replace-stock-home) REPLACE=1; shift ;;
    --debloat) DEBLOAT=safe; shift ;;
    --debloat=*) DEBLOAT="${1#*=}"; shift ;;
    --screensaver) SAVER=1; shift ;;
    --name) NAME="${2:?}"; shift 2 ;;
    --config) CONFIG="${2:?}"; shift 2 ;;
    --receiver-apk) RECEIVER_APK="${2:?}"; shift 2 ;;
    --launcher-apk) LAUNCHER_APK="${2:?}"; shift 2 ;;
    --allow-unverified) ALLOW_UNVERIFIED=1; shift ;;
    --yes|-y) YES=1; shift ;;
    --dry-run) DRY=1; shift ;;
    --simulate) SIM="${2:?}"; shift 2 ;;
    --status) MODE=status; shift ;;
    --undo) MODE=undo; shift ;;
    --rescue) MODE=rescue; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "unknown option: $1" ;;
  esac
done
case "$DEBLOAT" in ""|safe|full) ;; *) die "--debloat must be safe or full" ;; esac
case "$NAME" in *[!A-Za-z0-9\ ._-]*|"") die "--name may only contain letters, digits, space, dot, dash, underscore" ;; esac
[ -n "$CONFIG" ] && [ ! -f "$CONFIG" ] && die "config file not found: $CONFIG"

if [ -z "$HOST" ]; then
  [ "$DRY" = 1 ] && HOST=192.0.2.10 || { usage; die "--host is required (the TV's IP address)"; }
fi
case "$HOST" in *:*) SERIAL="$HOST" ;; *) SERIAL="$HOST:5555" ;; esac
JOURNAL="$STATE_DIR/$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9._-' '_').journal"

if [ "$DRY" = 1 ]; then
  SIMFILE="$HERE/sim/$SIM.env"
  [ -f "$SIMFILE" ] || die "unknown --simulate profile: $SIM (see installer/sim/)"
fi

# ---------- adb ----------
# Read-only queries. In --dry-run they are answered from the simulated device file.
simval() { grep -m1 "^$1=" "$SIMFILE" 2>/dev/null | cut -d= -f2- || true; }
adb_raw() { adb -s "$SERIAL" "$@" 2>/dev/null | tr -d '\r'; }

prop() { if [ "$DRY" = 1 ]; then simval "prop.$1"; else adb_raw shell getprop "$1"; fi; }
has_leanback() {
  if [ "$DRY" = 1 ]; then [ "$(simval feature.leanback)" = true ]; else [ "$(adb_raw shell pm has-feature android.software.leanback)" = true ]; fi
}
DRY_INSTALLED=" "
pkg_state() { # absent | enabled | disabled
  if [ "$DRY" = 1 ]; then
    case "$DRY_INSTALLED" in *" $1 "*) echo enabled; return ;; esac
    local v; v="$(simval "pkg.$1")"; echo "${v:-absent}"; return
  fi
  if ! adb_raw shell pm list packages "$1" | grep -qx "package:$1"; then echo absent
  elif adb_raw shell pm list packages -d "$1" | grep -qx "package:$1"; then echo disabled
  else echo enabled; fi
}
DRY_HOME=""
home_holder() {
  if [ "$DRY" = 1 ]; then [ -n "$DRY_HOME" ] && echo "$DRY_HOME" || simval role.home; return; fi
  adb_raw shell dumpsys role | awk '/name=android.app.role.HOME/{f=1;next} f&&/holders=/{sub(/.*holders=/,"");print;exit}'
}
home_activity() {
  if [ "$DRY" = 1 ]; then simval resolve.home; return; fi
  adb_raw shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
}
secure_get() { if [ "$DRY" = 1 ]; then local v; v="$(simval "secure.$1")"; echo "${v:-null}"; else adb_raw shell settings get secure "$1"; fi; }
appop_name() { # prints the vendor op name for a numeric op, e.g. APP_AUTO_START
  if [ "$DRY" = 1 ]; then simval "appop.$2"; return; fi
  adb_raw shell appops get "$1" "$2" | head -1 | cut -d: -f1
}

# Changes. Printed before they run; skipped entirely in --dry-run.
sh_do() {
  printf '  %s$ adb shell %s%s\n' "$D" "$*" "$N"
  [ "$DRY" = 1 ] && return 0
  local out; out="$(adb -s "$SERIAL" shell "$*" 2>&1 | tr -d '\r')" || { say "$out"; return 1; }
  [ -n "$out" ] && printf '    %s\n' "$out"
  case "$out" in *Error*|*Exception*|*"Unknown command"*|*Failure*) return 1 ;; esac
  return 0
}
adb_do() {
  local shown="$*"; shown="${shown//$ROOT\//}"
  printf '  %s$ adb %s%s\n' "$D" "$shown" "$N"
  [ "$DRY" = 1 ] && return 0
  adb -s "$SERIAL" "$@"
}

# ---------- journal ----------
journal_add() { # step, device shell command that undoes it
  if [ "$DRY" = 1 ]; then printf '  %sundo recorded: %s%s\n' "$D" "$2" "$N"; return; fi
  mkdir -p "$STATE_DIR"
  printf '%s|%s|%s\n' "$(date +%s)" "$1" "$2" >> "$JOURNAL"
}

confirm() {
  [ "$YES" = 1 ] || [ "$DRY" = 1 ] && return 0
  local a; printf '  %s?%s %s [y/N] ' "$Y" "$N" "$*"; read -r a </dev/tty || a=""
  case "$a" in y|Y|yes|YES|e|E|evet) return 0 ;; *) return 1 ;; esac
}
verify() { # description, then a command that must succeed
  local what="$1"; shift
  if [ "$DRY" = 1 ]; then printf '  %sverify (skipped in dry-run): %s%s\n' "$D" "$what" "$N"; return 0; fi
  if "$@"; then ok "$what"; else warn "verification failed: $what"; return 1; fi
}

# ---------- device check ----------
PROFILE=""
connect_and_profile() {
  hdr "Device"
  if [ "$DRY" = 1 ]; then
    say "  dry-run: nothing is sent to any device; simulating '$SIM' at $SERIAL"
  else
    command -v adb >/dev/null || die "adb not found. macOS: brew install android-platform-tools; Linux: apt install adb; Windows: use WSL"
    adb connect "$SERIAL" >/dev/null 2>&1 || true
    local st; st="$(adb devices | tr -d '\r' | awk -v s="$SERIAL" '$1==s{print $2}')"
    if [ "$st" = unauthorized ]; then
      say "  Accept the 'Allow debugging?' prompt on the TV (tick 'Always allow'), then press Enter."
      read -r _ </dev/tty || true
      st="$(adb devices | tr -d '\r' | awk -v s="$SERIAL" '$1==s{print $2}')"
    fi
    [ "$st" = device ] || die "cannot reach $SERIAL (state: ${st:-none}). Enable Developer options > Network/USB debugging on the TV and check the IP."
  fi
  MANU="$(prop ro.product.manufacturer)"; MODEL="$(prop ro.product.model)"; DEV="$(prop ro.product.device)"
  SDK="$(prop ro.build.version.sdk)"; ABI="$(prop ro.product.cpu.abi)"; FW="$(prop ro.software.version_id)"
  say "  $MANU $MODEL ($DEV), Android SDK $SDK, $ABI, firmware ${FW:-unknown}"

  if ! has_leanback || [ "${SDK:-0}" -lt 26 ]; then
    PROFILE=unsupported
  elif grep -v '^#' "$HERE/verified-devices.txt" | awk -F' *[|] *' -v m="$MANU" -v d="$DEV" -v s="$SDK" -v f="$FW" \
         '$1==m && $2==d && $3==s && $4==f {found=1} END{exit !found}'; then
    PROFILE=verified
  elif [ "$MANU" = TCL ]; then
    PROFILE=tcl-unverified
  else
    PROFILE=generic
  fi
  case "$PROFILE" in
    verified) ok "verified device: every step is available" ;;
    tcl-unverified) warn "TCL TV, but this model/firmware was not tested. Safe steps only unless --allow-unverified." ;;
    generic) warn "not a TCL TV: safe mode (apps, permissions and default home only; nothing is disabled)" ;;
    unsupported) [ "$MODE" = undo ] || [ "$MODE" = rescue ] || [ "$MODE" = status ] || die "not an Android TV with SDK >= 26 - stopping without changes" ;;
  esac
}

risky_allowed() { [ "$PROFILE" = verified ] || { [ "$PROFILE" = tcl-unverified ] && [ "$ALLOW_UNVERIFIED" = 1 ]; }; }

# ---------- APKs ----------
apk_path() { # explicit path, or dist/<name>
  local given="$1" def="$ROOT/dist/$2"
  if [ -n "$given" ]; then [ -f "$given" ] || die "APK not found: $given"; echo "$given"
  elif [ -f "$def" ]; then echo "$def"
  elif [ "$DRY" = 1 ]; then echo "$def"
  else die "missing $def - build it with ./scripts/build.sh or download it from the Releases page and pass --${3}-apk"; fi
}

install_apk() { # pkg, apk, step
  local pkg="$1" apk="$2" before; before="$(pkg_state "$pkg")"
  adb_do install -r "$apk" || die "install failed: $apk"
  [ "$DRY" = 1 ] && DRY_INSTALLED="$DRY_INSTALLED$pkg "
  verify "$pkg installed" test "$(pkg_state "$pkg")" = enabled || die "$pkg is not installed"
  [ "$before" = absent ] && journal_add "$3" "pm uninstall $pkg"
  return 0
}

# ---------- steps ----------
step_receiver() {
  hdr "Screen receiver ($CAST_PKG)"
  install_apk "$CAST_PKG" "$(apk_path "$RECEIVER_APK" couchkit-cast.apk receiver)" receiver
  # draw over other apps: needed to come to the front when a sender connects
  sh_do "appops set $CAST_PKG SYSTEM_ALERT_WINDOW allow"
  journal_add receiver "appops set $CAST_PKG SYSTEM_ALERT_WINDOW default"
  verify "overlay permission" sh -c "adb -s '$SERIAL' shell appops get $CAST_PKG SYSTEM_ALERT_WINDOW | grep -q allow"
  local kv k v
  for kv in "server_name=$NAME" boot_auto_start=true run_in_background=true; do
    k="${kv%%=*}"; v="${kv#*=}"
    sh_do "am broadcast -f 0x20 -n $CAST_SETTINGS -a $CAST_SET_ACTION --es key $k --es value '$v'"
  done
  # first launch takes the app out of the "stopped" state so it may start on boot; then back to home
  sh_do "am start -n $CAST_MAIN" && sleep 3
  sh_do "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
}

step_autostart() {
  hdr "Start on boot (vendor boot manager)"
  if [ "$PROFILE" != verified ] && [ "$PROFILE" != tcl-unverified ]; then
    say "  not a TCL TV: nothing to do"; return
  fi
  local pkg
  for pkg in "$CAST_PKG" "$HOME_PKG"; do
    [ "$(pkg_state "$pkg")" = enabled ] || { say "  $pkg not installed, skipped"; continue; }
    # ops 100/101 mean APP_AUTO_START / APP_ASSOC_START only on TCL builds; on stock Android they are
    # unrelated ops, so check the names the device reports before touching them
    if [ "$(appop_name "$pkg" 100)" != APP_AUTO_START ] || [ "$(appop_name "$pkg" 101)" != APP_ASSOC_START ]; then
      warn "this firmware has no TCL auto-start ops - skipped"; return
    fi
    sh_do "appops set $pkg 100 allow; appops set $pkg 101 allow"
    journal_add autostart "appops set $pkg 100 default; appops set $pkg 101 default"
    verify "$pkg may start on boot" sh -c "adb -s '$SERIAL' shell appops get $pkg 100 | grep -q allow"
  done
}

step_launcher() {
  hdr "Home screen ($HOME_PKG)"
  install_apk "$HOME_PKG" "$(apk_path "$LAUNCHER_APK" couchkit-home.apk launcher)" launcher
  if [ -n "$CONFIG" ]; then
    local dir=/sdcard/Android/data/$HOME_PKG/files
    sh_do "mkdir -p $dir"
    adb_do push "$CONFIG" "$dir/config.json"
    verify "config.json on the TV" sh -c "adb -s '$SERIAL' shell ls $dir/config.json >/dev/null"
  else
    say "  no --config: weather, rates, featured row and match card stay off (see launcher/config.example.json)"
  fi
}

step_home() {
  hdr "Default home screen"
  [ "$(pkg_state "$HOME_PKG")" = enabled ] || { warn "$HOME_PKG not installed - skipped"; return; }
  local prev prev_act; prev="$(home_holder)"
  if [ "$prev" = "$HOME_PKG" ]; then ok "already the default home"; return; fi
  prev_act="$(home_activity)"
  case "$prev_act" in */*) ;; *) prev_act="$STOCK_HOME_ACT" ;; esac
  confirm "Make CouchKit Home the default home screen? (previous: ${prev:-none})" || { say "  skipped"; return; }
  sh_do "cmd package set-home-activity $HOME_ACT" || die "set-home-activity failed"
  journal_add home "cmd package set-home-activity $prev_act"
  [ "$DRY" = 1 ] && DRY_HOME="$HOME_PKG"
  verify "home role holder is $HOME_PKG" test "$(home_holder)" = "$HOME_PKG" || warn "the stock launcher may still answer the Home button; the homekey step handles that"
}

step_homekey() {
  hdr "Home button (accessibility service)"
  [ "$(pkg_state "$HOME_PKG")" = enabled ] || { warn "$HOME_PKG not installed - skipped"; return; }
  # only Google TV's stock launcher grabs the Home key; elsewhere the default-home setting is enough
  [ "$(pkg_state $STOCK_HOME_PKG)" = absent ] && { say "  stock Google TV launcher not present: not needed"; return; }
  local prev en new; prev="$(secure_get enabled_accessibility_services)"; en="$(secure_get accessibility_enabled)"
  case ":$prev:" in *":$HOME_A11Y:"*) ok "already enabled"; return ;; esac
  say "  The service only listens for the Home key while the stock home screen is in front; it cannot read screen content."
  confirm "Enable the CouchKit Home button service?" || { say "  skipped"; return; }
  if [ "$prev" = null ] || [ -z "$prev" ]; then new="$HOME_A11Y"; else new="$prev:$HOME_A11Y"; fi
  sh_do "settings put secure enabled_accessibility_services '$new'; settings put secure accessibility_enabled 1"
  if [ "$prev" = null ] || [ -z "$prev" ]; then
    journal_add homekey "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled ${en:-0}"
  else
    journal_add homekey "settings put secure enabled_accessibility_services '$prev'; settings put secure accessibility_enabled ${en:-0}"
  fi
  verify "service enabled" sh -c "adb -s '$SERIAL' shell settings get secure enabled_accessibility_services | grep -q '$HOME_A11Y'"
}

step_replace() {
  hdr "Replace the stock home screen"
  risky_allowed || { warn "only on a verified device (or --allow-unverified on a TCL TV) - skipped"; return; }
  [ "$(pkg_state "$HOME_PKG")" = enabled ] || { warn "CouchKit Home is not installed - refusing"; return; }
  [ "$(home_holder)" = "$HOME_PKG" ] || { warn "CouchKit Home must be the default home first (step: home) - refusing"; return; }
  local s1 s2; s1="$(pkg_state $STOCK_HOME_PKG)"; s2="$(pkg_state $SETUP_PKG)"
  if [ "$s1" = disabled ] && [ "$s2" = disabled ]; then ok "already done"; return; fi
  [ "$s1" = enabled ] && [ "$s2" = enabled ] || { warn "unexpected state ($STOCK_HOME_PKG=$s1, $SETUP_PKG=$s2) - refusing"; return; }
  say "  Disables the stock launcher AND the setup wizard together (disabling only the launcher locks"
  say "  the TV in a recovery screen on the next boot). Frees ~300 MB RAM; stock recommendations,"
  say "  notifications panel and the Apps tab go away. Revert: ./install.sh --host $HOST --undo (or --rescue)."
  confirm "Disable $STOCK_HOME_PKG and $SETUP_PKG now?" || { say "  skipped"; return; }
  journal_add replace-stock-home "pm enable $SETUP_PKG; pm enable $STOCK_HOME_PKG; cmd package set-home-activity $STOCK_HOME_ACT"
  sh_do "pm disable-user --user 0 $STOCK_HOME_PKG && pm disable-user --user 0 $SETUP_PKG" || true
  if ! verify "Home resolves to CouchKit Home" sh -c "adb -s '$SERIAL' shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -q '$HOME_PKG'"; then
    warn "rolling back"
    sh_do "pm enable $SETUP_PKG; pm enable $STOCK_HOME_PKG; cmd package set-home-activity $STOCK_HOME_ACT"
    die "stock home restored; nothing else changed"
  fi
  say "  Test it: restart the TV once. If it ever shows a black/recovery screen, run: ./install.sh --host $HOST --rescue"
}

step_debloat() {
  [ -n "$DEBLOAT" ] || return 0
  hdr "Debloat ($DEBLOAT)"
  risky_allowed || { warn "only on a verified device (or --allow-unverified on a TCL TV) - skipped"; return; }
  local files="$HERE/debloat/tcl-safe.txt" line pkg what n=0
  [ "$DEBLOAT" = full ] && files="$files $HERE/debloat/tcl-optional.txt"
  # shellcheck disable=SC2086
  grep -hv '^#' $files | sed '/^ *$/d' > "${TMPDIR:-/tmp}/couchkit-debloat.$$"
  while IFS= read -r line; do
    pkg="$(echo "${line%%|*}" | tr -d ' ')"; what="${line#*| }"
    [ "$(pkg_state "$pkg")" = enabled ] || continue
    confirm "Disable $pkg ($what)?" || continue
    if sh_do "pm disable-user --user 0 $pkg"; then journal_add debloat "pm enable $pkg"; n=$((n+1)); fi
  done < "${TMPDIR:-/tmp}/couchkit-debloat.$$"
  rm -f "${TMPDIR:-/tmp}/couchkit-debloat.$$"
  ok "$n package(s) disabled (none uninstalled)"
}

step_screensaver() {
  [ "$SAVER" = 1 ] || return 0
  hdr "Screensaver"
  [ "$(pkg_state "$HOME_PKG")" = enabled ] || { warn "$HOME_PKG not installed - skipped"; return; }
  local prev; prev="$(secure_get screensaver_components)"
  [ "$prev" = "$HOME_DREAM" ] && { ok "already set"; return; }
  sh_do "settings put secure screensaver_components $HOME_DREAM"
  if [ "$prev" = null ] || [ -z "$prev" ]; then journal_add screensaver "settings delete secure screensaver_components"
  else journal_add screensaver "settings put secure screensaver_components $prev"; fi
  verify "screensaver set" sh -c "adb -s '$SERIAL' shell settings get secure screensaver_components | grep -q '$HOME_PKG'"
}

# ---------- modes ----------
want() { # step name -> should it run?
  case ",$SKIP," in *",$1,"*) return 1 ;; esac
  if [ -n "$ONLY" ]; then case ",$ONLY," in *",$1,"*) return 0 ;; *) return 1 ;; esac; fi
  case "$1" in
    receiver|autostart|launcher|home|homekey) return 0 ;;
    replace-stock-home) [ "$REPLACE" = 1 ] ;;
    debloat) [ -n "$DEBLOAT" ] ;;
    screensaver) [ "$SAVER" = 1 ] ;;
    *) return 1 ;;
  esac
}

do_install() {
  connect_and_profile
  [ "$DRY" = 1 ] && say "  ${B}DRY RUN${N} - commands below are printed, not executed"
  want receiver && step_receiver
  want launcher && step_launcher
  want autostart && step_autostart
  want home && step_home
  want homekey && step_homekey
  want replace-stock-home && step_replace
  want debloat && step_debloat
  want screensaver && step_screensaver
  hdr "Done"
  [ "$DRY" = 1 ] && say "  dry-run finished: nothing was changed" || say "  undo journal: $JOURNAL"
}

do_undo() {
  connect_and_profile
  [ -f "$JOURNAL" ] || die "no journal for $SERIAL at $JOURNAL (use --rescue to restore the stock home screen)"
  hdr "Undo (newest first)"
  local line cmd step
  awk '{a[NR]=$0} END{for(i=NR;i>0;i--) print a[i]}' "$JOURNAL" > "$JOURNAL.rev"
  while IFS= read -r line; do
    step="$(echo "$line" | cut -d'|' -f2)"; cmd="$(echo "$line" | cut -d'|' -f3-)"
    say "  [$step]"; sh_do "$cmd" || warn "failed (continuing): $cmd"
  done < "$JOURNAL.rev"
  rm -f "$JOURNAL.rev"
  [ "$DRY" = 1 ] || mv "$JOURNAL" "$JOURNAL.undone.$(date +%Y%m%d%H%M%S)"
  ok "undo finished"
}

do_rescue() {
  connect_and_profile
  hdr "Rescue: stock home screen"
  sh_do "pm enable $SETUP_PKG; pm enable $STOCK_HOME_PKG; cmd package set-home-activity $STOCK_HOME_ACT; am start -a android.intent.action.MAIN -c android.intent.category.HOME" || true
  ok "stock home enabled and set as default"
}

do_status() {
  connect_and_profile
  hdr "Status"
  say "  profile:               $PROFILE"
  say "  $CAST_PKG:      $(pkg_state $CAST_PKG)"
  say "  $HOME_PKG:      $(pkg_state $HOME_PKG)"
  say "  $STOCK_HOME_PKG: $(pkg_state $STOCK_HOME_PKG)"
  say "  $SETUP_PKG: $(pkg_state $SETUP_PKG)"
  say "  home role:             $(home_holder)"
  say "  accessibility:         $(secure_get enabled_accessibility_services)"
  say "  screensaver:           $(secure_get screensaver_components)"
  if [ -f "$JOURNAL" ]; then say "  journal ($JOURNAL):"; sed 's/^/    /' "$JOURNAL"; else say "  journal: none"; fi
}

case "$MODE" in
  install) do_install ;;
  undo) do_undo ;;
  rescue) do_rescue ;;
  status) do_status ;;
esac
