#!/usr/bin/env bash
# MaterialTV: Fire TV setup and undo, run from a Mac or Linux computer.
#
# Amazon blocks apps on a Fire TV from using that TV's own ADB (Fire OS 7.6.6.9 and newer),
# so this script does from your computer what MaterialTV's built-in setup does on the TV.
# It follows src/app/m3home/Setup.java step for step and shares its change log
# (/sdcard/Android/data/app.m3home/files/setup/ledger.json), so Undo works from either side.
#
#   ./setup.sh [--tv 192.168.1.50] [--undo] [--yes]
#
#   --tv IP    the Fire TV's IP address (asked for if left out)
#   --undo     put back what setup changed
#   --yes      don't ask, use the default answers
#
# Environment: M3_TV, M3_UNDO=1, M3_YES=1 do the same as the flags (handy with curl | bash).
# Works with bash 3.2 (macOS) and newer. Needs curl and unzip (unzip only if adb is missing).
# Downloads only from dl.google.com, github.com, raw.githubusercontent.com and pkgs.tailscale.com.

set -u

REPO="Brick-Bread/MaterialTV"
RAW_LISTS="https://raw.githubusercontent.com/$REPO/main/assets/setup"
APK_URL="https://github.com/$REPO/releases/latest/download/m3home.apk"
API_LATEST="https://api.github.com/repos/$REPO/releases/latest"
SELF="app.m3home"
GUARD="app.m3home/app.m3home.HomeGuard"
DREAM="app.m3home/app.m3home.Screensaver"
LISTENER="app.m3home/app.m3home.MediaListener"
TAILSCALE="com.tailscale.ipn"
YOUTUBE="com.amazon.firetv.youtube"
SMARTTUBE_PKGS="org.smarttube.stable com.teamsmart.videomanager.tv"
SMARTTUBE_URL="https://github.com/yuliskov/SmartTube/releases/download/32.56s/SmartTube_stable_32.56_universal.apk"
SMARTTUBE_SHA="4a35b90940612e048e4e9cb9e10379f7db1e84f30375994129f10a571ee04ce5"
LEDGER_DIR="/sdcard/Android/data/app.m3home/files/setup"
LEDGER_PATH="$LEDGER_DIR/ledger.json"
LIST_NAMES="packages.txt protected.txt settings.txt never-disable.txt alexa.txt settings.restore.txt"

# Git Bash on Windows would otherwise rewrite /sdcard/... into a Windows path.
case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) export MSYS2_ARG_CONV_EXCL='*' ;; esac

# ---- options ------------------------------------------------------------------------------

TV="${M3_TV:-}"
UNDO="${M3_UNDO:-0}"
YES="${M3_YES:-0}"
DRY_RUN="${M3_DRY_RUN:-0}"   # hidden: print every command that would change the TV, run none of them
case "$UNDO" in 1|true|yes|y) UNDO=1 ;; *) UNDO=0 ;; esac
case "$YES" in 1|true|yes|y) YES=1 ;; *) YES=0 ;; esac
case "$DRY_RUN" in 1|true|yes|y) DRY_RUN=1 ;; *) DRY_RUN=0 ;; esac

usage() {
    cat <<'EOF'
MaterialTV Fire TV setup

  setup.sh [--tv 192.168.1.50] [--undo] [--yes]

  --tv IP    the Fire TV's IP address (asked for if left out)
  --undo     put back what setup changed
  --yes      don't ask questions, use the default answers

Find the TV's IP in Settings > My Fire TV > About > Network
(the MaterialTV setup screen shows it too).
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --tv|-t) [ $# -ge 2 ] || { usage; exit 2; }; TV="$2"; shift ;;
        --tv=*) TV="${1#--tv=}" ;;
        --undo|-u) UNDO=1 ;;
        --yes|-y) YES=1 ;;
        --dry-run) DRY_RUN=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

# ---- output -------------------------------------------------------------------------------

if [ -t 1 ] && [ "${TERM:-dumb}" != "dumb" ] && [ -z "${NO_COLOR:-}" ]; then
    C_0=$'\033[0m'; C_B=$'\033[1m'; C_STEP=$'\033[1;35m'; C_OK=$'\033[32m'
    C_WARN=$'\033[33m'; C_ERR=$'\033[1;31m'; C_DIM=$'\033[2m'
else
    C_0=""; C_B=""; C_STEP=""; C_OK=""; C_WARN=""; C_ERR=""; C_DIM=""
fi
exec 3>&1   # dry-run lines go here, so they never end up inside $(...) captures

step() { printf '%s%s%s\n' "$C_STEP" "$*" "$C_0"; }
info() { printf '  %s\n' "$*"; }
ok()   { printf '  %s%s%s\n' "$C_OK" "$*" "$C_0"; }
warn() { printf '  %sWarning:%s %s\n' "$C_WARN" "$C_0" "$*"; }
fail() { printf '%sError:%s %s\n' "$C_ERR" "$C_0" "$*" >&2; }
die()  { fail "$*"; exit 1; }

SUMMARY=""
note() { SUMMARY="$SUMMARY
  - $*"; }

# ---- input (works with curl | bash too) -----------------------------------------------------

TTY_IN=""
if [ -t 0 ]; then TTY_IN=""; elif { : </dev/tty; } 2>/dev/null; then TTY_IN="/dev/tty"; fi

read_line() {
    local v=""
    if [ -n "$TTY_IN" ]; then IFS= read -r v <"$TTY_IN" || true; else IFS= read -r v || true; fi
    printf '%s' "$v"
}

# yesno "Question" Y|N  -> exit status 0 for yes
yesno() {
    local q="$1" def="$2" hint a
    if [ "$def" = "Y" ]; then hint="[Y/n]"; else hint="[y/N]"; fi
    if [ "$YES" = 1 ]; then
        printf '%s %s %s\n' "$q" "$hint" "$( [ "$def" = Y ] && echo yes || echo no )"
        [ "$def" = "Y" ]; return
    fi
    while :; do
        printf '%s %s ' "$q" "$hint"
        a=$(read_line)
        if [ -z "$TTY_IN" ] && [ ! -t 0 ]; then echo "$a"; fi
        case "$a" in
            "") [ "$def" = "Y" ]; return ;;
            [Yy]|[Yy][Ee][Ss]) return 0 ;;
            [Nn]|[Nn][Oo]) return 1 ;;
            *) echo "  Please answer y or n." ;;
        esac
    done
}

