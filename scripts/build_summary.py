"""Turn the phone's day files into one summary.json for the dashboard.

    python scripts/build_summary.py --check                  # sanity rules only (CI)
    python scripts/build_summary.py --out site/summary.json  # build the dashboard data
    python scripts/build_summary.py --data schema/examples --out site/summary.json  # try it locally

Reads data/readings/YYYY/MM/YYYY-MM-DD.json and data/attempts/YYYY/MM/YYYY-MM-DD.json.
Standard library only, so CI needs nothing beyond Python.
"""
import argparse
import datetime as dt
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Plausible ranges. The app already keeps out-of-range values out of readings;
# this is the second line of defence before anything reaches main.
RANGES = {
    "hr": (30, 220),
    "spo2": (70, 100),
    "steps": (0, 200_000),
    "battery": (0, 100),
    "bp": (60, 260),  # first value; the order (sys/dia) is still unverified on the F1
}
BP2_RANGE = (30, 160)

MAC = re.compile(r"([0-9a-f]{2}:){5}[0-9a-f]{2}", re.I)
LOCATION_KEY = re.compile(r'"(lat|lon|lng|latitude|longitude|location|gps)"', re.I)

# Feature list shown on the dashboard. `kind` matches the attempts files.
FEATURES = [
    ("battery", "Battery", "confirmed"),
    ("info", "Firmware / model", "confirmed"),
    ("clock", "Set clock", "confirmed"),
    ("vibrate", "Vibrate (find watch)", "confirmed"),
    ("hr", "Heart rate", "confirmed"),
    ("steps", "Steps", "confirmed"),
    ("spo2", "Blood oxygen (SpO2)", "experimental"),
    ("bp", "Blood pressure", "experimental"),
    ("history", "History sync", "experimental"),
    ("notify", "Notifications to watch", "experimental"),
]
OUTCOMES = ["ok", "timeout", "out_of_range", "no_reply", "error"]


def parse_t(s):
    return dt.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)


def day_files(data_dir, kind):
    base = data_dir / kind
    if not base.is_dir():
        return []
    return sorted(base.glob("*/*/*.json"))


def load(path):
    text = path.read_text(encoding="utf-8")
    return text, json.loads(text)


def check_file(path, text, doc, kind):
    """Return a list of problems with one day file (empty when fine)."""
    problems = []
    rel = path.as_posix()
    if MAC.search(text):
        problems.append(f"{rel}: contains something shaped like a Bluetooth address")
    if LOCATION_KEY.search(text):
        problems.append(f"{rel}: contains a location field")
    date = doc.get("date", "")
    if path.stem != date:
        problems.append(f"{rel}: file name does not match date {date!r}")
    if not path.parent.name == date[5:7] or not path.parent.parent.name == date[:4]:
        problems.append(f"{rel}: should live in {kind}/{date[:4]}/{date[5:7]}/")
    try:
        day = dt.date.fromisoformat(date)
    except ValueError:
        return problems + [f"{rel}: bad date {date!r}"]

    rows = doc.get("readings" if kind == "readings" else "attempts", [])
    seen = set()
    for i, r in enumerate(rows):
        where = f"{rel} [{i}]"
        try:
            t = parse_t(r["t"])
        except (KeyError, ValueError):
            problems.append(f"{where}: bad timestamp {r.get('t')!r}")
            continue
        # Day files group by the phone's local date, timestamps are UTC: allow +-1 day.
        if abs((t.date() - day).days) > 1:
            problems.append(f"{where}: timestamp {r['t']} is far from the file's date")
        if kind == "attempts":
            continue
        key = (r["t"], r["type"], r["src"])
        if key in seen:
            problems.append(f"{where}: duplicate reading {key}")
        seen.add(key)
        lo, hi = RANGES[r["type"]]
        if not lo <= r["v"] <= hi:
            problems.append(f"{where}: {r['type']} value {r['v']} outside {lo}-{hi}")
        if r["type"] == "bp":
            if "v2" not in r:
                problems.append(f"{where}: blood pressure needs v2")
            elif not BP2_RANGE[0] <= r["v2"] <= BP2_RANGE[1]:
                problems.append(f"{where}: bp v2 {r['v2']} outside {BP2_RANGE[0]}-{BP2_RANGE[1]}")
        elif "v2" in r:
            problems.append(f"{where}: only blood pressure has v2")
    return problems


