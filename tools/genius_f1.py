"""
Genius F1 / WearFit-family smartwatch BLE client.

Protocol reverse-engineered from:
  * Gadgetbridge  service/devices/wearfit/WearFitDeviceSupport.java (open source)
  * Wearfit Pro 5.6.16 (com.wakeup.howear), decompiled with REA + JADX
See PROTOCOL.md for the full write-up.

Usage:
  python genius_f1.py scan
  python genius_f1.py info    [ADDRESS]
  python genius_f1.py hr      [ADDRESS]   # live heart rate until Ctrl+C
  python genius_f1.py spo2    [ADDRESS]   # one blood-oxygen reading (up to 60 s)
  python genius_f1.py bp      [ADDRESS]   # one blood-pressure reading (up to 60 s)
  python genius_f1.py steps   [ADDRESS]   # today's steps (two sources)
  python genius_f1.py notify  ADDRESS "text"  # show a message on the watch
  python genius_f1.py find    [ADDRESS]   # vibrate the watch
  python genius_f1.py sync    [ADDRESS]   # dump stored steps / HR history
  python genius_f1.py listen  [ADDRESS]   # just print every notification
  python genius_f1.py raw ADDRESS HEX     # send a raw frame, e.g. "ab0003ff7180"
  python genius_f1.py reset   ADDRESS     # FACTORY RESET - wipes the watch, asks first

If ADDRESS is omitted, the first device advertising the Nordic UART service
(or a name matching --name) is used.
"""
import argparse
import asyncio
import contextlib
import datetime as dt
import shutil
import subprocess
import sys

from bleak import BleakClient, BleakScanner

SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
WRITE_CHAR = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # phone -> watch
NOTIFY_CHAR = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # watch -> phone

CHUNK = 20  # both apps split frames into 20-byte writes


# --------------------------------------------------------------------------
# Framing
# --------------------------------------------------------------------------
def frame(cmd, args=b"", kind=0x80, header=0xAB):
    """AB <len hi> <len lo> FF <cmd> <kind> <args...>, len = len(args) + 3."""
    n = len(args) + 3
    return bytes([header, n >> 8, n & 0xFF, 0xFF, cmd, kind]) + bytes(args)


def split(data, size=CHUNK):
    """First chunk is sent as-is; each continuation is <seq byte> + size-1 bytes."""
    if len(data) <= size:
        return [data]
    out = [data[:size]]
    rest = data[size:]
    for seq, i in enumerate(range(0, len(rest), size - 1)):
        out.append(bytes([seq]) + rest[i:i + size - 1])
    return out


async def write_frame(client, data):
    """Send a frame in 20-byte chunks. Use write-without-response only when the
    characteristic supports it (as Wearfit Pro does); BlueZ rejects it otherwise."""
    ch = client.services.get_characteristic(WRITE_CHAR)
    no_resp = ch is not None and "write-without-response" in ch.properties
    for chunk in split(data):
        await client.write_gatt_char(WRITE_CHAR, chunk, response=not no_resp)
        await asyncio.sleep(0.05)


class Reassembler:
    """Mirror of Wearfit Pro's DataPackager.packageData()."""

    def __init__(self):
        self.buf = None

    def feed(self, pkt):
        if self.buf is None:
            # Not a WearFit frame start: pass it through untouched.
            if len(pkt) < 3 or pkt[0] not in (0xAB, 0xEA):
                return bytes(pkt)
            total = ((pkt[1] << 8) | pkt[2]) + 3
            if total > 4096:
                return bytes(pkt)
            if len(pkt) >= total:
                return bytes(pkt[:total])
            self.buf = bytearray(pkt)
            self.total = total
            return None
        self.buf += pkt[1:]  # drop continuation sequence byte
        if len(self.buf) >= self.total:
            out, self.buf = bytes(self.buf[:self.total]), None
            return out
        return None