# ---- temp files ---------------------------------------------------------------------------

WORK=$(mktemp -d 2>/dev/null || mktemp -d -t m3home) || die "Couldn't create a temporary folder."
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
trap 'echo; fail "Stopped."; exit 130' INT TERM
FLAT="$WORK/ledger.flat"
: >"$FLAT"

# ---- downloads ----------------------------------------------------------------------------

command -v curl >/dev/null 2>&1 || die "This script needs curl. Install it and run again."

fetch() { curl -fsSL --retry 2 --connect-timeout 15 -A "MaterialTV-Setup" -o "$2" "$1"; }
fetch_text() { curl -fsSL --retry 2 --connect-timeout 15 -A "MaterialTV-Setup" "$1" 2>/dev/null; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print tolower($1)}'
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print tolower($1)}'
    else echo ""; fi
}

# Local path as adb sees it (adb.exe under Git Bash wants a Windows path).
adb_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# ---- adb ----------------------------------------------------------------------------------

ADB=""
find_adb() {
    if command -v adb >/dev/null 2>&1; then ADB=$(command -v adb); return 0; fi
    local plat exe cache
    case "$(uname -s)" in
        Darwin) plat=darwin; cache="$HOME/Library/Caches/materialtv" ;;
        Linux) plat=linux; cache="${XDG_CACHE_HOME:-$HOME/.cache}/materialtv" ;;
        MINGW*|MSYS*|CYGWIN*) plat=windows; cache="${LOCALAPPDATA:-$HOME}/MaterialTV" ;;
        *) die "Unsupported system $(uname -s). Install Android platform-tools (adb) and run again." ;;
    esac
    exe=adb; [ "$plat" = windows ] && exe=adb.exe
    if [ -x "$cache/platform-tools/$exe" ]; then ADB="$cache/platform-tools/$exe"; return 0; fi
    command -v unzip >/dev/null 2>&1 || die "adb isn't installed and unzip is missing to unpack it. Install unzip (or Android platform-tools) and run again."
    step "Getting adb..."
    info "adb isn't installed, so downloading Google's platform-tools from dl.google.com."
    fetch "https://dl.google.com/android/repository/platform-tools-latest-$plat.zip" "$WORK/platform-tools.zip" \
        || die "Couldn't download platform-tools. Check your internet connection."
    mkdir -p "$cache" || die "Couldn't create $cache."
    rm -rf "$cache/platform-tools"
    unzip -q -o "$WORK/platform-tools.zip" -d "$cache" || die "Couldn't unpack platform-tools."
    chmod +x "$cache/platform-tools/$exe" 2>/dev/null
    ADB="$cache/platform-tools/$exe"
    ok "adb is ready (kept in $cache for next time)."
}

DEV=""
# adb never reads stdin here (it would swallow the input of "while read" loops).
adbc() { "$ADB" "$@" </dev/null; }
# Read-only command on the TV.
tvq() { adbc -s "$DEV" shell "$1" 2>&1 | tr -d '\r'; }
# Command that changes the TV (printed, not run, in dry-run mode).
tvx() {
    if [ "$DRY_RUN" = 1 ]; then printf '  %s[dry-run] adb -s %s shell %s%s\n' "$C_DIM" "$DEV" "$1" "$C_0" >&3; return 0; fi
    tvq "$1"
}
clean() { local s; s=$(printf '%s' "$1" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'); [ "$s" = "null" ] && s=""; printf '%s' "$s"; }
pkgs() { tvq "$1" | sed -n 's/^package:\([^[:space:]]*\).*/\1/p'; }
in_list() { printf '%s\n' "$2" | grep -qxF -- "$1"; }
uid_of() { tvq "dumpsys package $1 | grep -m1 -E 'userId=|appId='" | grep -oE '(userId|appId)=[0-9]+' | head -n 1 | cut -d= -f2; }
strip_quotes() { printf '%s' "$1" | tr -d "'\""; }

# restore "ns key" value   (empty or "null" -> settings delete)
restore_setting() {
    if [ -z "$2" ] || [ "$2" = "null" ]; then tvx "settings delete $1" >/dev/null
    else tvx "settings put $1 '$(strip_quotes "$2")'" >/dev/null; fi
}

connect_tv() {
    step "Connecting to the TV at $DEV..."
    local out st start now limit=45 told=0
    out=$(adbc connect "$DEV" 2>&1 | tr -d '\r')
    info "$out"
    start=$(date +%s)
    while :; do
        st=$(adbc -s "$DEV" get-state 2>&1 | tr -d '\r')
        case "$st" in device) break ;; esac
        if printf '%s' "$st$out" | grep -qi unauthorized; then
            limit=180
            if [ "$told" = 0 ]; then
                told=1
                echo
                printf '  %sLook at the TV now.%s It asks "Allow USB debugging?" for this computer.\n' "$C_B" "$C_0"
                info "Tick \"Always allow from this computer\", then select Allow."
                info "Waiting for you to accept it..."
            fi
        fi
        now=$(date +%s)
        if [ $((now - start)) -ge "$limit" ]; then
            echo
            fail "Couldn't connect to the TV at $DEV."
            echo "  Check that:" >&2
            echo "  - the TV is on and on the same network as this computer, and the IP is right" >&2
            echo "    (Settings > My Fire TV > About > Network)." >&2
            echo "  - ADB debugging is on: Settings > My Fire TV > Developer options > ADB debugging." >&2
            echo "    If Developer options is missing: Settings > My Fire TV > About, then select" >&2
            echo "    the device name 7 times." >&2
            echo "  - you accepted \"Allow USB debugging?\" on the TV." >&2
            exit 1
        fi
        sleep 3
        if [ "$told" = 0 ]; then out=$(adbc connect "$DEV" 2>&1 | tr -d '\r'); fi
    done
    ok "Connected."
}

check_device() {
    local manu sdk model fos
    manu=$(clean "$(tvq 'getprop ro.product.manufacturer')")
    sdk=$(clean "$(tvq 'getprop ro.build.version.sdk')")
    model=$(clean "$(tvq 'getprop ro.product.model')")
    fos=$(clean "$(tvq 'getprop ro.build.version.name')")
    info "TV: ${manu:-unknown} ${model:-unknown}${fos:+, $fos} (Android SDK ${sdk:-?})."
    if [ "$manu" != "Amazon" ]; then
        warn "This doesn't look like an Amazon Fire TV. MaterialTV and these lists are made for Fire OS."
    fi
    case "$sdk" in ''|*[!0-9]*) sdk=0 ;; esac
    if [ "$sdk" -lt 28 ]; then
        warn "This TV runs an Android older than 9 (Fire OS 7). MaterialTV needs Fire OS 7 or newer."
    fi
    if { [ "$manu" != "Amazon" ] || [ "$sdk" -lt 28 ]; } && [ "$YES" != 1 ]; then
        yesno "Continue anyway?" N || exit 1
    fi
}

