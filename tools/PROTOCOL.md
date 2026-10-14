# Genius F1 / WearFit BLE protocol

Sources, cross-checked against each other:

- **Gadgetbridge** (commit `bb7a1e8`, 2026-10-13): `devices/wearfit/WearFitConstants.java`, `service/devices/wearfit/WearFitDeviceSupport.java`
- **Wearfit Pro 5.6.16** (`com.wakeup.howear`, APK sha256 `b553f402…35e2`), decompiled with [REA](https://github.com/morluto/rea) 6.4.0 using JADX headless 0.7.1. Relevant classes:
  - `com.wakeup.module.ble.BleWatchServiceImpl`: command builders
  - `com.wakeup.module.ble.OrderUtil`: packet splitting
  - `com.wakeup.module.device.stack.nordic.NordicWatchBleManager`: GATT layer
  - `com.wakeup.module.device.work.analyzer.{DataPackager, ConnectAnalyzer, HealthDataAnalyzer}`
  - `com.wakeup.module.device.work.dataSync.DataSyncProxy`: payload parsing

## 1. GATT

| Role | UUID |
|---|---|
| Service (Nordic UART) | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| Write, phone → watch | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| Notify, watch → phone | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

Wearfit Pro also embeds these UUIDs, which neither app needs for basic use:
- `6e400011/12/13-…` (bus code)
- `00003803`/`00004a03` (pay code)
- `00000000-0000-0x00-6473-5f696c666973` (SiChe log)

Writes use write-without-response when the characteristic supports it (`NordicWatchBleManager.writeData`). No bonding or encryption is required.

## 2. Framing

```
AB  LEN_HI LEN_LO  FF  CMD  KIND  ARGS...
LEN = len(ARGS) + 3
KIND is usually 0x80; measure/data commands put a sub-type here.
```

Newer firmware also uses an `EA` header with the same layout. Examples are time zone `EA … 93 01 …`, notification settings `EA … 72 01 …`, and heartbeat. If the watch sends `EA … 0B …`, reply `EA 00 03 00 0B 02`.

**Splitting (phone → watch)**, from `OrderUtil.handleMessageBytes(data, 20)`: the first 20 bytes go as-is. Each later chunk is `[seq]` + the next 19 bytes, with seq = 0, 1, 2, …

**Reassembly (watch → phone)**, from `DataPackager.packageData`: read the total length from bytes 1–2 of the first packet, then strip the first byte of every continuation packet.

## 3. Connect sequence

1. Connect GATT, discover services, enable notifications on `…0003`.
2. *(Gadgetbridge only)* write `01 00` to `…0002` ("allow modifications").
3. *(Newer Wearfit Pro watches only)* the watch sends `AB … 20 …` (pairing/capability report). The app answers with a connect request: `AB LL LL FF E0 80 <type 0|1> <len> <phone model ≤12 B> <len> <uid ascii>`. The watch acks with `AB … 21 80 <0 ok | 1 fail | 2 already paired>`. Older watches skip this step, and Gadgetbridge never does it.
4. Send `AB 00 04 FF 92 80 01` (get device info). The reply `92` contains firmware `a[0].a[1]`; bytes 9–10 hold the model code.
5. Set the clock, and optionally the time zone, user info, and 12/24 h mode.
6. Request history with `51`.

## 4. Commands (phone → watch)

| Opcode | Frame | Meaning |
|---|---|---|
| `93` | `AB 00 0B FF 93 80 00 YY YY MM DD hh mm ss` | Set time (year big-endian) |
| `92` | `AB 00 04 FF 92 80 01` | Get device info / firmware |
| `71` | `AB 00 03 FF 71 80` | Find watch (vibrate) |
| `72` | `AB … FF 72 80 <src> 02 <utf8 text>` | Notification (`src`: 01 call, 03 SMS, 0A WhatsApp, 12 Instagram, 18 Telegram…) |
| `73` | `AB 00 09 FF 73 80 id on hh mm repeat flags` | Alarm (repeat bitmask Mon=01 … Sun=40, once=80) |
| `74` | `AB 00 0C FF 74 80 stepLen age height weight unit goal/1000 tempUnit energyUnit gender` | User info |
| `75`/`76` | `… on h1 m1 h2 m2` | Sedentary reminder / do-not-disturb |
| `77` | `AB 00 04 FF 77 80 on` | Raise-to-wake |
| `7A` | `… on` | Disconnect (anti-lost) alert |
| `7C` | `AB 00 04 FF 7C 80 0=24h/1=12h` | Time format |
| `7E`/`88`/`8A` | | Weather (see Gadgetbridge `sendWeather*`) |
| `84` | `AB 00 04 FF 84 80 on` | Live heart rate (Gadgetbridge) |
| `31` | `AB 00 04 FF 31 KIND on` | Measure: KIND `09` single HR, `0A` live HR, `11` single SpO2, `12` live SpO2, `21` single BP, `22` live BP |
| `51` | `AB 00 0E FF 51 80 00 Y M D h m Y M D h m` | Sync history after a date (year − 2000) |
| `A7` | `AB 00 04 FF A7 80 t` | MTU query |
| `23` | `AB 00 04 FF 23 80 00` | **Factory reset**: avoid (`genius_f1.py reset` sends it, after typed confirmation) |
| `FF` | `AB 00 03 FF FF 80` | **Reboot**, or shutdown if sent after `23` |

## 5. Reports (watch → phone)

`a[]` = bytes after the 6-byte header.

| CMD KIND | Payload |
|---|---|
| `91 80` | `a0` charging, `a1` battery % |
| `92 80` | firmware `a0.a1`, … |
| `84 80` | `a0` = HR (`00` measuring, `FF` no skin contact) |
| `31 xx` | live/single measurement results, same KIND codes as the `31` command |
| `51 08` | today's totals: steps = `a0<<16 | a1<<8 | a2`, second 24-bit value `a3..a5` (the app stores it with steps; likely distance or calories), then two h:m durations at `a6:a7` and `a8:a9` |
| `51 11` | HR history: `Y-2000 M D h m bpm` |
| `51 12` | SpO2 history: `Y M D h m %` |
| `51 14` | BP history: `Y M D h m v1 v2` |
| `51 20` | hourly steps: `Y M D h 00 stepsHi stepsLo …` |
| `52 xx` | sleep records |
| `7D 80` | watch "find my phone" (`a0` start/stop) |
| `20 80` | capability / pairing-type report (newer watches) |

## 6. Why Gadgetbridge doesn't pick up the F1

`WearFitCoordinator.getSupportedDeviceName()` returns `Pattern.compile("^HK.*")`. Gadgetbridge only auto-matches watches whose advertised name starts with `HK`, which excludes a name like `F1`. The protocol is the same, so you have two options:

- In Gadgetbridge, enable the setting that shows unsupported devices during discovery, then long-press the F1 and add it manually as the WearFit/HK type. The exact menu wording varies by version.
- Build Gadgetbridge yourself and change the pattern to something like `^(HK|F1).*`.

## 7. Confirmed on the real Genius F1 (report 2026-10-14)

Watch: `F1`, address `XX:XX:XX:XX:XX:XX` (kept out of the repo), firmware **3.24**, model code **162**. Linux/BlueZ 5.85, bleak 3.0.2.

It advertises services `FEE7` and `7905f431-…`. The Nordic UART service appears only after connecting, not in the advert, so scans must match the watch by name.

| Feature | Result | Bytes |
|---|---|---|
| Nordic UART service | present (handles 17 notify / 20 write) | |
| Device info `92` | works; the reply uses **kind `C0`**, not `80` | `ab000dff92c00318a2…22` |
| Battery `91` | works when **sent as a request** (Gadgetbridge only lists it as a report) | `ab0005ff91800050` → 80 %, not charging |
| Set time `93` | works (confirmed on screen) | no reply frame |
| Find `71` | works (vibrated) | no UART reply |
| Live HR, Gadgetbridge `84` | **no reply**: not supported on the F1 | |
| Live HR, Wearfit Pro `31 0A` | **works**: `AB 00 05 FF 31 0A 00 <bpm>`, repeated about every 5 s | `…310a0056` → 86 bpm |
| Single SpO2 `31 11` | reply only after OFF, value 37: measurement was probably cut short (25 s) | `…31110025` |
| MTU `A7` | no reply | |
| Write char `6e400002` | supports both write and write-without-response | |
| Sync `51` (last 7 days) | only `51 08` today = 0 steps; no history records | |

The watch also exposes the WeChat pedometer service `FEE7`:
- `FEC9` (read/notify): MAC address.
- `FEA1` (read/indicate): flag + steps, distance and calories, each 3 bytes little-endian.
- `FEA2` (read/write/indicate).

`FEA1` read 460 steps before the clock was set, then 0 afterwards. Setting the correct date most likely rolled the watch onto a "new day" and reset today's count.
