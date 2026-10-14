"""
Genius F1 diagnostic report.

Run this on the machine that has Bluetooth. It:
  1. records the Bluetooth/OS environment,
  2. scans for nearby BLE devices and picks the watch,
  3. dumps every GATT service/characteristic and reads what is readable,
  4. sends a list of safe probe commands and records every reply,
  5. writes reports/f1_report_<timestamp>.md (+ .json with the raw data).

Usage:
  python f1_report.py                    # auto-pick the watch
  python f1_report.py XX:XX:XX:XX:XX:XX  # specific address
  python f1_report.py --quick            # skip the slow heart-rate/SpO2/sync probes
  python f1_report.py --yes              # never ask questions (no "did it vibrate?")

Never sends factory reset (0x23) or reboot (0xFF).
"""
import argparse
import asyncio
import datetime as dt
import json
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path

import genius_f1 as g

try:
    import bleak
    from bleak import BleakClient, BleakScanner
except ImportError:
    bleak = None

DEVICE_INFO_CHARS = {
    "00002a29-0000-1000-8000-00805f9b34fb": "Manufacturer",
    "00002a24-0000-1000-8000-00805f9b34fb": "Model number",
    "00002a25-0000-1000-8000-00805f9b34fb": "Serial number",
    "00002a26-0000-1000-8000-00805f9b34fb": "Firmware revision",
    "00002a27-0000-1000-8000-00805f9b34fb": "Hardware revision",
    "00002a28-0000-1000-8000-00805f9b34fb": "Software revision",
    "00002a00-0000-1000-8000-00805f9b34fb": "Device name",
    "00002a19-0000-1000-8000-00805f9b34fb": "Battery level",
}


def probes(quick):
    """(id, label, frame, seconds to listen, question to ask the user or None)."""
    p = [
        ("info", "Get device info / firmware (0x92)", g.cmd_device_info(), 2.5, None),
        ("battery", "Battery query (0x91, guessed request form)", g.frame(0x91), 2.0, None),
        ("time", "Set watch clock to this PC's time (0x93)", g.cmd_set_time(), 1.5,
         "Does the watch now show the correct time?"),
        ("mtu", "MTU query (0xA7)", g.frame(0xA7, [0]), 1.5, None),
        ("find", "Find watch / vibrate (0x71)", g.cmd_find(), 3.0, "Did the watch vibrate?"),
    ]
    if not quick:
        p += [
            # Gadgetbridge's 0x84 got no reply on the F1 (fw 3.24), so only 0x31 is probed.
            ("hr_wf", "Live heart rate ON (0x31/0x0A) - wear the watch",
             g.cmd_measure(0x0A, 1), 15.0, None),
            ("hr_wf_off", "Live heart rate OFF (0x31/0x0A)", g.cmd_measure(0x0A, 0), 2.0, None),
            # Round 1 stopped SpO2 after 25 s and got 37 % - give it a full minute.
            ("spo2", "Single SpO2 measurement, 60 s (0x31/0x11) - keep still",
             g.cmd_measure(0x11, 1), 60.0, None),
            ("spo2_off", "SpO2 measurement OFF", g.cmd_measure(0x11, 0), 3.0,
             "What SpO2 % does the watch screen show (type it, or Enter)?"),
            ("bp", "Single blood-pressure measurement, 60 s (0x31/0x21) - keep still",
             g.cmd_measure(0x21, 1), 60.0, None),
            ("bp_off", "Blood-pressure measurement OFF", g.cmd_measure(0x21, 0), 3.0,
             "What blood pressure does the watch screen show, e.g. 120/80 (or Enter)?"),
            ("sync", "History sync, last 7 days (0x51)", g.cmd_sync(), 12.0, None),
            # If the watch clock was wrong before, stored records carry old dates.
            ("sync_all", "History sync, everything since 2000 (0x51)",
             g.cmd_sync(dt.datetime(2000, 1, 1)), 20.0, None),
            ("notify", "Push a test notification 'Hello from PC' (0x72)",
             g.cmd_notify("Hello from PC"), 3.0, "Did 'Hello from PC' appear on the watch?"),
        ]
    return p