# ---- version compare (Updater.newer) ----------------------------------------------------------

newer() {   # newer A B -> 0 when A > B
    awk -v a="$1" -v b="$2" 'function num(s){gsub(/[^0-9]/,"",s); return s==""?0:s+0}
        BEGIN{na=split(a,x,"."); nb=split(b,y,"."); m=na>nb?na:nb
              for(i=1;i<=m;i++){p=(i<=na)?num(x[i]):0; q=(i<=nb)?num(y[i]):0; if(p!=q) exit !(p>q)} exit 1}'
}

latest_version() {
    fetch_text "$API_LATEST" | grep -m1 '"tag_name"' | sed -e 's/.*"tag_name"[[:space:]]*:[[:space:]]*"//' -e 's/".*//' -e 's/^[vV]//'
}

install_apk() {   # install_apk file -> output of adb install
    if [ "$DRY_RUN" = 1 ]; then
        printf '  %s[dry-run] adb -s %s install -r %s%s\n' "$C_DIM" "$DEV" "$1" "$C_0" >&3; echo "Success"; return 0
    fi
    adbc -s "$DEV" install -r "$(adb_path "$1")" 2>&1 | tr -d '\r'
}

install_m3home() {
    if [ "$DRY_RUN" = 1 ]; then
        printf '  %s[dry-run] download %s (+ .sha256), check it, adb -s %s install -r m3home.apk%s\n' "$C_DIM" "$APK_URL" "$DEV" "$C_0" >&3
        return 0
    fi
    local apk="$WORK/m3home.apk" want got r
    info "Downloading MaterialTV from GitHub..."
    fetch "$APK_URL" "$apk" || { fail "Couldn't download MaterialTV from GitHub."; return 1; }
    want=$(fetch_text "$APK_URL.sha256" | awk 'NR==1{print tolower($1)}')
    got=$(sha256_of "$apk")
    if [ ${#want} -ne 64 ] || [ "$want" != "$got" ]; then
        rm -f "$apk"; fail "The MaterialTV download didn't match its checksum, so it wasn't installed."; return 1
    fi
    info "Checksum OK. Installing..."
    r=$(install_apk "$apk"); rm -f "$apk"
    case "$r" in *Success*) ok "MaterialTV installed."; return 0 ;; esac
    fail "MaterialTV didn't install: $r"
    case "$r" in *UPDATE_INCOMPATIBLE*|*signatures*)
        echo "  The MaterialTV on the TV is signed with a different key (a self-built copy?)." >&2
        echo "  Uninstall it first, or keep using your own build." >&2 ;;
    esac
    return 1
}

ensure_m3home() {
    step "MaterialTV app..."
    local path cur latest
    path=$(tvq "pm path $SELF" | grep '^package:')
    latest=$(latest_version)
    if [ -z "$path" ]; then
        info "MaterialTV isn't on the TV yet. Installing the latest release${latest:+ ($latest)} from GitHub."
        install_m3home || return 1
        note "Installed MaterialTV${latest:+ $latest}."
        return 0
    fi
    cur=$(tvq "dumpsys package $SELF | grep versionName" | grep -m1 -oE 'versionName=[^[:space:]]+' | cut -d= -f2)
    if [ -z "$latest" ]; then
        ok "MaterialTV ${cur:-?} is installed. (Couldn't check GitHub for a newer version.)"
    elif newer "$latest" "${cur:-0}"; then
        info "MaterialTV ${cur:-?} is installed; version $latest is out."
        if yesno "  Update MaterialTV to $latest?" Y; then
            if install_m3home; then note "Updated MaterialTV to $latest."; else warn "Carrying on with MaterialTV ${cur:-?}."; fi
        fi
    else
        ok "MaterialTV ${cur:-?} is installed and up to date."
    fi
    return 0
}

# ---- setup lists ----------------------------------------------------------------------------

LISTS="$WORK/lists"
script_dir() {
    local src="${BASH_SOURCE[0]:-}"
    [ -n "$src" ] && [ -f "$src" ] || return 1
    (cd "$(dirname "$src")" 2>/dev/null && pwd)
}

