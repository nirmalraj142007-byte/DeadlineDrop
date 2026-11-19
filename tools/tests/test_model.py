"""Tests for tools/model/model.py: the three rules at their extremes, the N rule, the profiles."""

import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS / "model"))
import model  # noqa: E402


def inputs_with(rate_mbps, rtt_ms=40.0, sizes=(1, 5, 25), hash_mib_per_s=80.0):
    cohorts = (model.Cohort("only", 1.0, rate_mbps, rtt_ms),)
    probs = tuple(1.0 / len(sizes) for _ in sizes)
    return model.Inputs(cohorts=cohorts, sizes_mib=tuple(sizes), size_probs=probs,
                        hash_mib_per_s=hash_mib_per_s, window_min=10.0)


def rates(inputs, samples=200):
    mc = model.monte_carlo(inputs, samples=samples, seed=1)
    out = {}
    for s in model.SPEED_MULTIPLIERS:
        ex = model.expected(inputs, s)
        for a in model.ARMS:
            out[(s, a)] = (ex["arms"][a]["cohort_rate"], mc[(s, a)]["cohort_rate"])
    return out


def test_instant_uploads_give_arms_a_and_b_rate_zero():
    # 10^9 Mbps and no RTT: transfer + 3r is microseconds, far under the 5 s on-time line
    result = rates(inputs_with(rate_mbps=1e9, rtt_ms=0.0))
    for s in model.SPEED_MULTIPLIERS:
        for a in ("A", "B"):
            exact, run = result[(s, a)]
            assert exact == 0.0
            assert run["p97_5"] == 0.0 and run["mean"] == 0.0


def test_uploads_longer_than_any_click_give_arms_a_and_b_rate_one():
    # 1 MiB at 0.001 Mbps takes 8,389 s at 1x and 4,194 s at 2x: longer than the 600 s window
    result = rates(inputs_with(rate_mbps=0.001))
    for s in model.SPEED_MULTIPLIERS:
        for a in ("A", "B"):
            exact, run = result[(s, a)]
            assert exact == pytest.approx(1.0)
            assert run["p2_5"] == 1.0 and run["mean"] == 1.0


@pytest.mark.parametrize("rtt_ms,hash_rate", [(40.0, 80.0), (500.0, 10.0), (0.0, 1e9)])
def test_arm_c_rate_is_zero_whenever_hash_plus_rtt_is_under_five_seconds(rtt_ms, hash_rate):
    inp = inputs_with(rate_mbps=0.001, rtt_ms=rtt_ms, hash_mib_per_s=hash_rate)
    worst = max(inp.sizes_mib) / hash_rate + rtt_ms / 1000.0
    assert worst < model.ON_TIME_S
    result = rates(inp)
    for s in model.SPEED_MULTIPLIERS:
        exact, run = result[(s, "C")]
        assert exact == 0.0
        assert run["p97_5"] == 0.0


def test_arm_c_is_late_when_hash_plus_rtt_exceeds_the_click():
    flags = model.late_flags(c_s=[6.0, 6.0], size_bytes=[1, 1], rate_bps=[1e12, 1e12],
                             rtt_s=[0.1, 0.1], hash_s=[1.0, 6.5])
    assert flags["C"].tolist() == [False, True]


def test_rules_match_the_formulas():
    # 10 MiB at 8 Mbps = 10.486 s; r = 1 s -> A threshold 11.49 s, B threshold 13.49 s
    c = np.array([11.0, 12.0, 14.0])
    flags = model.late_flags(c, 10 * model.MIB, 8e6, 1.0, 0.125)
    assert flags["A"].tolist() == [True, False, False]
    assert flags["B"].tolist() == [True, True, False]
    assert flags["C"].tolist() == [False, False, False]


def test_expected_matches_monte_carlo():
    inp = inputs_with(rate_mbps=2.0)
    ex = model.expected(inp, 1.0)
    mc = model.monte_carlo(inp, samples=400, seed=3)
    for a in ("A", "B"):
        assert mc[(1.0, a)]["cohort_rate"]["mean"] == pytest.approx(ex["arms"][a]["cohort_rate"], abs=0.005)


@pytest.mark.parametrize("minutes,expected_n", [
    ([], 10),
    ([0.5] * 29, 10),                          # under 30 submissions
    ([0.5] * 10 + [100.0] * 20, 5),            # third inside the first minute -> clamped up to 5
    ([7.2] * 10 + [100.0] * 20, 8),            # third reached at 7.2 min -> 8
    ([40.0] * 30, 15),                         # clamped down to 15
])
def test_n_rule(minutes, expected_n):
    assert model.choose_n(minutes)[0] == expected_n


def test_profiles_are_cited_by_the_csv():
    cited = model.cite_profiles(model.read_profiles_csv(REPO / "results/network/public-profiles.csv"))
    assert set(cited) == {"slow", "medium", "fast"}
    assert cited["slow"]["rate"]["cited"][0]["value"] == pytest.approx(7.124)


def test_a_drifted_profile_fails(tmp_path):
    rows = model.read_profiles_csv(REPO / "results/network/public-profiles.csv")
    for r in rows:
        if r["source"] == model.TILE_SOURCE and (r["segment"], r["metric"], r["statistic"]) == ("mobile", "upload", "p20"):
            r["value"] = "2.0"
    with pytest.raises(ValueError, match="slow rate"):
        model.cite_profiles(rows)


def test_cli_writes_outputs(tmp_path):
    proc = subprocess.run([sys.executable, str(TOOLS / "model/model.py"), "--profiles",
                           str(REPO / "results/network/public-profiles.csv"), "--out", str(tmp_path),
                           "--samples", "200"], capture_output=True, text=True)
    assert proc.returncode == 0, proc.stderr
    assert "N = 10" in proc.stdout
    out = json.loads((tmp_path / "model-output.json").read_text(encoding="utf-8"))
    assert out["N_minutes"] == 10
    assert set(out["results"]) == {"1x", "0.5x", "2x"}
    assert all(a["reason"] for a in out["assumptions"])
    assert (tmp_path / "sensitivity.csv").read_text(encoding="utf-8").count("\n") == 10


def test_profiles_schedule_uses_the_shaped_rates(tmp_path):
    proc = subprocess.run([sys.executable, str(TOOLS / "gen_schedule.py"), "--mode", "profiles", "--n", "200",
                           "--seed", "7", "--out", str(tmp_path)], capture_output=True, text=True)
    assert proc.returncode == 0, proc.stderr
    meta = json.loads((tmp_path / "schedule-meta.json").read_text(encoding="utf-8"))
    assert meta["mode"] == "PUBLIC_PROFILES"
    students = json.loads((tmp_path / "shard-all.json").read_text(encoding="utf-8"))
    for s in students:
        profile = meta["profiles"][s["cohort"]]
        assert s["mode"] == "PUBLIC_PROFILES"
        assert s["speedMbps"] == profile["rateMbps"] and s["rttMs"] == profile["rttMs"]
