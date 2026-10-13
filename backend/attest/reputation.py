"""Deterministic, explainable participation state. No hidden scores.

reputation = sum of assurance levels of verified attestations (P1=1, P2=2, ...).
The on-chain PresenceProfile applies the same rule, so both can be cross-checked.
"""

from dataclasses import dataclass

SECONDS_PER_DAY = 86_400

# League is derived from XP earned through verified actions only.
LEAGUES = [("ELITE", 5_000), ("GOLD", 1_500), ("SILVER", 500), ("BRONZE", 0)]


def utc_day(ts: float) -> int:
    return int(ts // SECONDS_PER_DAY)


def league_for(xp: int) -> str:
    return next(name for name, floor in LEAGUES if xp >= floor)


def next_streak(current: int, last_day: int | None, day: int, freezes: int = 0) -> tuple[int, int]:
    """(new streak, freezes used). A streak freeze covers one missed day."""
    if last_day == day:
        return current, 0
    missed = day - last_day - 1 if last_day is not None else 0
    if last_day is not None and missed <= freezes:
        return current + 1, missed
    return 1, 0


def live_streak(current: int, last_day: int | None, today: int, freezes: int = 0) -> int:
    """Streak as displayed today: it survives until the end of the day after the last proof, plus one
    more day per streak freeze held."""
    return current if last_day is not None and today - last_day <= 1 + freezes else 0


@dataclass
class Profile:
    wallet: str
    xp: int = 0
    verified_count: int = 0
    rejected_count: int = 0
    reputation: int = 0
    current_streak: int = 0
    best_streak: int = 0
    last_day: int | None = None
    freezes: int = 0

    def apply_verified(self, day: int, assurance_level: int, xp: int) -> None:
        self.current_streak, used = next_streak(self.current_streak, self.last_day, day, self.freezes)
        self.freezes -= used
        self.best_streak = max(self.best_streak, self.current_streak)
        self.last_day = day
        self.verified_count += 1
        self.reputation += assurance_level
        self.xp += xp

    def view(self, today: int, rank: int | None) -> dict:
        return {
            "wallet": self.wallet,
            "xp": self.xp,
            "league": league_for(self.xp),
            "rank": rank,
            "streak": live_streak(self.current_streak, self.last_day, today, self.freezes),
            "streak_freezes": self.freezes,
            "best_streak": self.best_streak,
            "verified_count": self.verified_count,
            "rejected_count": self.rejected_count,
            "reputation": self.reputation,
            "proved_today": self.last_day == today,
        }