get_lists() {
    mkdir -p "$LISTS"
    local here name from_remote=0 from_local=0 localdir=""
    here=$(script_dir) && [ -d "$here/../assets/setup" ] && localdir="$here/../assets/setup"
    for name in $LIST_NAMES; do
        if fetch_text "$RAW_LISTS/$name" >"$LISTS/$name" && [ -s "$LISTS/$name" ]; then
            from_remote=1
        elif [ -n "$localdir" ] && [ -f "$localdir/$name" ]; then
            cp "$localdir/$name" "$LISTS/$name"; from_local=1
        else
            die "Couldn't get the setup list $name from GitHub, and there's no local copy next to this script."
        fi
    done
    if [ "$from_local" = 1 ] && [ "$from_remote" = 0 ]; then info "Using the setup lists in $(cd "$localdir" && pwd)."
    elif [ "$from_local" = 1 ]; then info "Using the setup lists from GitHub (some from the local copy)."
    else info "Using the setup lists from GitHub."; fi
}

# list name -> one package per line (settings lists: the whole "ns key value" line). Like Setup.list.
list() {
    awk -v whole="$(case "$1" in settings*) echo 1 ;; *) echo 0 ;; esac)" '
        { sub(/\r$/, ""); h = index($0, "#"); if (h) $0 = substr($0, 1, h - 1)
          gsub(/^[ \t]+|[ \t]+$/, ""); if ($0 == "") next
          if (whole == 1) print; else { split($0, f, /[ \t]+/); print f[1] } }' "$LISTS/$1"
}

# ---- change log (ledger.json) ---------------------------------------------------------------
# Kept flat in $FLAT, one record per line: kind<TAB>key<TAB>member<TAB>value, values still
# JSON-escaped. Kinds: s string, r raw (true/false/number), a array item, ar raw array item,
# A empty array, o object member, or raw object member, O empty object. Unknown keys survive.

json_flatten() {
    awk 'BEGIN { RS = "\001" }
    function ws() { while (i <= n && index(" \t\r\n", substr(s, i, 1))) i++ }
    function str(   c, out) { i++; out = ""
        while (i <= n) { c = substr(s, i, 1)
            if (c == "\\") { out = out c substr(s, i + 1, 1); i += 2; continue }
            if (c == "\"") { i++; return out }
            out = out c; i++ }
        return out }
    function raw(   st) { st = i; while (i <= n && !index(",}] \t\r\n", substr(s, i, 1))) i++; return substr(s, st, i - st) }
    function value(key, member, inner,   c, v) { ws(); c = substr(s, i, 1)
        if (c == "\"") { v = str(); print (inner == "o" ? "o" : (inner == "a" ? "a" : "s")) "\t" key "\t" member "\t" v; return }
        if (c == "[" && inner == "") { array(key); return }
        if (c == "{" && inner == "") { object(key); return }
        if (c == "[" || c == "{") { skip(); return }
        v = raw(); if (v == "") { i++; return }
        print (inner == "o" ? "or" : (inner == "a" ? "ar" : "r")) "\t" key "\t" member "\t" v }
    function skip(   d, c) { d = 0
        while (i <= n) { c = substr(s, i, 1)
            if (c == "\"") { str(); continue }
            if (c == "[" || c == "{") d++
            if (c == "]" || c == "}") { d--; if (d == 0) { i++; return } }
            i++ } }
    function array(key,   cnt, last) { i++; cnt = 0
        while (i <= n) { ws(); if (substr(s, i, 1) == "]") { i++; break }
            last = i; value(key, "", "a"); cnt++; ws()
            if (substr(s, i, 1) == ",") i++
            if (i == last) i++ }
        if (cnt == 0) print "A\t" key "\t\t" }
    function object(key,   cnt, m, last) { i++; cnt = 0
        while (i <= n) { ws(); if (substr(s, i, 1) == "}") { i++; break }
            last = i
            if (substr(s, i, 1) != "\"") { i++; continue }
            m = str(); ws(); if (substr(s, i, 1) == ":") i++
            value(key, m, "o"); cnt++; ws()
            if (substr(s, i, 1) == ",") i++
            if (i == last) i++ }
        if (cnt == 0) print "O\t" key "\t\t" }
    { s = $0; n = length(s); i = 1; ws(); if (substr(s, i, 1) != "{") exit; i++
      while (i <= n) { ws(); c = substr(s, i, 1)
          if (c == "}") break
          if (c != "\"") { i++; continue }
          k = str(); ws(); if (substr(s, i, 1) == ":") i++
          value(k, "", ""); ws(); if (substr(s, i, 1) == ",") i++ } }'
}

json_esc() { printf '%s' "$1" | awk 'BEGIN{RS="\001"} { gsub(/\\/, "\\\\"); gsub(/"/, "\\\""); gsub(/\t/, "\\t"); gsub(/\r/, ""); gsub(/\n/, "\\n"); printf "%s", $0 }'; }

json_unesc() {
    awk 'function hex(h,   k, v) { v = 0; h = tolower(h); for (k = 1; k <= 4; k++) v = v * 16 + index("0123456789abcdef", substr(h, k, 1)) - 1; return v }
    { s = $0; n = length(s); out = ""
      for (i = 1; i <= n; i++) { c = substr(s, i, 1)
          if (c == "\\" && i < n) { d = substr(s, ++i, 1)
              if (d == "n") out = out "\n"; else if (d == "t") out = out "\t"
              else if (d == "r" || d == "b" || d == "f") out = out
              else if (d == "u") { v = hex(substr(s, i + 1, 4)); i += 4; out = out ((v >= 32 && v < 127) ? sprintf("%c", v) : "?") }
              else out = out d }
          else out = out c }
      print out }'
}

