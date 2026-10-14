#!/usr/bin/env bash
# Genius F1 helper for Linux.
#
#   bash run.sh                 interactive menu
#   bash run.sh setup           check Bluetooth + create the Python environment
#   bash run.sh report [ADDR]   full diagnostic report (start here)
#   bash run.sh scan | find | info | hr | spo2 | bp | steps | sync | listen [ADDR]
#   bash run.sh notify ADDR "text"
#   bash run.sh raw ADDR HEX
#   bash run.sh reset ADDR      FACTORY RESET the watch (asks you to type RESET)
#   bash run.sh debug [ADDR]    record Bluetooth's own log while connecting (needs sudo)
#   bash run.sh fix-mtu         stop BlueZ sending the MTU request the F1 never answers (undo-mtu)
set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")"
# Optional, git-ignored: F1_ADDRESS=XX:XX:XX:XX:XX:XX (keeps the watch address out of the repo)
[ -f .env ] && . ./.env

VENV=".venv-linux"   # separate from the Windows .venv, which doesn't work on Linux
PY="$VENV/bin/python"

ok()   { printf '  [ OK ] %s\n' "$*"; }
warn() { printf '  [WARN] %s\n' "$*"; }
fail() { printf '  [FAIL] %s\n' "$*"; }

check_system() {
    echo "== Checking system"
    local problems=0

    if command -v python3 >/dev/null; then
        if python3 -c 'import sys; sys.exit(sys.version_info < (3, 10))'; then
            ok "python3 $(python3 -c 'import platform; print(platform.python_version())')"
        else
            fail "Python 3.10+ needed (bleak 3 requirement) (found $(python3 --version 2>&1))"; problems=1
        fi
    else
        fail "python3 not installed -> sudo apt install python3 python3-venv  (Fedora: sudo dnf install python3)"
        return 1
    fi

    if command -v bluetoothctl >/dev/null; then
        ok "bluez $(bluetoothctl --version 2>/dev/null | awk '{print $2}')"
    else
        fail "bluez not installed -> sudo apt install bluez  (Fedora: dnf, Arch: pacman -S bluez bluez-utils)"; problems=1
    fi

    if command -v systemctl >/dev/null; then
        if systemctl is-active --quiet bluetooth; then
            ok "bluetooth service running"
        else
            fail "bluetooth service not running -> sudo systemctl enable --now bluetooth"; problems=1
        fi
    fi

    if command -v rfkill >/dev/null && rfkill list bluetooth 2>/dev/null | grep -q 'blocked: yes'; then
        fail "Bluetooth is blocked -> rfkill unblock bluetooth"; problems=1
    fi

    if command -v bluetoothctl >/dev/null; then
        local show
        show="$(timeout 5 bluetoothctl show 2>/dev/null)"
        if [ -z "$show" ] || grep -q 'No default controller' <<<"$show"; then
            fail "no Bluetooth adapter found (check 'lsusb' / dongle)"; problems=1
        elif grep -q 'Powered: yes' <<<"$show"; then
            ok "adapter $(awk '/^Controller/{print $2; exit}' <<<"$show") powered on"
        else
            warn "adapter is off -> bluetoothctl power on"; problems=1
        fi
    fi
    return $problems
}

setup() {
    check_system || warn "fix the items above, then run setup again (continuing with Python setup anyway)"
    echo "== Python environment ($VENV)"
    if [ ! -x "$PY" ]; then
        if ! python3 -m venv "$VENV"; then
            rm -rf "$VENV"
            fail "couldn't create venv -> sudo apt install python3-venv   (then rerun: bash run.sh setup)"
            exit 1
        fi
    fi
    "$PY" -m pip install -q --upgrade pip
    if "$PY" -m pip install -q -r requirements.txt; then
        ok "bleak $("$PY" -c 'from importlib.metadata import version; print(version("bleak"))')"
    else
        fail "pip install failed (no internet?)"; exit 1
    fi
    echo "Setup done. Next: bash run.sh report"
}

ensure_ready() {
    if [ ! -x "$PY" ] || ! "$PY" -c 'import bleak' 2>/dev/null; then
        echo "First run - setting up."
        setup
    fi
}

