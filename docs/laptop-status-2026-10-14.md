# Genius F1 project status (2026-10-14)

## Goal
Connect to the Genius F1 smartwatch over Bluetooth LE without the discontinued Wearfit app.

## Done
- Decompiled Gadgetbridge and Wearfit Pro 5.6.16 with REA/JADX. The F1 uses the **WearFit protocol**: Nordic UART service `6e400001…`, with frames `AB LEN_HI LEN_LO FF CMD KIND ARGS`. The full spec is in `PROTOCOL.md`.
- Wrote the tools:
  - `genius_f1.py`: commands scan, info, hr, find, sync, listen and raw.
  - `f1_report.py`: diagnostic report.
  - `run.sh` / `run.ps1`: launchers.
  - `README.md`: setup on the Linux PC.
- First real test on the Linux PC; the results are in `Reports/f1_report.json`.

## Confirmed on the watch
| Feature | Status |
|---|---|
| Connect (no pairing needed) | works (address kept out of the repo) |
| Device info | firmware 3.24, model 162 |
| Battery | works (80%) |
| Set time | works |
| Find watch (vibrate) | works |
| Live heart rate (`31 0A`) | works (86 bpm) |
| Gadgetbridge heart-rate command (`84`) | **not supported**, so stock Gadgetbridge can't do live HR |
| MTU query (`A7`) | no reply |

## Still open
- **SpO2:** got 37% because the measurement was cut short. The report now waits 60 s.
- **Blood pressure:** untested.
- **History sync:** returned no records. The report now also asks for everything since 2000.
- **Notifications:** untested. The report sends "Hello from PC".
- **Sleep data (`52`):** not explored.
- **WeChat step service (`FEE7`/`FEA1`):** seen but not verified. Today's steps reset after setting the clock.

## Next step
1. Copy `genius_f1.py`, `f1_report.py` and `PROTOCOL.md` to the Linux PC.
2. Turn off Bluetooth on your phone and wear the watch.
3. Run `bash run.sh report`. Keep still during the two 1-minute measurements, and answer the questions about what the watch screen shows.
4. Bring back `reports/f1_report_<time>.md` and the matching `.json`.
