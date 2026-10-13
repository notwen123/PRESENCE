"""Figures and measurements for the PRESENCE whitepaper. Every number comes from the code or the devnet database.

    uv run --with matplotlib --with numpy python docs/whitepaper/make_figures.py

Writes docs/whitepaper/fig/*.pdf and docs/whitepaper/measured.json (quoted in the paper).
"""
import json, os, sqlite3, sys, time
from statistics import median, pstdev
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
sys.path.insert(0, os.path.join(ROOT, "backend"))
from attest import anomaly, evidence, policy as pol  # noqa: E402
from attest.evidence import CheckpointData  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "fig"); os.makedirs(OUT, exist_ok=True)
GREEN, INK, MUTE, RED, AMBER = "#1f9d55", "#1d2b24", "#8a958f", "#c8473b", "#d99a2b"
plt.rcParams.update({"font.family": "serif", "font.size": 8.5, "axes.spines.top": False, "axes.spines.right": False,
                     "axes.edgecolor": INK, "axes.labelcolor": INK, "xtick.color": INK, "ytick.color": INK,
                     "figure.dpi": 200, "savefig.bbox": "tight", "legend.frameon": False})
def save(f, name): f.savefig(os.path.join(OUT, name + ".pdf")); plt.close(f)

db = sqlite3.connect(os.path.join(ROOT, ".devnet/attest.db"))
M = {}

# ------------------------------------------------------------------ devnet sessions
rows = db.execute("select s.id, s.mission_id, s.state, s.receipt, group_concat(c.response_ms), group_concat(c.touch_motion),"
                  " group_concat(c.still_pct) from sessions s join checkpoints c on c.session_id=s.id group by s.id").fetchall()
M["sessions_total"], M["wallets"] = db.execute("select count(*), count(distinct wallet) from sessions").fetchone()
M["states"] = dict(db.execute("select state, count(*) from sessions group by state").fetchall())
M["checkpoints"] = db.execute("select count(*) from checkpoints").fetchone()[0]
for t in ("claims", "stakes", "sponsor_deposits", "freeze_orders", "squads", "squad_members", "faucet_grants"):
    M[t] = db.execute(f"select count(*) from {t}").fetchone()[0]
M["stake_status"] = dict(db.execute("select status, count(*) from stakes group by status").fetchall())
M["window"] = db.execute("select min(datetime(created_at,'unixepoch')), max(datetime(created_at,'unixepoch')) from sessions").fetchone()

cause = {"left foreground": 0, "missed check-ins": 0}
for _, _, st, rc, *_ in rows:
    if st == "REJECTED":
        d = json.loads(rc)["detail"]
        cause["left foreground" if "foreground" in d else "missed check-ins"] += 1
M["reject_causes"] = cause

ints = lambda s: [int(x) for x in s.split(",")]
resp = [r for *_, rs, _, _ in rows for r in ints(rs) if r >= 0]
M["answers"] = len(resp); M["answer_ms"] = dict(min=min(resp), median=median(resp), max=max(resp), under_floor=sum(r < 150 for r in resp))

# Touch motion by source. 2189/2190 mm/s^2 = the emulator's virtual-accelerometer knock (a fixed synthetic jolt);
# 1..734 with still 0% on Oct 11-12 = adb `input tap` on the real realme phone (scripts/device_e2e.py): injected input.
emu, injected = [], []
for _, _, _, _, rs, tm, sp in rows:
    for t in ints(tm):
        if t in (2189, 2190): emu.append(t)
        elif t > 0: injected.append(t)
M["injected_taps"] = dict(n=len(injected), min=min(injected), median=median(injected), max=max(injected),
                          below_floor=sum(t < 150 for t in injected))
M["emulator_knocks"] = dict(n=len(emu))

# per-session anomaly risk (as the server computes it)
risks = []
for sid, mid, st, rc, rs, tm, sp in rows:
    a = [r for r in ints(rs) if r >= 0]
    if len(a) >= anomaly.MIN_SAMPLES:
        risks.append((sum(r < 150 for r in a) / len(a), pstdev(a), anomaly.assess(a).risk, st))
M["risk_sessions"] = len(risks); M["risk_max"] = max(r[2] for r in risks)
M["sigma_min"] = round(min(r[1] for r in risks)); M["sigma_median"] = round(median(r[1] for r in risks))

