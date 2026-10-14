# HealthLog

HealthLog keeps a budget smartwatch (the Genius F1) working after its vendor app (Wearfit) lost
support, using reverse-engineered Bluetooth. An Android app speaks the watch's WearFit protocol
directly, stores readings on the phone, and syncs them to this repo as pull
requests. A check validates them, and a public dashboard on GitHub Pages rebuilds
after each merge.

```
Genius F1 ──BLE──▶ Android app (Room DB) ──PR every 6 h──▶ validate check ──merge──▶ Pages dashboard
```

The full plan is in [`docs/Genius F1 Android App Implementation Plan.md`](docs/Genius%20F1%20Android%20App%20Implementation%20Plan.md).

## Layout

| Path | What |
| --- | --- |
| `android/` | Kotlin + Jetpack Compose app (open in Android Studio) |
| `tools/` | Laptop tools: `genius_f1.py`, `f1_report.py`, `run.sh`, and the protocol write-up [`PROTOCOL.md`](tools/PROTOCOL.md) |
| `data/` | Day files written **only** by the phone: `readings/YYYY/MM/DATE.json`, `attempts/YYYY/MM/DATE.json` |
| `schema/` | JSON Schemas the CI check validates against, plus hand-written examples |
| `scripts/build_summary.py` | Sanity check (`--check`) and dashboard data builder (`--out site/summary.json`) |
| `site/` | The dashboard: `index.html` + `app.js` + Chart.js from a CDN, no build step |
| `.github/workflows/` | `validate.yml` (required check on PRs), `pages.yml` (deploy on merge), `android.yml` (tests + APK) |

## Status

| Phase | State |
| --- | --- |
| 0. Finish the protocol on the laptop | Open: SpO2, blood pressure, history sync and notifications still need captures |
| 1. Protocol module + golden tests | Done: 28 tests, frames from the real watch and `genius_f1.py` |
| 2. Connect and talk (pairing, BLE manager, debug screen) | Written and builds; **not yet tried on the watch** |
| 3. Store it (Room, live-HR service, snapshot job, dashboard) | Written and builds; needs the 24-hour test on the watch |
| 4. GitHub pipeline without the phone | Files ready; repo not created yet (see below) |
| 5. Phone to GitHub (settings, client, export worker) | Written and unit-tested against a mock server |

## Building the app

Open `android/` in Android Studio and press Run with the phone plugged in (USB debugging on),
or from a terminal:

```bash
cd android
./gradlew testDebugUnitTest      # protocol, exporter and GitHub client tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Before the first connection: uninstall or disable Wearfit on that phone and close the laptop
tools. The watch accepts one connection at a time.

**First run on the watch (phase 2 checklist):** Settings → Pair watch, then Debug → Connect.
You should see battery 80 %-ish and firmware 3.24; Vibrate should buzz; Live HR on should show
bpm about every 5 s. Then disconnect and reconnect 10 times in a row.

## One-time GitHub setup (phase 4)

1. Create the repo (public), push this folder. Before the first push, check for leaked Bluetooth addresses:
   `git grep -i -E "[0-9a-f]{2}(:[0-9a-f]{2}){5}"` should print nothing.
2. Settings → General: turn on **Allow auto-merge** and **Automatically delete head branches**.
3. Settings → Branches: add a rule on `main` requiring the **validate** check.
4. Settings → Pages: source **GitHub Actions**.
5. Create a [fine-grained token](https://github.com/settings/personal-access-tokens) for this repo only,
   with **Contents** and **Pull requests** read/write and an expiry. Paste it into the app's Settings.

To preview the dashboard locally with the example data:

```bash
mkdir -p /tmp/f1/readings/2026/10 /tmp/f1/attempts/2026/10
cp schema/examples/readings/*.json /tmp/f1/readings/2026/10/
cp schema/examples/attempts/*.json /tmp/f1/attempts/2026/10/
python scripts/build_summary.py --data /tmp/f1 --out site/summary.json
python -m http.server -d site 8000
```

## Privacy

The repo and dashboard are public by design. **Never collected:** location (the app has no
location permission), contacts, phone notifications. **Never written to the repo:** the watch's
or phone's Bluetooth address, serial numbers, the GitHub token, `tools/reports/`. The schemas
reject unknown keys, and `validate.yml` rejects anything shaped like a Bluetooth address.
