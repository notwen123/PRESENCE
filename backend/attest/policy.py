"""Typed mission policy and the single place where policy is evaluated."""

import json
from dataclasses import dataclass, field
from pathlib import Path

from . import anomaly
from .errors import AttestError, Reason

ASSURANCE_LEVELS = {"P1": 1, "P2": 2, "P3": 3, "P4": 4}


@dataclass(frozen=True)
class MissionPolicy:
    mission_id: str
    name: str
    description: str
    steps: list[str]
    required_assurance: str
    duration_seconds: int
    required_checkpoints: int
    checkpoint_interval_seconds: int
    min_interactions_per_checkpoint: int
    witness_required: bool
    max_claims_per_identity_per_day: int
    reward_xp: int
    reward_token: float = 0.0  # paid from the sponsor's pool in REWARD_MINT units (UI amount)
    reward_symbol: str = "SKR"
    stake_token: float = 0.0  # user's own stake (REWARD_MINT UI amount): returned on success, forfeited to the pool otherwise
    bonus_rate: float = 0.0   # finishers earn stake * bonus_rate from the pool (funded by forfeits and sponsors)
    no_loss: bool = False     # the stake is locked for the session and returned either way; only finishers earn
    cooldown: bool = False    # quitting locks the stake for 7 / 14 / 30 days, then returns it; nobody else wins it
    sponsor: str = ""
    why: str = ""
    timing_tolerance_seconds: int = 3
    # Physical evidence (accelerometer). 0 disables a rule. Thresholds need calibration on real phones.
    min_still_pct: int = 0        # each window, the phone lay still at least this share of the time
    min_touch_motion: int = 0     # each "I'm here" tap jolted the phone at least this much (mm/s^2)
    allowed_misses: int = 0       # check-ins that may go unanswered (N-1 rule: one soft knock can't kill a session)
    extra: dict = field(default_factory=dict)

    @property
    def assurance_level(self) -> int:
        return ASSURANCE_LEVELS[self.required_assurance]

    def public(self) -> dict:
        return {
            "mission_id": self.mission_id,
            "name": self.name,
            "description": self.description,
            "steps": self.steps,
            "required_assurance": self.required_assurance,
            "duration_seconds": self.duration_seconds,
            "required_checkpoints": self.required_checkpoints,
            "checkpoint_interval_seconds": self.checkpoint_interval_seconds,
            "min_interactions_per_checkpoint": self.min_interactions_per_checkpoint,
            "witness_required": self.witness_required,
            "max_claims_per_identity_per_day": self.max_claims_per_identity_per_day,
            "reward": {"xp": self.reward_xp, "token": self.reward_token, "symbol": self.reward_symbol},
            "stake": {"token": self.stake_token, "bonus_rate": self.bonus_rate, "no_loss": self.no_loss,
                      "cooldown": self.cooldown},
            "sponsor": self.sponsor,
            "why": self.why,
            "physical": {"min_still_pct": self.min_still_pct, "min_touch_motion": self.min_touch_motion},
        }


def load_missions(path: Path) -> dict[str, MissionPolicy]:
    raw = json.loads(path.read_text())
    missions = {}
    for m in raw:
        missions[m["mission_id"]] = build_policy(m)
    return missions


def build_policy(m: dict) -> MissionPolicy:
    """Validate one mission definition. Used for missions.json and for generated drafts."""
    m = dict(m)
    reward = m.pop("reward")
    stake = m.pop("stake", {})
    p = MissionPolicy(reward_xp=int(reward["xp"]), reward_token=float(reward.get("token", 0)),
                      reward_symbol=reward.get("symbol", "SKR"), stake_token=float(stake.get("token", 0)),
                      bonus_rate=float(stake.get("bonus_rate", 0)), no_loss=bool(stake.get("no_loss", False)),
                      cooldown=bool(stake.get("cooldown", False)), **m)
    if p.required_assurance not in ASSURANCE_LEVELS:
        raise ValueError(f"{p.mission_id}: unknown assurance {p.required_assurance}")
    if p.witness_required:
        # P3 witness flow is not implemented; refuse to load a policy we cannot enforce.
        raise ValueError(f"{p.mission_id}: witness_required is not supported yet")
    if not 0 <= p.allowed_misses < p.required_checkpoints:
        raise ValueError(f"{p.mission_id}: allowed_misses must leave at least one answered check-in")
    if p.required_checkpoints < 1 or p.checkpoint_interval_seconds < 5:
        raise ValueError(f"{p.mission_id}: need >= 1 checkpoint and >= 5 s between checkpoints")
    if p.required_checkpoints * p.checkpoint_interval_seconds != p.duration_seconds:
        raise ValueError(f"{p.mission_id}: duration must equal checkpoints x interval")
    if not 1 <= p.max_claims_per_identity_per_day <= 255:
        raise ValueError(f"{p.mission_id}: max claims per day must be 1..255 (on-chain claim index is a u8)")
    if p.cooldown and (p.stake_token <= 0 or p.no_loss):
        raise ValueError(f"{p.mission_id}: cooldown needs a stake and excludes no_loss")
    if p.no_loss and (p.stake_token <= 0 or p.bonus_rate):
        raise ValueError(f"{p.mission_id}: no_loss needs a stake and no bonus (nobody forfeits to fund one)")
    if not 0 <= p.bonus_rate <= 1:
        raise ValueError(f"{p.mission_id}: bonus_rate must be within 0..1")
    if not 0 <= p.min_still_pct <= 100 or p.min_touch_motion < 0:
        raise ValueError(f"{p.mission_id}: min_still_pct must be 0..100 and min_touch_motion >= 0")
    if p.reward_token < 0 or p.reward_xp < 0 or p.stake_token < 0:
        raise ValueError(f"{p.mission_id}: rewards cannot be negative")
    return p