# ------------------------------------------------------------------ Fig: anomaly risk surface + measured sessions
fs = np.linspace(0, 1, 301); sg = np.logspace(0, 3.6, 301); F, S = np.meshgrid(fs, sg)
steady = np.where(S < anomaly.STEADY_STDEV_MS, 1.0, np.where(S < anomaly.SUSPICIOUS_STDEV_MS, 0.6, 0.0))
R = 1 - (1 - F) * (1 - steady)
f, ax = plt.subplots(figsize=(3.4, 2.5))
im = ax.pcolormesh(F, S, R, cmap="Greens", shading="auto", vmin=0, vmax=1, rasterized=True)
ax.contour(F, S, R, levels=[anomaly.THRESHOLD], colors=RED, linewidths=1.1)
ax.scatter([r[0] for r in risks], [r[1] for r in risks], s=11, c=INK, marker="o", zorder=3,
           label=f"devnet sessions (n={len(risks)})")
ax.set_yscale("log")
ax.axhline(15, color=MUTE, lw=.6, ls=":"); ax.axhline(40, color=MUTE, lw=.6, ls=":")
ax.text(0.98, 9, r"$\sigma<15$: steady=1", ha="right", fontsize=6.5, color="white")
ax.text(0.98, 24, r"$\sigma<40$: steady=0.6", ha="right", fontsize=6.5, color="white")
ax.text(0.52, 2.0, r"reject: $\rho\geq0.9$", color="white", fontsize=7)
ax.set_xlabel(r"share of answers under 150 ms, $\phi$"); ax.set_ylabel(r"answer-time spread $\sigma$ (ms)")
f.colorbar(im, ax=ax, label=r"risk $\rho$", pad=0.02); ax.legend(loc="upper left", fontsize=6.3)
save(f, "risk_surface")

# ------------------------------------------------------------------ Fig: answer-time histogram
f, ax = plt.subplots(figsize=(3.4, 2.0))
ax.hist(np.array(resp) / 1000, bins=np.arange(0, 10.01, 0.5), color=GREEN, edgecolor="white", lw=.6)
ax.axvline(0.15, color=RED, lw=1); ax.annotate("150 ms human floor", xy=(0.15, ax.get_ylim()[1] * 0.9), xytext=(5.5, ax.get_ylim()[1] * 0.9), color=RED, fontsize=7, va="center", arrowprops=dict(arrowstyle="->", color=RED, lw=.7))
ax.set_xlabel("time from check-in shown to answered (s)"); ax.set_ylabel("check-ins")
save(f, "answer_times")

# ------------------------------------------------------------------ Fig: touch motion by source (log scale)
f, ax = plt.subplots(figsize=(3.4, 1.75))
rng = np.random.default_rng(3)
ax.scatter(injected, 1 + rng.uniform(-.18, .18, len(injected)), s=12, color=AMBER, label="adb-injected taps, real phone")
ax.scatter(emu, 0 + rng.uniform(-.18, .18, len(emu)), s=12, color=GREEN, label="virtual-accelerometer knocks, emulator")
ax.axvline(150, color=RED, lw=1); ax.text(160, 1.45, r"$\tau_{touch}=150$", color=RED, fontsize=7)
ax.set_xscale("log"); ax.set_xlim(10, 5000); ax.set_ylim(-.6, 1.75); ax.set_yticks([0, 1], ["emulator", "real phone"])
ax.set_xlabel(r"largest jolt within 300 ms of the answer, $\mu$ (mm/s$^2$)")
save(f, "touch_motion")

# ------------------------------------------------------------------ Fig: unpredictable check-in timing
f, ax = plt.subplots(figsize=(3.4, 1.55))
for i in range(4):
    ax.add_patch(plt.Rectangle((i * 30 + 0.15 * 30, 0.2), 0.45 * 30, 0.6, color=GREEN, alpha=.25, lw=0))
    ax.axvline(i * 30, color=MUTE, lw=.6)
    t = i * 30 + 30 * (0.15 + rng.uniform() * 0.45); ax.plot([t], [0.5], marker="v", color=INK, ms=5)
ax.axvline(120, color=MUTE, lw=.6)
for i in range(1, 5): ax.text(i * 30 - 1, 0.92, f"cp$_{i-1}$ sealed", ha="right", fontsize=6.3)
ax.set_xlim(-2, 124); ax.set_ylim(0, 1.05); ax.set_yticks([]); ax.set_xlabel("session time (s), quick mission: 4 windows of 30 s")
ax.spines["left"].set_visible(False)
save(f, "checkin_timing")