def build(data_dir):
    problems = []
    readings, attempts = [], []
    firmware = None
    for kind, sink in (("readings", readings), ("attempts", attempts)):
        for path in day_files(data_dir, kind):
            text, doc = load(path)
            problems += check_file(path.relative_to(data_dir), text, doc, kind)
            # Group by the file's date (the phone's local day), not the UTC date.
            sink.extend({**r, "day": doc.get("date")} for r in doc.get(kind, []))
            if kind == "readings":
                firmware = doc.get("device", {}).get("firmware") or firmware

    readings.sort(key=lambda r: r["t"])
    attempts.sort(key=lambda a: a["t"])

    by_day = defaultdict(lambda: {"steps": None, "hr": []})
    for r in readings:
        d = r["day"]
        if r["type"] == "steps":
            # The daily total only grows, except when a clock set resets it: use the max.
            cur = by_day[d]["steps"]
            by_day[d]["steps"] = r["v"] if cur is None else max(cur, r["v"])
        elif r["type"] == "hr":
            by_day[d]["hr"].append(r["v"])
    days = []
    for d in sorted(by_day):
        hr = by_day[d]["hr"]
        days.append({
            "date": d,
            "steps": by_day[d]["steps"],
            "hr": {"min": min(hr), "max": max(hr), "avg": round(sum(hr) / len(hr)), "n": len(hr)} if hr else None,
        })

    now = dt.datetime.now(dt.timezone.utc)
    cutoff = now - dt.timedelta(days=30)
    features = []
    for kind, name, status in FEATURES:
        recent = [a for a in attempts if a["kind"] == kind and parse_t(a["t"]) >= cutoff]
        ok = sum(a["outcome"] == "ok" for a in recent)
        if status == "experimental" and len(recent) >= 10 and ok == 0:
            status = "not working"
        features.append({"id": kind, "name": name, "status": status, "attempts30": len(recent),
                         "ok30": ok, "rate30": round(ok / len(recent), 3) if recent else None})

    reliability = defaultdict(lambda: dict.fromkeys(OUTCOMES, 0))
    for a in attempts:
        reliability[a["day"]][a["outcome"]] += 1

    battery = [{"t": r["t"], "v": r["v"]} for r in readings if r["type"] == "battery"]
    stamps = [r["t"] for r in readings] + [a["t"] for a in attempts]
    summary = {
        "generatedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "lastData": max(stamps) if stamps else None,
        "firmware": firmware,
        "daysLogged": len({r["day"] for r in readings + attempts}),
        "readingsLogged": len(readings),
        "attemptsLogged": len(attempts),
        "battery": battery[-1] if battery else None,
        "days": days,
        "batteryHistory": battery,
        "spo2": [{"t": r["t"], "v": r["v"]} for r in readings if r["type"] == "spo2"],
        "bp": [{"t": r["t"], "v": r["v"], "v2": r.get("v2")} for r in readings if r["type"] == "bp"],
        "features": features,
        "reliability": [{"date": d, **reliability[d]} for d in sorted(reliability)],
    }
    return summary, problems


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--data", type=Path, default=ROOT / "data", help="folder holding readings/ and attempts/")
    p.add_argument("--out", type=Path, help="write summary.json here")
    p.add_argument("--check", action="store_true", help="fail if any day file breaks a sanity rule")
    o = p.parse_args()

    summary, problems = build(o.data.resolve())
    for msg in problems:
        print(f"::error::{msg}" if o.check else f"warning: {msg}", file=sys.stderr)
    if o.check:
        print(f"{summary['readingsLogged']} readings, {summary['attemptsLogged']} attempts, "
              f"{len(problems)} problem(s)")
        if problems:
            sys.exit(1)
    if o.out:
        o.out.parent.mkdir(parents=True, exist_ok=True)
        o.out.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        print(f"wrote {o.out} ({summary['readingsLogged']} readings over {summary['daysLogged']} days)")


if __name__ == "__main__":
    main()