def connect_request(model="PC", uid="1"):
    """Reply to a 0x20 pairing report (Wearfit Pro sendConnectRequestType, type 0)."""
    m, u = model.encode()[:12], uid.encode()
    return g.frame(0xE0, bytes([0, len(m)]) + m + bytes([len(u)]) + u)


def run(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
        return (r.stdout + r.stderr).strip() or f"(exit {r.returncode}, no output)"
    except FileNotFoundError:
        return "(command not installed)"
    except Exception as e:
        return f"(failed: {e})"


def environment():
    env = {
        "time": dt.datetime.now().isoformat(timespec="seconds"),
        "os": platform.platform(),
        "python": sys.version.split()[0],
        "bleak": getattr(bleak, "__version__", None) or _pkg_version("bleak"),
        "commands": {},
    }
    if sys.platform.startswith("linux"):
        for name, cmd in {
            "bluetooth service": ["systemctl", "is-active", "bluetooth"],
            "rfkill": ["rfkill", "list", "bluetooth"],
            "bluetoothctl version": ["bluetoothctl", "--version"],
            "adapter (bluetoothctl show)": ["bluetoothctl", "show"],
            "user groups": ["id", "-nG"],
        }.items():
            env["commands"][name] = run(cmd) if shutil.which(cmd[0]) else "(command not installed)"
    return env


def _pkg_version(name):
    try:
        from importlib.metadata import version
        return version(name)
    except Exception:
        return None


def ask(question, interactive):
    if not interactive or not question:
        return None
    try:
        a = input(f"   ? {question} [y/n/Enter=skip] ").strip().lower()
    except EOFError:
        return None
    if not a:
        return None
    return {"y": "yes", "n": "no"}[a[:1]] if a in ("y", "n", "yes", "no") else a


def is_known(decoded):
    return not decoded.startswith(("cmd=0x", "EA-frame", "unknown header", "short frame"))


async def scan(secs, name_hint):
    found = await BleakScanner.discover(timeout=secs, return_adv=True)
    rows = []
    for dev, adv in found.values():
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        name = dev.name or adv.local_name or ""
        rows.append({
            "address": dev.address,
            "name": name,
            "rssi": adv.rssi,
            "service_uuids": uuids,
            "manufacturer_data": {f"0x{k:04x}": bytes(v).hex() for k, v in (adv.manufacturer_data or {}).items()},
            "uart": g.SERVICE in uuids,
            "name_match": bool(name_hint and name_hint.lower() in name.lower()),
        })
    rows.sort(key=lambda r: (not (r["uart"] or r["name_match"]), -(r["rssi"] or -999)))
    return rows


def pick(rows, interactive):
    cands = [r for r in rows if r["uart"] or r["name_match"]]
    if not cands:
        return None
    if len(cands) == 1 or not interactive:
        return cands[0]["address"]
    print("\nPossible watches:")
    for i, r in enumerate(cands):
        print(f"  [{i}] {r['name'] or '?'}  {r['address']}  rssi={r['rssi']}  {'UART' if r['uart'] else ''}")
    try:
        i = input("Pick one [0]: ").strip()
        return cands[int(i) if i else 0]["address"]
    except (EOFError, ValueError, IndexError):
        return cands[0]["address"]


async def probe_watch(address, quick, interactive, log):
    """Connect, dump GATT, run probes. Returns a dict; never raises."""
    out = {"address": address, "connected": False, "gatt": [], "reads": [], "subscribed": [],
           "probes": [], "frames": [], "errors": []}
    start = time.monotonic()
    current = {"probe": "connect"}
    reassemblers = {}

    try:
        async with g.open_client(address, log=log) as client:
            out["connected"] = True
            log(f"Connected to {address}")

            # --- GATT dump -----------------------------------------------------
            for svc in client.services.services.values():
                s = {"uuid": svc.uuid, "description": svc.description, "characteristics": []}
                for ch in svc.characteristics:
                    s["characteristics"].append({
                        "uuid": ch.uuid, "handle": ch.handle, "description": ch.description,
                        "properties": list(ch.properties),
                    })
                    if "read" in ch.properties:
                        try:
                            v = bytes(await client.read_gatt_char(ch))
                            out["reads"].append({
                                "uuid": ch.uuid, "label": DEVICE_INFO_CHARS.get(ch.uuid, ch.description),
                                "hex": v.hex(), "text": v.decode("utf-8", "replace") if v else "",
                                "int": v[0] if len(v) == 1 else None,
                            })
                        except Exception as e:
                            out["reads"].append({"uuid": ch.uuid, "label": ch.description, "error": str(e)})
                out["gatt"].append(s)
            log(f"GATT: {len(out['gatt'])} services, {sum(len(s['characteristics']) for s in out['gatt'])} characteristics")

            write_char = client.services.get_characteristic(g.WRITE_CHAR)
            if write_char is None:
                out["errors"].append("Write characteristic 6e400002 not present - not the WearFit protocol.")

            async def send(data):
                await g.write_frame(client, data)

            # --- subscribe to every notify/indicate characteristic -----------------
            def make_cb(uuid):
                rx = reassemblers.setdefault(uuid, g.Reassembler())

                def cb(_, data):
                    f = rx.feed(bytes(data))
                    if f is None:
                        return
                    if uuid == g.NOTIFY_CHAR:
                        d = g.decode(f)
                    elif uuid == g.PEDOMETER_CHAR:
                        d = g.decode_pedometer(f)
                    else:
                        d = f"(non-UART channel) {f.hex()}"
                    out["frames"].append({"t": round(time.monotonic() - start, 2), "probe": current["probe"],
                                          "char": uuid, "hex": f.hex(), "decoded": d,
                                          "known": uuid in (g.NOTIFY_CHAR, g.PEDOMETER_CHAR) and is_known(d)})
                    log(f"   <- [{uuid[4:8]}] {f.hex()}  | {d}")
                    if uuid == g.NOTIFY_CHAR and len(f) > 4:
                        if f[0] == 0xEA and f[4] == 0x0B:
                            asyncio.get_running_loop().create_task(send(g.HEARTBEAT_REPLY))
                        elif f[0] == 0xAB and f[4] == 0x20:
                            log("   (watch sent pairing report 0x20 - answering with connect request 0xE0)")
                            asyncio.get_running_loop().create_task(send(connect_request()))
                return cb

            # Only the UART channel is subscribed. Subscribing to everything (as the first
            # version did) left the watch remembering an FEA1 indication that later blocked
            # every connection; clear any such leftovers instead.
            await g.clear_other_subscriptions(client, log=log)
            if client.services.get_characteristic(g.NOTIFY_CHAR) is not None:
                try:
                    await client.start_notify(g.NOTIFY_CHAR, make_cb(g.NOTIFY_CHAR))
                    out["subscribed"].append(g.NOTIFY_CHAR)
                except Exception as e:
                    out["errors"].append(f"subscribe {g.NOTIFY_CHAR}: {e}")
            log(f"Subscribed to {len(out['subscribed'])} notify channel(s); listening 3 s for unsolicited data")
            current["probe"] = "unsolicited"
            await asyncio.sleep(3)

            if write_char is None:
                return out

            # --- probes ----------------------------------------------------------
            plist = probes(quick)
            for i, (pid, label, data, wait, question) in enumerate(plist):
                current["probe"] = pid
                before = len(out["frames"])
                log(f"[{i + 1}/{len(plist)}] {label}\n   -> {data.hex()}")
                err = None
                try:
                    await send(data)
                    await asyncio.sleep(wait)
                except Exception as e:
                    err = str(e)
                    log(f"   !! {e}")
                replies = out["frames"][before:]
                answer = ask(question, interactive)
                out["probes"].append({"id": pid, "label": label, "sent": data.hex(), "error": err,
                                      "replies": len(replies), "user_answer": answer})

                # Gadgetbridge writes 01 00 before anything else; retry info once if silent.
                if pid == "info" and not replies and not err:
                    current["probe"] = "info_after_gb_unlock"
                    log("   no reply - retrying after Gadgetbridge's '01 00' write")
                    before = len(out["frames"])
                    try:
                        await client.write_gatt_char(g.WRITE_CHAR, b"\x01\x00", response=None)
                        await asyncio.sleep(0.3)
                        await send(g.cmd_device_info())
                        await asyncio.sleep(2.5)
                    except Exception as e:
                        err = str(e)
                    out["probes"].append({"id": "info_after_gb_unlock", "label": "Device info after '01 00' write",
                                          "sent": "0100 then " + g.cmd_device_info().hex(), "error": err,
                                          "replies": len(out["frames"]) - before, "user_answer": None})
            current["probe"] = "final"
            await asyncio.sleep(2)
    except Exception as e:
        out["errors"].append(f"{type(e).__name__}: {e}")
        log(f"!! {type(e).__name__}: {e}")
    return out


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
def summarize(data):
    """Return (checklist rows, next steps)."""
    scan_rows, w = data["scan"], data.get("watch") or {}
    frames = w.get("frames", [])
    uart_frames = [f for f in frames if f["char"] == g.NOTIFY_CHAR]
    pr = {p["id"]: p for p in w.get("probes", [])}

    def frames_for(*ids):
        return [f for f in uart_frames if f["probe"] in ids]

    def any_dec(pred, fs=uart_frames):
        return next((f["decoded"] for f in fs if pred(f["decoded"])), None)

    has_uart_svc = any(s["uuid"] == g.SERVICE for s in w.get("gatt", []))
    fw = any_dec(lambda d: d.startswith("device info"))
    batt = any_dec(lambda d: d.startswith("battery"))
    batt_read = next((r for r in w.get("reads", []) if r.get("uuid") == "00002a19-0000-1000-8000-00805f9b34fb"), None)
    hr = any_dec(lambda d: "bpm" in d and "sample" not in d)
    hr_any = frames_for("hr_gb", "hr_wf")
    spo2_ok = any_dec(lambda d: "SpO2:" in d and "%" in d and "implausible" not in d)
    # Latest FEA1 value wins: the read happens before the clock is set, which can reset the count.
    steps_wx = next((f["decoded"] for f in reversed(frames) if f["char"] == g.PEDOMETER_CHAR), None) or next(
        (g.decode_pedometer(bytes.fromhex(r["hex"])) + " (before clock was set)" for r in w.get("reads", [])
         if r.get("uuid") == g.PEDOMETER_CHAR and r.get("hex")), None)
    spo2 = frames_for("spo2", "spo2_off")
    sync = frames_for("sync")
    sync_all = frames_for("sync_all")
    bp = frames_for("bp", "bp_off")
    note = pr.get("notify", {}).get("user_answer")

    def screen(pid):
        ans = pr.get(pid, {}).get("user_answer")
        return f" (screen: {ans})" if ans else ""
    unknown = [f for f in frames if not f["known"]]
    vib = pr.get("find", {}).get("user_answer")
    clock = pr.get("time", {}).get("user_answer")

    def row(ok, item, detail):
        return ({True: "OK", False: "MISSING", None: "UNKNOWN"}[ok], item, detail)

    rows = [
        row(data["scan_error"] is None, "Bluetooth adapter usable", data["scan_error"] or f"{len(scan_rows)} devices seen"),
        row(None if data["scan_error"] else bool(w.get("address")), "Watch found in scan", w.get("address") or "no device with Nordic UART / matching name"),
        row(w.get("connected"), "Connected", "; ".join(w.get("errors", [])[:1]) or ("yes" if w.get("connected") else "not attempted")),
        row(has_uart_svc if w.get("connected") else None, "Nordic UART service (6e400001)", "present" if has_uart_svc else "absent"),
        row(bool(uart_frames) if w.get("connected") else None, "Watch answers WearFit frames", f"{len(uart_frames)} frames received"),
        row(bool(fw) if w.get("connected") else None, "Firmware version (0x92)", fw or "-"),
        row(bool(batt or batt_read) if w.get("connected") else None, "Battery level",
            batt or (f"{batt_read.get('int')}% via standard battery service" if batt_read else "-")),
        row({"yes": True, "no": False}.get(vib, bool(frames_for("find")) or None), "Find / vibrate (0x71)", vib or "not confirmed by user"),
        row({"yes": True, "no": False}.get(clock), "Set time (0x93)", clock or "not confirmed by user"),
        row(bool(hr) if "hr_wf" in pr else None, "Live heart rate", hr or f"{len(hr_any)} frames during HR probes"),
        row(bool(spo2_ok) if "spo2" in pr else None, "SpO2",
            (spo2_ok or ("; ".join(f["decoded"] for f in spo2) if spo2 else "-")) + screen("spo2_off")),
        row(bool(bp) if "bp" in pr else None, "Blood pressure",
            ("; ".join(f["decoded"] for f in bp) if bp else "-") + screen("bp_off")),
        row({"yes": True, "no": False}.get(note) if "notify" in pr else None, "Notification (0x72)",
            note or "not confirmed by user"),
        row(bool(steps_wx) if w.get("connected") else None, "Steps via WeChat pedometer (FEA1)", steps_wx or "-"),
        row(bool(sync) if "sync" in pr else None, "History sync (0x51)", f"{len(sync)} frames"),
        row(len(sync_all) > 1 if "sync_all" in pr else None, "Full history since 2000", f"{len(sync_all)} frames"),
    ]

    steps = []
    if data["scan_error"]:
        if sys.platform.startswith("linux"):
            steps.append("Fix Bluetooth first: `sudo systemctl enable --now bluetooth`, `rfkill unblock bluetooth`, "
                         "and check `bluetoothctl show` lists a controller with `Powered: yes`.")
        else:
            steps.append("Fix Bluetooth first: this computer has no usable Bluetooth adapter (plug in a BLE dongle "
                         "or turn Bluetooth on in Settings).")
    elif not w.get("address"):
        steps.append("The watch wasn't advertising. Turn off Bluetooth on your phone (or force-stop Wearfit) so the "
                     "watch is free, wake its screen, then rerun. If it shows in the scan table under another name, "
                     "rerun with that address: `./run.sh report <ADDRESS>`.")
    elif not w.get("connected"):
        steps.append(g.CONNECT_HELP.format(addr=w.get("address")).replace("\n", " "))
    elif not has_uart_svc:
        steps.append("The watch does NOT use the WearFit (Nordic UART) protocol. Send this report back: the GATT "
                     "table shows which service it uses, and we'll need to decompile the app that actually supports "
                     "it (possibly 'Genius Fit').")
    elif not uart_frames:
        steps.append("UART service exists but the watch stayed silent. Send this report back; next things to try are "
                     "the newer 0xE0 connect handshake with a real user id and write-with-response.")
    else:
        steps.append("Protocol confirmed. Remaining work is decoding anything listed under 'Unrecognised frames'.")
    if w.get("connected") and uart_frames:
        if not fw:
            steps.append("No firmware reply: the 0x92 response layout may differ on this model - check unknown frames.")
        if "hr_wf" in pr and not hr:
            steps.append("No live heart rate: wear the watch snugly and rerun without --quick. If still nothing, "
                         "the HR start command differs on this model.")
        if "sync" in pr and not sync:
            steps.append("No history returned: the watch may need data recorded first (wear it a day) or a different "
                         "sync request (Wearfit Pro's 17-byte form).")
    if unknown:
        steps.append(f"{len(unknown)} unrecognised frame(s) - send the report so they can be decoded.")
    if any("BP sample" in f["decoded"] for f in uart_frames):
        steps.append("Blood-pressure frames seen: compare the two numbers with what the watch screen shows to settle "
                     "systolic/diastolic order.")
    return rows, steps


def md_table(headers, rows):
    esc = lambda v: str(v).replace("|", "\\|").replace("\n", " ")
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(esc(c) for c in r) + " |" for r in rows]
    return "\n".join(lines)