# ------------------------------------------------------------------ Fig: cool-down vs forfeiture (expected cost of a quit)
COOLDOWN_DAYS = (7, 14, 30)
f, (a1, a2) = plt.subplots(1, 2, figsize=(7.0, 2.1), gridspec_kw={"width_ratios": [1, 1.35]})
k = np.arange(1, 7); lock = [COOLDOWN_DAYS[min(i - 1, 2)] for i in k]
a1.bar(k, lock, color=GREEN, width=.6); [a1.text(x, y + 0.8, f"{y} d", ha="center", fontsize=7) for x, y in zip(k, lock)]
a1.set_xlabel("quit number within 30 days, $k$"); a1.set_ylabel("stake locked (days)"); a1.set_ylim(0, 36)
p = np.linspace(0, 1, 101); s = 1.0; r = 0.10
a2.plot(p, (1 - p) * s, color=RED, lw=1.4, label="forfeit to operator/charity (stickK-style)")
for d, ls in ((7, "-"), (30, "--")):
    a2.plot(p, (1 - p) * s * r * d / 365, color=GREEN, lw=1.4, ls=ls, label=f"FACEDOWN Stake, {d}-day lock (time value at r=10%/yr)")
a2.set_xlabel("probability of finishing, $p$"); a2.set_ylabel("expected cost per session (stakes)")
a2.set_yscale("log"); a2.set_ylim(1e-4, 1.5); a2.legend(fontsize=6.2, loc=(0.02, 0.32))
save(f, "cooldown")

# ------------------------------------------------------------------ Fig: bonus vs pool + streak survival (models)
f, (a1, a2) = plt.subplots(1, 2, figsize=(7.0, 2.15))
pool = np.linspace(0, 1.5, 301)
for st, c in ((1.0, GREEN), (5.0, INK)):
    a1.plot(pool, np.minimum(round(st * 0.2, 6), np.maximum(pool, 0)), color=c, lw=1.4, label=f"stake {st:g} SKR, $\\beta$=0.2")
a1.set_xlabel("pool remaining $P-R$ (SKR)"); a1.set_ylabel("bonus paid (SKR)"); a1.legend(fontsize=6.5)
days = np.arange(0, 61)
def survive(q, freezes, n=1):
    qd = q ** n  # a squad day counts only if every member verified
    out = []
    from math import comb
    for d in days:  # P(at most `freezes` misses in d days), freezes not replenished
        out.append(sum(comb(d, m) * (1 - qd) ** m * qd ** (d - m) for m in range(0, freezes + 1)))
    return np.array(out)
a2.plot(days, survive(0.9, 0), color=MUTE, lw=1.2, label="solo, q=0.9, no freeze")
a2.plot(days, survive(0.9, 1), color=GREEN, lw=1.4, label="solo, q=0.9, 1 freeze")
a2.plot(days, survive(0.9, 0, 3), color=AMBER, lw=1.2, label="squad of 3, q=0.9 each")
a2.plot(days, survive(0.97, 0, 3), color=INK, lw=1.2, ls="--", label="squad of 3, q=0.97 each")
a2.set_xlabel("days"); a2.set_ylabel("P(streak still alive)"); a2.legend(fontsize=6.2); a2.set_ylim(0, 1.02)
save(f, "economics")

# ------------------------------------------------------------------ outcomes bar
f, ax = plt.subplots(figsize=(3.4, 1.7))
order = ["SETTLED", "REJECTED", "ACTIVE", "CREATED"]
vals = [M["states"].get(k, 0) for k in order]; cols = [GREEN, RED, AMBER, MUTE]
ax.barh(order[::-1], vals[::-1], color=cols[::-1], height=.6)
for y, v in enumerate(vals[::-1]): ax.text(v + .4, y, str(v), va="center", fontsize=7)
ax.set_xlabel(f"devnet sessions ({M['window'][0][:10]} to {M['window'][1][:10]})")
save(f, "outcomes")

# ------------------------------------------------------------------ latency: chain recompute + policy evaluate
from dataclasses import replace
from pathlib import Path
P = pol.load_missions(Path(ROOT, "backend/missions.json"))["quick-free"]
lat = {}
for n in (4, 16, 64, 256):
    cps = [CheckpointData(i, 1_760_000_000_000 + i * 30_000, i * 30_000, True, 1, 1800 + 97 * i, 100, 410) for i in range(n)]
    obs = [pol.ObservedCheckpoint(i, 1000.0 + (i + 1) * 30, True, 1, 1800 + 97 * i, 100, 410) for i in range(n)]
    pn = replace(P, required_checkpoints=n, duration_seconds=n * 30)
    ts = []
    for _ in range(2000):
        t0 = time.perf_counter_ns()
        evidence.evidence_root(evidence.chain("sess", "nonce", cps)[-1]); pol.evaluate(pn, 1000.0, 1000.0 + n * 30 + 1, obs, 2)
        ts.append((time.perf_counter_ns() - t0) / 1000)
    ts.sort(); lat[n] = dict(p50=round(ts[1000], 1), p99=round(ts[1980], 1))
M["latency_us"] = lat
json.dump(M, open(os.path.join(os.path.dirname(__file__), "measured.json"), "w"), indent=1, default=str)
print(json.dumps(M, indent=1, default=str))