led_has() { K="$1" awk -F'\t' '$2 == ENVIRON["K"] { f = 1 } END { exit !f }' "$FLAT"; }
led_get() { K="$1" awk -F'\t' '$2 == ENVIRON["K"] && ($1 == "s" || $1 == "r") { print $4; exit }' "$FLAT" | json_unesc; }
led_remember() { led_has "$1" || printf 's\t%s\t\t%s\n' "$1" "$(json_esc "$2")" >>"$FLAT"; }
led_set_raw() {
    K="$1" awk -F'\t' '$2 != ENVIRON["K"]' "$FLAT" >"$FLAT.tmp" && mv "$FLAT.tmp" "$FLAT"
    printf 'r\t%s\t\t%s\n' "$1" "$2" >>"$FLAT"
}
led_arr_add() {
    local e; e=$(json_esc "$2")
    K="$1" V="$e" awk -F'\t' '$1 == "a" && $2 == ENVIRON["K"] && $4 == ENVIRON["V"] { f = 1 } END { exit !f }' "$FLAT" && return 0
    K="$1" awk -F'\t' '!($1 == "A" && $2 == ENVIRON["K"])' "$FLAT" >"$FLAT.tmp" && mv "$FLAT.tmp" "$FLAT"
    printf 'a\t%s\t\t%s\n' "$1" "$e" >>"$FLAT"
}
led_arr_items() { K="$1" awk -F'\t' '($1 == "a" || $1 == "ar") && $2 == ENVIRON["K"] { print $4 }' "$FLAT" | json_unesc; }
led_obj_has() { K="$1" M="$(json_esc "$2")" awk -F'\t' '($1 == "o" || $1 == "or") && $2 == ENVIRON["K"] && $3 == ENVIRON["M"] { f = 1 } END { exit !f }' "$FLAT"; }
led_obj_remember() {
    led_obj_has "$1" "$2" && return 0
    K="$1" awk -F'\t' '!($1 == "O" && $2 == ENVIRON["K"])' "$FLAT" >"$FLAT.tmp" && mv "$FLAT.tmp" "$FLAT"
    printf 'o\t%s\t%s\t%s\n' "$1" "$(json_esc "$2")" "$(json_esc "$3")" >>"$FLAT"
}
# Prints "member<TAB>value" per line, unescaped.
led_obj_items() { K="$1" awk -F'\t' '($1 == "o" || $1 == "or") && $2 == ENVIRON["K"] { print $3 "\t" $4 }' "$FLAT" | json_unesc; }
led_count() { K="$1" awk -F'\t' '$2 == ENVIRON["K"] && ($1 == "a" || $1 == "ar" || $1 == "o" || $1 == "or") { n++ } END { print n + 0 }' "$FLAT"; }

led_json() {
    awk -F'\t' '
    { k = $2; if (!(k in seen)) { seen[k] = 1; order[++n] = k }
      t = $1
      if (t == "s") { typ[k] = "v"; val[k] = "\"" $4 "\"" }
      else if (t == "r") { typ[k] = "v"; val[k] = $4 }
      else if (t == "A") { typ[k] = "a" }
      else if (t == "O") { typ[k] = "o" }
      else if (t == "a" || t == "ar") { typ[k] = "a"; it = (t == "a") ? "\"" $4 "\"" : $4; items[k] = (cnt[k]++ ? items[k] "," : "") it }
      else if (t == "o" || t == "or") { typ[k] = "o"; it = "\"" $3 "\":" ((t == "o") ? "\"" $4 "\"" : $4); items[k] = (cnt[k]++ ? items[k] "," : "") it } }
    END { printf "{"
          for (j = 1; j <= n; j++) { k = order[j]; if (j > 1) printf ","
              printf "\"%s\":", k
              if (typ[k] == "a") printf "[%s]", items[k]
              else if (typ[k] == "o") printf "{%s}", items[k]
              else printf "%s", val[k] }
          print "}" }' "$FLAT"
}

LEDGER_FOUND=0
read_ledger() {
    local raw
    raw=$(adbc -s "$DEV" shell "cat $LEDGER_PATH 2>/dev/null" 2>/dev/null | tr -d '\r')
    case "$raw" in
        "{"*) printf '%s' "$raw" | json_flatten >"$FLAT"; LEDGER_FOUND=1 ;;
        *) : >"$FLAT"; LEDGER_FOUND=0 ;;
    esac
}

save_ledger() {
    local json; json=$(led_json)
    if [ "$DRY_RUN" = 1 ]; then
        printf '  %s[dry-run] adb -s %s shell mkdir -p %s%s\n' "$C_DIM" "$DEV" "$LEDGER_DIR" "$C_0" >&3
        printf '  %s[dry-run] adb -s %s push ledger.json %s%s\n' "$C_DIM" "$DEV" "$LEDGER_PATH" "$C_0" >&3
        printf '  %s[dry-run] ledger.json would be: %s%s\n' "$C_DIM" "$json" "$C_0" >&3
        return 0
    fi
    printf '%s\n' "$json" >"$WORK/ledger.json"
    tvq "mkdir -p $LEDGER_DIR" >/dev/null
    if adbc -s "$DEV" push "$(adb_path "$WORK/ledger.json")" "$LEDGER_PATH" >/dev/null 2>&1; then
        info "Saved the change log on the TV, so Undo can put everything back."
    else
        warn "Couldn't save the change log on the TV ($LEDGER_PATH). Undo will fall back to dumbtv's restore lists."
        return 1
    fi
}

# ---- setup steps (Setup.java) ---------------------------------------------------------------

do_permissions() {
    step "MaterialTV permissions..."
    local a11y ss
    a11y=$(clean "$(tvq 'settings get secure enabled_accessibility_services')")
    led_remember a11y_before "$a11y"
    case "$a11y" in
        *"$GUARD"*) ;;
        "") tvx "settings put secure enabled_accessibility_services '$GUARD'" >/dev/null ;;
        *) tvx "settings put secure enabled_accessibility_services '$a11y:$GUARD'" >/dev/null ;;
    esac
    tvx "settings put secure accessibility_enabled 1" >/dev/null
    tvx "pm grant $SELF android.permission.WRITE_SECURE_SETTINGS" >/dev/null
    tvx "cmd notification allow_listener $LISTENER" >/dev/null
    tvx "appops set $SELF REQUEST_INSTALL_PACKAGES allow" >/dev/null
    tvx "dumpsys deviceidle whitelist +$SELF" >/dev/null
    ss=$(clean "$(tvq 'settings get secure screensaver_components')")
    led_remember screensaver_before "$ss"
    tvx "settings put secure screensaver_components $DREAM" >/dev/null
    tvx "settings put secure screensaver_enabled 1" >/dev/null
    led_set_raw permissions true
    ok "Home button, screensaver, now playing and updates are set up."
    note "MaterialTV permissions granted, MaterialTV is the screensaver."
}

