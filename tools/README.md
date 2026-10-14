# Genius F1 BLE tools

The laptop-side reference implementation of the watch protocol. The Android app's
`protocol/` package is a port of `genius_f1.py`, and its golden tests use frames captured here.

**Privacy:** `reports/` is git-ignored and must never be committed. Reports hold the watch's and the
laptop's Bluetooth addresses, the laptop's name and nearby devices. Put the watch address in a
git-ignored `tools/.env` (`F1_ADDRESS=XX:XX:XX:XX:XX:XX`) rather than in any tracked file.

## Moving to the Linux PC

Copy this `tools/` folder, but **not** any `.venv*` folder (virtual environments don't move between machines).

## First run (Linux)

```bash
cd path/to/repo/tools
bash run.sh setup      # checks Bluetooth, creates .venv-linux, installs bleak
bash run.sh report     # full diagnostic -> reports/f1_report_<time>.md
```

Use `bash run.sh` rather than `./run.sh`, because copying from Windows usually drops the executable bit. With no arguments it opens a menu.

Before running `report`:
- Turn Bluetooth **off on your phone**, or force-stop Wearfit. The watch accepts only one connection, and it won't show up while the phone holds it.
- Wear the watch. The heart-rate and SpO2 tests need skin contact.
- Answer the y/n questions ("did it vibrate?", "is the time right?").

Then send back `reports/f1_report_<time>.md`. The `.json` next to it has the same data in raw form.

## Requirements on the Linux PC

| Need | Install (Debian/Ubuntu/Mint; Fedora: dnf, Arch: pacman) |
|---|---|
| Python 3.10+ with venv | `sudo apt install python3 python3-venv` |
| BlueZ + running service | `sudo apt install bluez && sudo systemctl enable --now bluetooth` |
| Adapter unblocked and powered | `rfkill unblock bluetooth`, `bluetoothctl power on` |
| Internet (first setup only) | to download `bleak` |

`bash run.sh check` re-runs only the system checks.

## Other commands

```bash
bash run.sh scan               # list nearby devices
bash run.sh find   <ADDRESS>   # vibrate the watch
bash run.sh info   <ADDRESS>   # set time, show firmware/battery
bash run.sh hr     <ADDRESS>   # live heart rate (Ctrl+C to stop)
bash run.sh spo2   <ADDRESS>   # one blood-oxygen reading - keep still, up to 60 s
bash run.sh bp     <ADDRESS>   # one blood-pressure reading - keep still, up to 60 s
bash run.sh steps  <ADDRESS>   # today's steps from both channels the watch offers
bash run.sh notify <ADDRESS> "Hi"  # show a message on the watch
bash run.sh sync   <ADDRESS>   # last 7 days of history
bash run.sh listen <ADDRESS>   # print everything the watch sends
bash run.sh report --quick     # report without the slow sensor tests
```

`run.ps1` does the same things if you'd rather use PowerShell (`pwsh ./run.ps1 report` on Linux).