# --------------------------------------------------------------------------
# Commands (opcodes confirmed in both Gadgetbridge and Wearfit Pro)
# --------------------------------------------------------------------------
def cmd_set_time(now=None):
    now = now or dt.datetime.now()
    return frame(0x93, [0x00, now.year >> 8, now.year & 0xFF, now.month, now.day,
                        now.hour, now.minute, now.second])


def cmd_device_info():
    return frame(0x92, [0x01])


def cmd_find():
    return frame(0x71)


def cmd_realtime_hr(on):  # Gadgetbridge style (report 0x84)
    return frame(0x84, [1 if on else 0])


def cmd_measure(kind, on):
    """Wearfit Pro setOnceOrRealTimeMeasure: AB 00 04 FF 31 <kind> <on>.
    kind: 0x09 single HR, 0x0A live HR, 0x11 single SpO2, 0x12 live SpO2,
          0x21 single BP, 0x22 live BP."""
    return frame(0x31, [on], kind=kind)


def cmd_time_mode(h12):
    return frame(0x7C, [1 if h12 else 0])


def cmd_sync(since=None):
    """Request history newer than `since` (Gadgetbridge CMD_REQUEST_FITNESS)."""
    s = since or (dt.datetime.now() - dt.timedelta(days=7))
    t = [s.year - 2000, s.month, s.day, s.hour, s.minute]
    return frame(0x51, [0x00] + t + t)


def cmd_factory_reset():
    """Wearfit Pro factory reset: AB 00 04 FF 23 80 00. Erases everything on the watch.
    Wearfit Pro follows it with 0xFF (reboot), which can power the watch off instead,
    so that is deliberately not sent here."""
    return frame(0x23, [0x00])


def confirm_reset(address):
    """Ask the user to type RESET. Refuses when stdin isn't a terminal."""
    print(f"""
!!! FACTORY RESET of {address} !!!
This erases everything on the watch: settings, pairing, and any step/heart-rate
history that hasn't been synced yet. It cannot be undone.""")
    if not sys.stdin.isatty():
        sys.exit("Refusing to reset: no terminal to confirm on.")
    try:
        answer = input("Type RESET (capitals) to continue, anything else to cancel: ")
    except EOFError:
        answer = ""
    if answer.strip() != "RESET":
        sys.exit("Cancelled - nothing was sent.")


def cmd_notify(text, source=0x03):
    """Push a notification (0x72). source 0x03 = SMS; see PROTOCOL.md for others."""
    return frame(0x72, bytes([source, 0x02]) + text.encode("utf-8"))


PEDOMETER_CHAR = "0000fea1-0000-1000-8000-00805f9b34fb"  # WeChat pedometer (service FEE7)


def decode_pedometer(v):
    """WeChat 'current pedometer measurement': flag byte (bit0 steps, bit1 distance,
    bit2 calories), then uint24 little-endian fields in that order.
    On the F1 the step count reset to 0 when the clock was set (new day)."""
    if len(v) < 4:
        return f"pedometer raw {v.hex()}"
    flag, fields, i = v[0], [], 1
    for bit, label in ((1, "steps"), (2, "distance"), (4, "calories")):
        if flag & bit and i + 3 <= len(v):
            fields.append(f"{label}={int.from_bytes(v[i:i + 3], 'little')}")
            i += 3
    return "pedometer (WeChat FEA1): " + ", ".join(fields)


HEARTBEAT_REPLY =bytes([0xEA, 0x00, 0x03, 0x00, 0x0B, 0x02])