do_stay_on() {
    step "Screensaver timeout..."
    led_remember stay_on_before "$STAY_ON"
    tvx "settings put global stay_on_while_plugged_in 0" >/dev/null
    ok "The TV no longer stays awake while plugged in, so the screensaver can start."
    note "Turned off \"stay awake while plugged in\" (was $STAY_ON)."
}

do_debloat() {
    step "Debloat and privacy (dumbtv lists)..."
    local installed disabled never targets p r count=0 bg quiet=0 uid line ns key val n=0
    installed=$(pkgs "pm list packages")
    disabled=$(pkgs "pm list packages -d")
    never=$(list never-disable.txt)
    targets=$(list packages.txt)
    [ "$KEEP_ALEXA" = 1 ] || targets="$targets
$(list alexa.txt)"
    for p in $targets; do
        if in_list "$p" "$never" || ! in_list "$p" "$installed" || in_list "$p" "$disabled"; then continue; fi
        r=$(tvx "pm disable-user --user 0 $p")
        if [ "$DRY_RUN" = 1 ] || printf '%s' "$r" | grep -q disabled; then led_arr_add disabled "$p"; count=$((count + 1)); fi
    done
    ok "Switched off $count Amazon ad and tracking apps."

    bg=$(clean "$(tvq 'cmd netpolicy get restrict-background')")
    case "$bg" in *enabled*) led_remember data_saver_before true ;; *) led_remember data_saver_before false ;; esac
    tvx "cmd netpolicy set restrict-background true" >/dev/null
    for p in $(list protected.txt); do
        in_list "$p" "$installed" || continue
        tvx "appops set $p RUN_IN_BACKGROUND ignore; appops set $p RUN_ANY_IN_BACKGROUND ignore; am force-stop $p" >/dev/null
        led_arr_add background_off "$p"; quiet=$((quiet + 1))
        uid=$(uid_of "$p")
        if [ -n "$uid" ] && [ "$uid" -ge 10000 ]; then
            tvx "cmd netpolicy add restrict-background-blacklist $uid" >/dev/null
            led_arr_add blacklisted_uids "$uid"
        fi
    done
    ok "Limited $quiet apps Amazon won't let you switch off."

    while IFS= read -r line; do
        set -- $line
        [ $# -ge 3 ] || continue
        ns="$1"; key="$2"; val="$3"
        if ! led_obj_has settings_before "$ns $key"; then
            led_obj_remember settings_before "$ns $key" "$(clean "$(tvq "settings get $ns $key")")"
        fi
        tvx "settings put $ns $key $val" >/dev/null
        n=$((n + 1))
    done <<EOF
$(list settings.txt)
EOF
    ok "Applied $n privacy settings."
    note "Debloat: $count apps switched off, $quiet protected apps limited, $n privacy settings."
}

do_smarttube() {
    step "SmartTube..."
    local installed had=0 p apk r
    installed=$(pkgs "pm list packages")
    for p in $SMARTTUBE_PKGS; do in_list "$p" "$installed" && had=1; done
    if [ "$had" = 0 ]; then
        apk="$WORK/smarttube.apk"
        if [ "$DRY_RUN" = 1 ]; then
            printf '  %s[dry-run] download %s, check SHA-256 %s%s\n' "$C_DIM" "$SMARTTUBE_URL" "$SMARTTUBE_SHA" "$C_0" >&3
        else
            info "Downloading SmartTube from GitHub..."
            fetch "$SMARTTUBE_URL" "$apk" || { fail "Couldn't download SmartTube."; return 1; }
            if [ "$(sha256_of "$apk")" != "$SMARTTUBE_SHA" ]; then rm -f "$apk"; fail "SmartTube download didn't match its checksum."; return 1; fi
        fi
        r=$(install_apk "$apk"); rm -f "$apk"
        case "$r" in *Success*) ;; *) fail "SmartTube didn't install: $r"; return 1 ;; esac
        led_arr_add installed "org.smarttube.stable"
        ok "Installed SmartTube."
        note "Installed SmartTube."
    else
        ok "SmartTube was already installed."
    fi
    if in_list "$YOUTUBE" "$installed" && ! in_list "$YOUTUBE" "$(pkgs 'pm list packages -d')"; then
        tvx "pm disable-user --user 0 $YOUTUBE" >/dev/null
        led_arr_add disabled "$YOUTUBE"
        ok "Switched off the Amazon YouTube app."
        note "Switched off the Amazon YouTube app."
    fi
}

