#!/usr/bin/env python3
"""Summarise a RadioProbe logcat capture.

    adb shell setprop log.tag.RadioProbe DEBUG
    adb logcat -s RadioProbe:D > probe.txt      # while a run is going
    python3 tools/radio_probe_summary.py probe.txt

It answers the two questions the probe exists for (see TODO.md, Radio 2026-09-14):

1. Is the serving cell's own signal strength (`CELL`, from getAllCellInfo) fresher than
   `SignalStrength` (`SS`)? And does asking the modem (`REQCELL`, requestCellInfoUpdate) help?
   For each source: polls, refreshes (distinct modem timestamps) and the age of the value
   (now - ts), median and max.
2. On 5G NSA, does the cell list carry the NR leg? It counts the polls where `SS` had an NR
   RSRP, and how many of those had an NR cell in the matching `CELL` list.

Both clocks in a line are the boot clock in milliseconds, so the ages need no conversion.
"""

import re
import statistics
import sys
from collections import defaultdict

LINE = re.compile(r"\b(SS|CELL|REQCELL|REQERR) (.*)$")


def fields(rest: str) -> dict:
    return dict(kv.split("=", 1) for kv in rest.split() if "=" in kv)


def summarise(lines) -> str:
    polls = defaultdict(set)
    stamps = defaultdict(set)
    ages = defaultdict(list)
    errors = 0
    ss_nr_polls = []
    cell_types_at = defaultdict(set)

    for line in lines:
        m = LINE.search(line)
        if not m:
            continue
        kind, f = m.group(1), fields(m.group(2))
        if kind == "REQERR":
            errors += 1
            continue
        now = int(f["now"])
        if kind == "SS":
            source = "SignalStrength (SS)"
            if f.get("nrRsrp", "na") != "na":
                ss_nr_polls.append(now)
        else:
            if f.get("n") == "0":
                polls[kind].add(now)
                continue
            if kind == "CELL":
                cell_types_at[now].add(f["type"])
            # Only the serving cell's age is the one publishing would use.
            if f.get("status") != "1" and f.get("reg") != "true":
                continue
            source = f"{kind} serving {f['type']}"
        polls[source].add(now)
        ts = int(f["ts"])
        if ts <= 0:
            continue
        stamps[source].add(ts)
        ages[source].append((now - ts) / 1000)

    out = [f"{'source':<28} {'polls':>6} {'refreshes':>9}  age s (median / max)"]
    for source in sorted(k for k in polls if k in ages or k == "SignalStrength (SS)"):
        a = ages.get(source) or [float("nan")]
        out.append(
            f"{source:<28} {len(polls[source]):>6} {len(stamps[source]):>9}  "
            f"{statistics.median(a):.1f} / {max(a):.1f}"
        )
    if errors:
        out.append(f"requestCellInfoUpdate errors: {errors}")

    # Pair each SS poll with the CELL poll logged closest to it (they come from one provider poll).
    cell_nows = sorted(cell_types_at)
    with_nr = 0
    for now in ss_nr_polls:
        if not cell_nows:
            break
        nearest = min(cell_nows, key=lambda c: abs(c - now))
        if abs(nearest - now) < 500 and "NR" in cell_types_at[nearest]:
            with_nr += 1
    out.append("")
    out.append(
        f"NSA check: SS reported an NR leg on {len(ss_nr_polls)} polls; "
        f"the cell list carried an NR cell on {with_nr} of them"
    )
    return "\n".join(out)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with open(sys.argv[1], errors="replace") as f:
        print(summarise(f))