# --------------------------------------------------------------------------
# Decoding
# --------------------------------------------------------------------------
def decode(f):
    if len(f) < 6:
        return f"short frame {f.hex()}"
    if f[0] == 0xEA:
        return f"EA-frame cmd=0x{f[4]:02x} {f.hex()}"
    if f[0] != 0xAB:
        return f"unknown header {f.hex()}"
    cmd, kind, a = f[4], f[5], f[6:]
    if cmd == 0x91 and len(a) >= 2:
        return f"battery {a[1]}% {'(charging)' if a[0] else ''}"
    if cmd == 0x92 and len(a) >= 2:
        # Wearfit Pro ConnectAnalyzer.getBandVersionInfo; the F1 replies with kind 0xC0
        model = a[2] if len(a) > 2 else None
        if len(a) > 4 and (a[3] or a[4]):
            model = (a[3] & 0x7F) * 256 + a[4]
        return f"device info: firmware {a[0]}.{a[1]:02d}  model code {model}  raw={a.hex()}"
    if cmd == 0x84 and a:
        hr = a[0]
        return "heart rate: no contact" if hr == 0xFF else f"heart rate: {hr} bpm" if hr else "heart rate: measuring..."
    if cmd == 0x31:
        # Confirmed on the Genius F1 (fw 3.24): reply is AB 00 05 FF 31 <kind> 00 <value>.
        names = {0x09: "single HR", 0x0A: "live HR", 0x11: "single SpO2",
                 0x12: "live SpO2", 0x21: "single BP", 0x22: "live BP"}
        name = names.get(kind, f"measure 0x{kind:02x}")
        if kind in (0x09, 0x0A) and len(a) >= 2:
            return f"{name}: {a[1]} bpm" if a[1] else f"{name}: measuring..."
        if kind in (0x11, 0x12) and len(a) >= 2:
            if not a[1]:
                return f"{name}: measuring..."
            note = "" if 70 <= a[1] <= 100 else "  (implausible - measurement probably cut short)"
            return f"{name}: {a[1]}%{note}"
        if kind in (0x21, 0x22) and len(a) >= 3:
            return f"{name}: {a[1]} / {a[2]} (sys/dia order unverified)"
        return f"{name}: {list(a)}"
    if cmd == 0x51 and kind == 0x08 and len(a) >= 6:
        steps = (a[0] << 16) | (a[1] << 8) | a[2]
        kcal = (a[3] << 16) | (a[4] << 8) | a[5]
        return f"today: {steps} steps, {kcal} (kcal/distance field)"
    if cmd == 0x51 and kind == 0x11 and len(a) >= 6:
        return f"HR sample {2000 + a[0]}-{a[1]:02d}-{a[2]:02d} {a[3]:02d}:{a[4]:02d}  {a[5]} bpm"
    if cmd == 0x51 and kind == 0x12 and len(a) >= 6:
        return f"SpO2 sample {2000 + a[0]}-{a[1]:02d}-{a[2]:02d} {a[3]:02d}:{a[4]:02d}  {a[5]}%"
    if cmd == 0x51 and kind == 0x14 and len(a) >= 7:
        return f"BP sample {2000 + a[0]}-{a[1]:02d}-{a[2]:02d} {a[3]:02d}:{a[4]:02d}  values {a[5]} / {a[6]} (sys/dia order unverified)"
    if cmd == 0x51 and kind == 0x20 and len(a) >= 7:
        return f"steps sample {2000 + a[0]}-{a[1]:02d}-{a[2]:02d} {a[3]:02d}:00  {(a[5] << 8) | a[6]} steps"
    if cmd == 0x7D and a:
        return f"watch is looking for phone: {'start' if a[0] else 'stop'}"
    if cmd == 0x20:
        return f"pairing type / capability report: {a.hex()}"
    return f"cmd=0x{cmd:02x} kind=0x{kind:02x} args={a.hex()}"


# --------------------------------------------------------------------------
# BLE plumbing
# --------------------------------------------------------------------------
async def find_device(address, name):
    if address:
        return address
    print("Scanning 10 s ...")
    found = await BleakScanner.discover(timeout=10.0, return_adv=True)
    for dev, adv in found.values():
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        n = dev.name or adv.local_name or ""
        if SERVICE in uuids or (name and name.lower() in n.lower()):
            print(f"Using {n} [{dev.address}]")
            return dev.address
    sys.exit("No WearFit-style watch found. Run `scan` and pass the address explicitly.")


SERVICE_CHANGED = "00002a05-0000-1000-8000-00805f9b34fb"  # managed by BlueZ itself