do_tailscale() {
    step "Tailscale..."
    local name url want apk r
    if ! in_list "$TAILSCALE" "$(pkgs 'pm list packages')"; then
        name=$(fetch_text "https://pkgs.tailscale.com/stable/" | grep -oE 'tailscale-android-universal-[0-9.]+\.apk' | head -n 1)
        [ -n "$name" ] || { fail "Couldn't find Tailscale's Android download."; return 1; }
        url="https://pkgs.tailscale.com/stable/$name"
        want=$(fetch_text "$url.sha256" | awk 'NR==1{print tolower($1)}')
        apk="$WORK/tailscale.apk"
        if [ "$DRY_RUN" = 1 ]; then
            printf '  %s[dry-run] download %s, check SHA-256 %s%s\n' "$C_DIM" "$url" "${want:-?}" "$C_0" >&3
        else
            info "Downloading ${name%.apk} from pkgs.tailscale.com..."
            fetch "$url" "$apk" || { fail "Couldn't download Tailscale."; return 1; }
            if [ ${#want} -ne 64 ] || [ "$want" != "$(sha256_of "$apk")" ]; then rm -f "$apk"; fail "Tailscale download didn't match its checksum."; return 1; fi
        fi
        r=$(install_apk "$apk"); rm -f "$apk"
        case "$r" in *Success*) ;; *) fail "Tailscale didn't install: $r"; return 1 ;; esac
        led_arr_add installed "$TAILSCALE"
        ok "Installed ${name%.apk}."
        note "Installed ${name%.apk}."
    else
        ok "Tailscale was already installed."
    fi
    led_remember vpn_before "$(clean "$(tvq 'settings get secure always_on_vpn_app')")"
    tvx "appops set $TAILSCALE ACTIVATE_VPN allow; settings put secure always_on_vpn_app $TAILSCALE; dumpsys deviceidle whitelist +$TAILSCALE" >/dev/null
    ok "Tailscale is the always-on VPN. Open Tailscale once on the TV to sign in."
    note "Tailscale is the always-on VPN (open it once on the TV to sign in)."
}

run_setup() {
    ensure_m3home || die "MaterialTV isn't installed, so setup can't continue."
    get_lists
    read_ledger
    if [ "$LEDGER_FOUND" = 1 ]; then info "Found the change log from an earlier setup; it will be kept and added to."; fi

    STAY_ON=$(clean "$(tvq 'settings get global stay_on_while_plugged_in')")
    echo
    step "Choose what to set up"
    CH_PERM=0; CH_DEBLOAT=0; KEEP_ALEXA=1; CH_ST=0; CH_TS=0; CH_STAY=0
    yesno "  MaterialTV permissions (Home button, screensaver, now playing, updates)?" Y && CH_PERM=1
    if yesno "  Debloat and privacy (switch off Amazon ads and tracking, dumbtv lists)?" Y; then
        CH_DEBLOAT=1
        yesno "  Keep Alexa and the voice remote?" Y || KEEP_ALEXA=0
    fi
    yesno "  Install SmartTube instead of the Amazon YouTube app?" N && CH_ST=1
    yesno "  Install Tailscale and make it the always-on VPN?" N && CH_TS=1
    case "$STAY_ON" in
        ""|0) ;;
        *) info "The TV is set to stay awake while plugged in, which stops any screensaver."
           yesno "  Turn that off?" Y && CH_STAY=1 ;;
    esac
    if [ $((CH_PERM + CH_DEBLOAT + CH_ST + CH_TS + CH_STAY)) -eq 0 ]; then
        echo; info "Nothing chosen, nothing changed."; return 0
    fi
    if [ "$YES" != 1 ]; then echo; yesno "Start now?" Y || { info "Cancelled. Nothing changed."; exit 0; }; fi
    echo

    local rc=0
    while :; do
        if [ "$CH_PERM" = 1 ]; then do_permissions || { rc=1; break; }; fi
        if [ "$CH_STAY" = 1 ]; then do_stay_on || { rc=1; break; }; fi
        if [ "$CH_DEBLOAT" = 1 ]; then do_debloat || { rc=1; break; }; fi
        if [ "$CH_ST" = 1 ]; then do_smarttube || { rc=1; break; }; fi
        if [ "$CH_TS" = 1 ]; then do_tailscale || { rc=1; break; }; fi
        break
    done
    save_ledger || rc=1
    echo
    if [ "$rc" = 0 ]; then step "Setup finished."; else fail "Setup stopped early. What ran so far is in the change log."; fi
    [ -n "$SUMMARY" ] && printf 'Summary:%s\n' "$SUMMARY"
    if [ "$rc" = 0 ]; then
        echo "To put everything back later: run this script with --undo (or use Settings > Fire TV setup > Undo in MaterialTV)."
    fi
    [ "$DRY_RUN" = 1 ] && printf '%s(Dry run: nothing on the TV was changed.)%s\n' "$C_WARN" "$C_0"
    return $rc
}

# ---- undo (Setup.undo) ----------------------------------------------------------------------