def render(data):
    w = data.get("watch") or {}
    rows, steps = summarize(data)
    L = [f"# Genius F1 diagnostic report", "", f"Generated {data['env']['time']} on `{data['env']['os']}`", ""]
    L += ["## Results", "", md_table(["Status", "Check", "Detail"], rows), ""]
    L += ["## What we still need", ""] + [f"{i}. {s}" for i, s in enumerate(steps, 1)] + [""]

    L += ["## Environment", "", f"- Python {data['env']['python']}, bleak {data['env']['bleak']}"]
    for k, v in data["env"]["commands"].items():
        L += [f"- **{k}**:", "", "  ```", *["  " + x for x in v.splitlines()[:25]], "  ```"]
    L += [""]

    L += ["## Scan", ""]
    if data["scan_error"]:
        L += [f"Scan failed: `{data['scan_error']}`", ""]
    else:
        L += [md_table(["Address", "Name", "RSSI", "Watch?", "Services", "Manufacturer data"],
                       [(r["address"], r["name"] or "?", r["rssi"],
                         "UART" if r["uart"] else ("name" if r["name_match"] else ""),
                         ", ".join(u[:8] for u in r["service_uuids"]) or "-",
                         ", ".join(f"{k}:{v}" for k, v in r["manufacturer_data"].items()) or "-")
                        for r in data["scan"][:40]]), ""]

    if w.get("gatt"):
        L += ["## GATT services", ""]
        L += [md_table(["Service", "Characteristic", "Handle", "Properties", "Description"],
                       [(s["uuid"], c["uuid"], c["handle"], ",".join(c["properties"]), c["description"])
                        for s in w["gatt"] for c in s["characteristics"]]), ""]
    if w.get("reads"):
        L += ["## Readable values", ""]
        L += [md_table(["Characteristic", "Label", "Hex", "Text"],
                       [(r["uuid"], r.get("label"), r.get("hex", ""), r.get("text") or r.get("error", ""))
                        for r in w["reads"]]), ""]
    if w.get("probes"):
        L += ["## Probes", ""]
        L += [md_table(["Probe", "Sent", "Replies", "You said", "Error"],
                       [(p["label"], f"`{p['sent']}`", p["replies"], p["user_answer"] or "", p["error"] or "")
                        for p in w["probes"]]), ""]
    unknown = [f for f in w.get("frames", []) if not f["known"]]
    if unknown:
        L += ["## Unrecognised frames", ""]
        L += [md_table(["t (s)", "During", "Channel", "Hex"],
                      [(f["t"], f["probe"], f["char"][4:8], f"`{f['hex']}`") for f in unknown[:200]]), ""]
    if w.get("frames"):
        L += ["## Full notification log", ""]
        L += [md_table(["t (s)", "During", "Channel", "Hex", "Decoded"],
                       [(f["t"], f["probe"], f["char"][4:8], f"`{f['hex']}`", f["decoded"]) for f in w["frames"][:500]]), ""]
    if w.get("errors"):
        L += ["## Errors", ""] + [f"- {e}" for e in w["errors"]] + [""]
    return "\n".join(L)