async def clear_other_subscriptions(client, keep=(NOTIFY_CHAR,), log=print):
    """The F1 remembers notify/indicate subscriptions even across restarts, and a
    remembered FEA1 indication fired at connect time seems to wedge its ATT server
    (it then never answers the MTU request). Switch every subscription except
    `keep` on and straight off again, so BlueZ writes 00 00 to its CCCD."""
    cleared = []
    for svc in client.services:
        for ch in svc.characteristics:
            if ch.uuid in keep or ch.uuid == SERVICE_CHANGED:
                continue
            if not ({"notify", "indicate"} & set(ch.properties)):
                continue
            try:
                await client.start_notify(ch, lambda *_: None)
                await client.stop_notify(ch)
                cleared.append(ch.uuid[4:8])
            except Exception as e:
                log(f"   (couldn't clear subscription on {ch.uuid[4:8]}: {e})")
    if cleared:
        log(f"   cleared leftover subscriptions on: {', '.join(cleared)}")


class ConnectFailed(Exception):
    pass


CONNECT_HELP = """Couldn't connect to the watch. Most likely causes:
  * your phone (Wearfit) has grabbed it again - turn the phone's Bluetooth off
  * an earlier run is still holding it:  pkill -f genius_f1.py; pkill -f f1_report.py
  * BlueZ is stuck:  bluetoothctl remove {addr}; sudo systemctl restart bluetooth
  * the watch itself is stuck - restart it (hold the button) or put it on the charger briefly"""


def _bluez_disconnect(address):
    """Drop any half-open link BlueZ still holds, so the next attempt starts clean."""
    if sys.platform.startswith("linux") and shutil.which("bluetoothctl"):
        try:
            subprocess.run(["bluetoothctl", "disconnect", address],
                           capture_output=True, timeout=8)
        except Exception:
            pass


@contextlib.asynccontextmanager
async def open_client(address, attempts=3, timeout=30.0, log=print):
    """Connect with retries. Each attempt first finds the watch in a fresh scan (it
    must be advertising), then connects. Raises ConnectFailed after the last attempt."""
    client, last = None, None
    t0 = [0.0]

    def on_disconnect(_):
        secs = asyncio.get_running_loop().time() - t0[0]
        if secs < timeout - 1:  # later than that it's our own timeout hanging up, not the watch
            log(f"   !! the link dropped {secs:.1f} s after connecting started")

    for n in range(1, attempts + 1):
        try:
            dev = await BleakScanner.find_device_by_address(address, timeout=15.0)
            if dev is None:
                raise ConnectFailed("watch not seen in scan (asleep, out of range, or held by the phone)")
            client = BleakClient(dev, timeout=timeout, disconnected_callback=on_disconnect)
            t0[0] = asyncio.get_running_loop().time()
            await client.connect()
            break
        except Exception as e:
            last = e
            if "NotAvailable" in type(e).__name__:  # adapter off/blocked: retrying won't help
                raise ConnectFailed("Bluetooth is off on this computer -> rfkill unblock bluetooth; "
                                    "bluetoothctl power on") from e
            if isinstance(e, (asyncio.TimeoutError, TimeoutError)):
                e = TimeoutError(f"the watch never finished sending its service list ({timeout:.0f} s)")
            log(f"   connect attempt {n}/{attempts} failed: {type(e).__name__}: {str(e) or 'timed out'}")
            if client is not None:
                with contextlib.suppress(Exception):
                    await client.disconnect()
            client = None
            _bluez_disconnect(address)
            if n < attempts:
                await asyncio.sleep(3)
    if client is None:
        raise ConnectFailed(f"{type(last).__name__}: {str(last) or 'timed out'}")
    try:
        yield client
    finally:
        # Unsubscribe before leaving so the watch doesn't keep the subscription.
        with contextlib.suppress(Exception):
            await client.stop_notify(NOTIFY_CHAR)
        with contextlib.suppress(Exception):
            await client.disconnect()