run_undo() {
    get_lists
    read_ledger
    local logged=$LEDGER_FOUND
    if [ "$logged" = 1 ]; then info "Found the change log from setup; undo will put back exactly what it changed."
    else info "No change log on the TV, so undo uses dumbtv's restore lists."; fi

    echo
    step "Choose what to undo"
    U_DEBLOAT=0; U_ST=0; U_TS=0; U_SELF=0
    yesno "  Undo debloat and privacy (apps back on, settings restored)?" Y && U_DEBLOAT=1
    yesno "  Remove SmartTube (if setup installed it) and bring back Amazon's YouTube app?" Y && U_ST=1
    yesno "  Remove Tailscale (if setup installed it) and clear the always-on VPN?" Y && U_TS=1
    yesno "  Uninstall MaterialTV too (Amazon's home screen comes back)?" N && U_SELF=1
    if [ $((U_DEBLOAT + U_ST + U_TS + U_SELF)) -eq 0 ]; then echo; info "Nothing chosen, nothing changed."; return 0; fi
    if [ "$YES" != 1 ]; then echo; yesno "Undo these now?" Y || { info "Cancelled. Nothing changed."; exit 0; }; fi
    echo

    local disabled toenable p n s uid key val all stay ss a11y rest
    if [ "$U_DEBLOAT" = 1 ]; then
        step "Undoing debloat and privacy..."
        disabled=$(pkgs "pm list packages -d")
        toenable=$(led_arr_items disabled)
        if [ "$logged" != 1 ]; then toenable="$toenable
$(list packages.txt)
$(list alexa.txt)"; fi
        n=0
        for p in $toenable; do
            if [ "$p" = "$YOUTUBE" ] && [ "$U_ST" != 1 ]; then continue; fi
            if in_list "$p" "$disabled"; then tvx "pm enable --user 0 $p" >/dev/null; n=$((n + 1)); fi
        done
        ok "Switched $n apps back on."
        for p in $(list protected.txt); do
            tvx "appops set $p RUN_IN_BACKGROUND allow; appops set $p RUN_ANY_IN_BACKGROUND allow" >/dev/null
            uid=$(uid_of "$p")
            [ -n "$uid" ] && tvx "cmd netpolicy remove restrict-background-blacklist $uid" >/dev/null
        done
        if [ "$(led_get data_saver_before)" = "true" ]; then tvx "cmd netpolicy set restrict-background true" >/dev/null
        else tvx "cmd netpolicy set restrict-background false" >/dev/null; fi
        s=0
        if [ "$(led_count settings_before)" -gt 0 ]; then
            while IFS="$(printf '\t')" read -r key val; do
                [ -n "$key" ] || continue
                restore_setting "$key" "$val"; s=$((s + 1))
            done <<EOF
$(led_obj_items settings_before)
EOF
        else
            while IFS= read -r all; do
                set -- $all
                [ $# -ge 3 ] || continue
                tvx "settings put $1 $2 $3" >/dev/null; s=$((s + 1))
            done <<EOF
$(list settings.restore.txt)
EOF
        fi
        ok "Restored $s settings and background limits."
        note "Undid debloat: $n apps back on, $s settings restored."
    fi
    if [ "$U_DEBLOAT" = 1 ] || [ "$U_SELF" = 1 ]; then
        if led_has stay_on_before; then
            stay=$(led_get stay_on_before)
            restore_setting "global stay_on_while_plugged_in" "$stay"
            ok "Put \"stay awake while plugged in\" back to ${stay:-the default}."
        fi
    fi
    if [ "$U_ST" = 1 ]; then
        all=$(pkgs "pm list packages")
        for p in $SMARTTUBE_PKGS; do
            if in_list "$p" "$all" && { [ "$logged" != 1 ] || in_list "$p" "$(led_arr_items installed)"; }; then
                tvx "pm uninstall $p" >/dev/null
                step "Removed SmartTube."
                note "Removed SmartTube."
            fi
        done
        tvx "pm enable --user 0 $YOUTUBE" >/dev/null
    fi
    if [ "$U_TS" = 1 ]; then
        restore_setting "secure always_on_vpn_app" "$(led_get vpn_before)"
        if in_list "$TAILSCALE" "$(pkgs 'pm list packages')" && { [ "$logged" != 1 ] || in_list "$TAILSCALE" "$(led_arr_items installed)"; }; then
            step "Removed Tailscale's always-on VPN; uninstalling Tailscale..."
            tvx "pm uninstall $TAILSCALE" >/dev/null
            note "Removed Tailscale."
        else
            step "Tailscale is no longer the always-on VPN."
            note "Tailscale is no longer the always-on VPN."
        fi
    fi
    if [ "$U_SELF" = 1 ]; then
        step "Removing MaterialTV and its permissions..."
        ss=$(led_get screensaver_before)
        case "$ss" in ""|*"$SELF"*) ss=$(clean "$(tvq 'settings get secure screensaver_default_component')") ;; esac
        restore_setting "secure screensaver_components" "$ss"
        a11y=$(clean "$(tvq 'settings get secure enabled_accessibility_services')")
        rest=$(printf '%s' "$a11y" | awk -v g="$GUARD" '{ while ((k = index($0, ":" g)) > 0) $0 = substr($0, 1, k - 1) substr($0, k + length(g) + 1)
                                                      while ((k = index($0, g ":")) > 0) $0 = substr($0, 1, k - 1) substr($0, k + length(g) + 1)
                                                      while ((k = index($0, g)) > 0) $0 = substr($0, 1, k - 1) substr($0, k + length(g)); print }')
        restore_setting "secure enabled_accessibility_services" "$rest"
        [ -z "$rest" ] && tvx "settings put secure accessibility_enabled 0" >/dev/null
        tvx "cmd notification disallow_listener $LISTENER; dumpsys deviceidle whitelist -$SELF" >/dev/null
        tvx "rm -f $LEDGER_PATH" >/dev/null
        step "Uninstalling MaterialTV. Amazon's home screen comes back. Goodbye!"
        tvx "pm uninstall $SELF" >/dev/null
        note "Uninstalled MaterialTV (its change log went with it)."
    else
        tvx "rm -f $LEDGER_PATH" >/dev/null
        step "Undo finished."
        [ "$logged" = 1 ] && note "Removed the change log from the TV."
    fi
    [ -n "$SUMMARY" ] && printf 'Summary:%s\n' "$SUMMARY"
    [ "$DRY_RUN" = 1 ] && printf '%s(Dry run: nothing on the TV was changed.)%s\n' "$C_WARN" "$C_0"
    return 0
}

# ---- main ---------------------------------------------------------------------------------

printf '%sMaterialTV - Fire TV %s%s\n' "$C_B" "$( [ "$UNDO" = 1 ] && echo undo || echo setup )" "$C_0"
[ "$DRY_RUN" = 1 ] && printf '%sDry run: commands that would change the TV are printed, not run.%s\n' "$C_WARN" "$C_0"

if [ -z "$TV" ]; then
    echo "Enter your Fire TV's IP address."
    echo "  Find it on the TV in Settings > My Fire TV > About > Network,"
    echo "  or on MaterialTV's setup screen. Example: 192.168.1.50"
    printf 'Fire TV IP: '
    TV=$(read_line)
fi
TV=$(printf '%s' "$TV" | tr -d '[:space:]')
[ -n "$TV" ] || die "No IP address given."
case "$TV" in *:*) DEV="$TV" ;; *) DEV="$TV:5555" ;; esac

find_adb
connect_tv
check_device
echo
if [ "$UNDO" = 1 ]; then run_undo; else run_setup; fi
exit $?