async def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("address", nargs="?")
    ap.add_argument("--name", default="F1", help="name substring that marks the watch in scans (default F1)")
    ap.add_argument("--scan-secs", type=float, default=15)
    ap.add_argument("--quick", action="store_true", help="skip heart rate / SpO2 / sync probes")
    ap.add_argument("--yes", action="store_true", help="non-interactive")
    o = ap.parse_args()
    interactive = sys.stdin.isatty() and not o.yes
    log = lambda s: print(s, flush=True)

    data = {"env": environment(), "scan": [], "scan_error": None, "watch": None}
    if bleak is None:
        data["scan_error"] = "bleak not installed (run setup)"
    else:
        log(f"Scanning {o.scan_secs:.0f} s ...")
        try:
            data["scan"] = await scan(o.scan_secs, o.name)
            log(f"{len(data['scan'])} devices seen")
        except Exception as e:
            data["scan_error"] = f"{type(e).__name__}: {e}"
            log(f"!! scan failed: {data['scan_error']}")

    if not data["scan_error"]:
        addr = o.address or pick(data["scan"], interactive)
        if addr:
            if interactive:
                print("\nWear the watch, keep it close, and make sure your phone is NOT connected to it.")
                print("Some probes take ~15 s while the heart-rate sensor runs.\n")
            data["watch"] = await probe_watch(addr, o.quick, interactive, log)
        else:
            log("No watch candidate found (no Nordic UART service, no name containing "
                f"'{o.name}'). See the scan table in the report.")

    out_dir = Path(__file__).resolve().parent / "reports"
    out_dir.mkdir(exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    md, js = out_dir / f"f1_report_{stamp}.md", out_dir / f"f1_report_{stamp}.json"
    md.write_text(render(data), encoding="utf-8")
    js.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")

    rows, steps = summarize(data)
    print("\n================ SUMMARY ================")
    for status, item, detail in rows:
        print(f"  [{status:^7}] {item}: {detail}")
    print("\nWhat we still need:")
    for i, s in enumerate(steps, 1):
        print(f"  {i}. {s}")
    print(f"\nReport written to:\n  {md}\n  {js}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\ninterrupted")