async def session(address, actions, listen_secs, stop_actions=()):
    rx = Reassembler()

    async with open_client(address) as client:
        print(f"Connected to {address}")

        async def send(data):
            await write_frame(client, data)

        def on_notify(_, data):
            f = rx.feed(bytes(data))
            if f is None:
                return
            print(f"<- {f.hex()}  | {decode(f)}")
            if f[0] == 0xEA and len(f) > 4 and f[4] == 0x0B:
                asyncio.get_running_loop().create_task(send(HEARTBEAT_REPLY))

        await clear_other_subscriptions(client)
        await client.start_notify(NOTIFY_CHAR, on_notify)

        # The F1 also has the WeChat pedometer channel. Read it only: subscribing
        # to it is what got the watch stuck before.
        if client.services.get_characteristic(PEDOMETER_CHAR) is not None:
            try:
                v = bytes(await client.read_gatt_char(PEDOMETER_CHAR))
                print(f"<- [fea1] {v.hex()}  | {decode_pedometer(v)}")
            except Exception as e:
                print(f"(pedometer channel unavailable: {e})")

        for a in actions:
            print(f"-> {a.hex()}")
            await send(a)
            await asyncio.sleep(0.3)
        try:
            await asyncio.sleep(listen_secs)
        except asyncio.CancelledError:
            pass
        finally:
            # Switch sensors off again so the watch doesn't keep measuring.
            for a in stop_actions:
                try:
                    print(f"-> {a.hex()}  (stop)")
                    await asyncio.shield(send(a))
                except Exception:
                    pass
        return send


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("action", choices=["scan", "info", "hr", "spo2", "bp", "steps", "notify", "find", "sync", "listen", "raw", "reset"])
    p.add_argument("address", nargs="?")
    p.add_argument("hex", nargs="?", help="raw: frame as hex; notify: the message text")
    p.add_argument("--name", default="F1", help="name substring to auto-pick (default: F1)")
    p.add_argument("--secs", type=float, default=15)
    o = p.parse_args()

    if o.action == "scan":
        found = await BleakScanner.discover(timeout=10.0, return_adv=True)
        for dev, adv in sorted(found.values(), key=lambda x: -x[1].rssi):
            uuids = [u.lower() for u in (adv.service_uuids or [])]
            tag = "  <-- Nordic UART (WearFit protocol)" if SERVICE in uuids else ""
            print(f"{dev.address}  rssi={adv.rssi:4d}  {dev.name or adv.local_name or '?'}{tag}")
        return

    if o.action == "reset" and not o.address:
        sys.exit("reset needs the watch address spelled out (run `scan` to find it).")
    addr = await find_device(o.address, o.name)
    if o.action == "reset":
        confirm_reset(addr)
    base = [cmd_set_time(), cmd_device_info()]
    plan = {
        "info": (base, o.secs),
        "find": ([cmd_find()], 3),
        "sync": (base + [cmd_sync()], max(o.secs, 20)),
        "listen": ([], o.secs if o.secs != 15 else 3600),
        # The F1 answers only the Wearfit Pro style 0x31 command, not Gadgetbridge's 0x84.
        "hr": (base + [cmd_measure(0x0A, 1)], 3600),
        "spo2": ([cmd_measure(0x11, 1)], max(o.secs, 60)),
        "bp": ([cmd_measure(0x21, 1)], max(o.secs, 60)),
        "steps": (base + [cmd_sync()], max(o.secs, 10)),
        "notify": ([cmd_notify(o.hex or "Hello from PC")], 3),
        "raw": ([bytes.fromhex(o.hex or "")], o.secs),
        "reset": ([cmd_factory_reset()], 5),
    }
    stops = {"hr": [cmd_measure(0x0A, 0)], "spo2": [cmd_measure(0x11, 0)], "bp": [cmd_measure(0x21, 0)]}
    actions, secs = plan[o.action]
    try:
        await session(addr, actions, secs, stops.get(o.action, ()))
    except KeyboardInterrupt:
        pass
    except ConnectFailed as e:
        if "Bluetooth is off" in str(e):
            sys.exit(f"\n{e}")
        sys.exit("\n" + CONNECT_HELP.format(addr=addr))


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nbye")