@dataclass(frozen=True)
class ObservedCheckpoint:
    index: int
    received_at: float  # server clock
    foreground: bool
    interactions: int
    response_ms: int = -1
    still_pct: int = -1
    touch_motion: int = -1


def physical(policy: MissionPolicy, checkpoints: list[ObservedCheckpoint]) -> dict[str, list[str]]:
    """Problems with the physical evidence, per rule. Empty lists = passed (or the rule is off)."""
    touch, still = [], []
    for cp in checkpoints:
        if policy.min_touch_motion and cp.interactions > 0 and cp.touch_motion < policy.min_touch_motion:
            touch.append(f"checkpoint {cp.index}: tap moved the phone {max(cp.touch_motion, 0)} mm/s^2 "
                         f"(< {policy.min_touch_motion}); no finger touched it")
        if policy.min_still_pct and cp.still_pct < policy.min_still_pct:
            still.append(f"checkpoint {cp.index}: phone still {max(cp.still_pct, 0)}% of the time "
                         f"(< {policy.min_still_pct}%)")
    return {"human_touch": touch, "phone_down": still}


def evaluate(policy: MissionPolicy, started_at: float, submitted_at: float,
             checkpoints: list[ObservedCheckpoint], achieved_assurance: int,
             enforce_physical: bool = True) -> dict[str, str]:
    """Raise with every violated rule; on pass return the physical-check results for the receipt.

    All timing uses the server clock (authorize -> each checkpoint receipt -> submit),
    so a client cannot compress or backdate the process. With enforce_physical=False
    (development: emulators have no real motion) physical problems are reported, not fatal.
    """
    problems = []
    tol = policy.timing_tolerance_seconds

    if achieved_assurance < policy.assurance_level:
        problems.append(f"assurance P{achieved_assurance} < required {policy.required_assurance}")
    if len(checkpoints) < policy.required_checkpoints:
        problems.append(f"{len(checkpoints)}/{policy.required_checkpoints} checkpoints")
    if submitted_at - started_at < policy.duration_seconds - tol:
        problems.append(f"duration {submitted_at - started_at:.1f}s < {policy.duration_seconds}s")

    prev = started_at
    missed = []
    for cp in checkpoints:
        gap = cp.received_at - prev
        if gap < policy.checkpoint_interval_seconds - tol:
            problems.append(f"checkpoint {cp.index} arrived after {gap:.1f}s")
        if not cp.foreground:
            problems.append(f"checkpoint {cp.index}: session left foreground")
        if cp.interactions < policy.min_interactions_per_checkpoint:
            missed.append(f"checkpoint {cp.index}: {cp.interactions} interactions")
        prev = cp.received_at
    if len(missed) > policy.allowed_misses:
        problems.extend(missed)

    if problems:
        raise AttestError(Reason.POLICY_NOT_SATISFIED, "; ".join(problems))

    a = anomaly.assess([cp.response_ms for cp in checkpoints])
    if a.risk >= anomaly.THRESHOLD:
        raise AttestError(Reason.AUTOMATION_DETECTED, f"risk {a.risk}: " + "; ".join(a.reasons))

    found = physical(policy, checkpoints)
    if enforce_physical and found["human_touch"]:
        raise AttestError(Reason.AUTOMATION_DETECTED, "; ".join(found["human_touch"]))
    if enforce_physical and found["phone_down"]:
        raise AttestError(Reason.POLICY_NOT_SATISFIED, "; ".join(found["phone_down"]))
    off = {"human_touch": not policy.min_touch_motion, "phone_down": not policy.min_still_pct}
    return {k: "NOT_REQUIRED" if off[k] else "FLAGGED" if v else "PASSED" for k, v in found.items()}
