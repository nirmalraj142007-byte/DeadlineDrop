"""Reads the last demo rush's vu-log and Lab 4's export; prints lost / duplicates (see check.sh)."""
import json
import re
import sys
from collections import Counter

LIVE = {"COMMITTED", "UPLOADED", "VERIFIED"}


def main(rush_dir: str) -> int:
    # The console on Windows may not be UTF-8; the "·" below must survive.
    sys.stdout.reconfigure(encoding="utf-8")
    with open(rush_dir + "/export.json", encoding="utf-8") as f:
        rows = json.load(f)
    live = Counter(r["studentEmail"] for r in rows if r["status"] in LIVE)
    status_of = {r["studentEmail"]: r["status"] for r in rows if r["status"] in LIVE}

    entries = {}
    with open(rush_dir + "/vu-log.ndjson", encoding="utf-8") as f:
        for raw in f:
            # k6 console output is logfmt: time=... level=info msg="<escaped json>"
            m = re.search(r'msg=(".*")\s*$', raw.strip())
            if not m:
                continue
            try:
                e = json.loads(json.loads(m.group(1)))
            except ValueError:
                continue
            if isinstance(e, dict) and "email" in e and "commitStatus" in e:
                entries[e["email"]] = e

    acked = [em for em, e in entries.items() if e["commitStatus"] in (200, 201)]
    lost = [em for em in acked if live[em] == 0]
    dups = [em for em, n in live.items() if n > 1]
    skipped = [em for em in acked if entries[em].get("skipFinalize")]
    settled = [em for em in skipped if status_of.get(em) in ("UPLOADED", "VERIFIED")]

    print(f"lost {len(lost)} · duplicates {len(dups)} (check)")
    print(f"skipped finalizes that ended UPLOADED or VERIFIED: {len(settled)} of {len(skipped)}")
    waiting = [em for em in skipped if status_of.get(em) == "COMMITTED"]
    if waiting:
        print(f"  {len(waiting)} still COMMITTED: the reconciler only settles commits older than "
              "2 minutes; wait and run make check again")
    print(f"students in log: {len(entries)}, commits acknowledged: {len(acked)}, "
          f"live rows in export: {sum(live.values())}")
    if lost:
        print("lost students:", ", ".join(sorted(lost)[:10]))
    return 1 if lost or dups else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: check.py <last-rush-dir>")
    sys.exit(main(sys.argv[1]))