debug() {
    # Record BlueZ's own log (btmon) while connecting, to see WHY the link drops.
    local addr="${1:-${F1_ADDRESS:-}}"
    [ -n "$addr" ] || { fail "debug needs the watch address: bash run.sh debug XX:XX:XX:XX:XX:XX (or set F1_ADDRESS in tools/.env)"; exit 1; }
    mkdir -p reports
    local out="reports/btdebug_$(date +%Y%m%d_%H%M%S).txt"
    local since; since="$(date '+%Y-%m-%d %H:%M:%S')"
    if ! command -v btmon >/dev/null; then
        fail "btmon not found -> sudo apt install bluez   (it ships with bluez)"; exit 1
    fi
    echo "btmon needs root to read the Bluetooth log - enter your password if asked."
    sudo -v || exit 1
    rfkill unblock bluetooth 2>/dev/null; bluetoothctl power on >/dev/null 2>&1; sleep 1
    sudo btmon > "$out.btmon" 2>&1 &
    sleep 2

    {
        echo "# Bluetooth debug log for $addr  ($(date))"
        echo; echo "## bluetoothctl info (before)"; bluetoothctl info "$addr" 2>&1
        echo; echo "## Test A: BlueZ alone (bluetoothctl connect)"
        timeout 20 bluetoothctl --timeout 12 scan on >/dev/null 2>&1
        timeout 40 bluetoothctl --timeout 30 connect "$addr" 2>&1
        sleep 15
        echo; echo "## bluetoothctl info (15 s after connect)"; bluetoothctl info "$addr" 2>&1
        timeout 10 bluetoothctl disconnect "$addr" >/dev/null 2>&1
        sleep 3
        echo; echo "## Test B: the app (genius_f1.py info)"
    } 2>&1 | tee "$out"
    ensure_ready
    timeout 150 "$PY" genius_f1.py info "$addr" --secs 5 2>&1 | tail -40 | tee -a "$out"

    sleep 2
    sudo pkill -INT -x btmon; sleep 1
    {
        echo; echo "## Disconnect reasons seen by btmon"
        grep -n -A4 -E "Disconnect Complete|Disconnect \(0x01\|0x0006\)|Connection Failed|LE Enhanced Connection Complete|LE Connection Complete" "$out.btmon" | grep -E "Disconnect|Reason|Status|Connection Complete|Handle" | head -60
        echo; echo "## bluetoothd log"
        sudo journalctl -u bluetooth --since "$since" --no-pager 2>&1 | tail -60
        echo; echo "## Full btmon log (last 1500 lines)"
        tail -1500 "$out.btmon"
    } >> "$out"
    rm -f "$out.btmon"
    echo
    echo "Debug log written to: $out   - send this file back."
}

fix_mtu() {
    # The F1 doesn't answer BlueZ's "Exchange MTU" request, so BlueZ never gets as far
    # as discovering services. With ExchangeMTU = 23 BlueZ skips that request.
    local conf=/etc/bluetooth/main.conf
    sudo -v || exit 1
    [ -f "$conf" ] || printf '[General]\n' | sudo tee "$conf" >/dev/null
    [ -f "$conf.bak-before-f1" ] || sudo cp "$conf" "$conf.bak-before-f1"
    if grep -qE '^[[:space:]]*#?[[:space:]]*ExchangeMTU' "$conf"; then
        sudo sed -i -E 's/^[[:space:]]*#?[[:space:]]*ExchangeMTU[[:space:]]*=.*/ExchangeMTU = 23/' "$conf"
    elif grep -q '^\[GATT\]' "$conf"; then
        sudo sed -i '/^\[GATT\]/a ExchangeMTU = 23' "$conf"
    else
        printf '\n[GATT]\nExchangeMTU = 23\n' | sudo tee -a "$conf" >/dev/null
    fi
    sudo systemctl restart bluetooth; sleep 2
    bluetoothctl power on >/dev/null 2>&1
    ok "set: $(grep -nE '^ExchangeMTU' "$conf")   (backup: $conf.bak-before-f1)"
    echo "Undo any time with: bash run.sh undo-mtu"
}

undo_mtu() {
    local conf=/etc/bluetooth/main.conf
    sudo sed -i -E 's/^ExchangeMTU = 23$/#ExchangeMTU = 517/' "$conf" && sudo systemctl restart bluetooth
    ok "restored the default MTU setting"
}

menu() {
    echo "Genius F1 tools"
    echo "  1) setup    check Bluetooth, install Python packages"
    echo "  2) report   full diagnostic report (recommended)"
    echo "  3) report --quick   (skip slow heart-rate / SpO2 / sync tests)"
    echo "  4) scan     list nearby Bluetooth devices"
    echo "  5) find     make the watch vibrate"
    echo "  6) info     set time, show firmware/battery"
    echo "  7) hr       live heart rate (Ctrl+C to stop)"
    echo "  8) sync     download last 7 days of history"
    echo "  9) listen   print everything the watch sends"
    echo "  s) spo2     one blood-oxygen reading (keep still, up to 60 s)"
    echo "  b) bp       one blood-pressure reading (keep still, up to 60 s)"
    echo "  t) steps    today's step count"
    echo "  q) quit"
    read -rp "Choice: " c
    case "$c" in
        1) set -- setup ;;   2) set -- report ;;  3) set -- report --quick ;;
        4) set -- scan ;;    5) set -- find ;;    6) set -- info ;;
        7) set -- hr ;;      8) set -- sync ;;    9) set -- listen ;;
        s) set -- spo2 ;;    b) set -- bp ;;      t) set -- steps ;;
        *) exit 0 ;;
    esac
    main "$@"
}

main() {
    local action="${1:-}"; shift || true
    case "$action" in
        "")      menu ;;
        setup)   setup ;;
        check)   check_system ;;
        report)  ensure_ready; "$PY" f1_report.py "$@" ;;
        scan|find|info|hr|spo2|bp|steps|notify|sync|listen|raw|reset)
                 ensure_ready; "$PY" genius_f1.py "$action" "$@" ;;
        debug)   debug "$@" ;;
        fix-mtu) fix_mtu ;;
        undo-mtu) undo_mtu ;;
        -h|--help|help) sed -n '2,13p' "$0" ;;
        *)       echo "Unknown action '$action'"; sed -n '2,13p' "$0"; exit 1 ;;
    esac
}

main "$@"
