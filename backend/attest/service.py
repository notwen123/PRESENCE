"""ATTEST engine: the only component that decides PASS/FAIL, owns nonces, claims and rewards.

State machine (invalid transitions fail):
  CREATED --authorize--> ACTIVE --evidence--> SUBMITTED --verify--> VERIFIED --settle--> SETTLED
                                                                 \\-> REJECTED
"""

import base64
import json
import logging
import os
import secrets
import sqlite3
import threading
import time
import uuid
from collections.abc import Callable

from . import evidence, identity
from .errors import AttestError, Reason
from .evidence import CheckpointData
from .policy import MissionPolicy, ObservedCheckpoint, evaluate
from .reputation import Profile, league_for, utc_day
from .settlement import AttestationRecord, Attestor

log = logging.getLogger("attest")

EXPLORER = {"devnet": "?cluster=devnet", "mainnet": "",
            "localnet": "?cluster=custom&customUrl=http%3A%2F%2F127.0.0.1%3A8899"}


def event(stage: str, session_id: str | None, **fields) -> None:
    """Structured log line. Never pass keys, signatures, tokens or raw evidence here."""
    log.info(json.dumps({"stage": stage, "session_id": session_id, "ts": time.time(), **fields}))


class Attest:
    def __init__(self, db: sqlite3.Connection, missions: dict[str, MissionPolicy],
                 sgt: identity.SgtVerifier, attestor: Attestor, *, domain: str, chain_id: str,
                 dev_mode: bool, network: str, ttl_seconds: int = 300,
                 enforce_physical: bool | None = None, clock: Callable[[], float] = time.time, monad=None):
        self.db, self.missions, self.sgt, self.attestor = db, missions, sgt, attestor
        self.domain, self.chain_id, self.dev_mode, self.network = domain, chain_id, dev_mode, network
        self.ttl, self.clock = ttl_seconds, clock
        self.monad = monad  # optional MonadAnchor: also record each verified session in PresenceRegistry on Monad
        # Max sponsor reward + bonus one device (SGT, or wallet in dev mode) can earn per UTC day, in base units.
        # Farming a device only pays if cap x days > device cost, so a low cap makes farms uneconomic.
        self.daily_reward_cap = int(os.environ.get("DAILY_REWARD_CAP_PER_DEVICE", "3000000"))  # 3 tokens at 6 decimals
        # Emulators have no real motion, so development only reports physical problems unless told otherwise.
        self.enforce_physical = (not dev_mode) if enforce_physical is None else enforce_physical
        # ponytail: one lock serializes every state transition; per-session locks if throughput matters.
        self.lock = threading.Lock()
        self.settling: set[str] = set()

    # ---------- helpers ----------

    def _session(self, session_id: str) -> sqlite3.Row:
        row = self.db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        if row is None:
            raise AttestError(Reason.SESSION_NOT_FOUND)
        return row

    def _live(self, row: sqlite3.Row) -> None:
        if self.clock() > row["expires_at"]:
            raise AttestError(Reason.SESSION_EXPIRED)

    @staticmethod
    def _expect(row: sqlite3.Row, state: str) -> None:
        if row["state"] != state:
            # A session (and its nonce) is single-use: any attempt to run a step again is a replay.
            done = {"CREATED": 0, "ACTIVE": 1, "SUBMITTED": 2, "VERIFIED": 3, "SETTLED": 4, "REJECTED": 4}
            reason = Reason.NONCE_REPLAY if done[row["state"]] > done[state] else Reason.INVALID_STATE
            raise AttestError(reason, f"session is {row['state']}, expected {state}")

    def _mission(self, mission_id: str) -> MissionPolicy:
        if mission_id not in self.missions:
            raise AttestError(Reason.UNKNOWN_MISSION, mission_id)
        return self.missions[mission_id]

    def _profile(self, wallet: str) -> Profile:
        row = self.db.execute("SELECT * FROM profiles WHERE wallet=?", (wallet,)).fetchone()
        return Profile(**dict(row)) if row else Profile(wallet=wallet)

    def _save_profile(self, p: Profile) -> None:
        self.db.execute(
            "INSERT INTO profiles (wallet, xp, verified_count, rejected_count, reputation, current_streak, "
            "best_streak, last_day, freezes) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(wallet) DO UPDATE SET "
            "xp=excluded.xp, verified_count=excluded.verified_count, rejected_count=excluded.rejected_count, "
            "reputation=excluded.reputation, current_streak=excluded.current_streak, "
            "best_streak=excluded.best_streak, last_day=excluded.last_day, freezes=excluded.freezes",
            (p.wallet, p.xp, p.verified_count, p.rejected_count, p.reputation,
             p.current_streak, p.best_streak, p.last_day, p.freezes))

    def _rank(self, xp: int) -> int:
        return 1 + self.db.execute("SELECT COUNT(*) FROM profiles WHERE xp > ?", (xp,)).fetchone()[0]

    @staticmethod
    def _identity_key(row: sqlite3.Row) -> str:
        return row["sgt_mint"] or f"dev:{row['wallet']}"

    def _rewarded_today(self, identity_key: str, day: int) -> int:
        return self.db.execute("SELECT COALESCE(SUM(reward_base),0) FROM claims WHERE identity_key=? AND day=?",
                               (identity_key, day)).fetchone()[0]

    def _claims_today(self, identity_key: str, mission_id: str, day: int) -> int:
        return self.db.execute(
            "SELECT COUNT(*) FROM claims WHERE identity_key=? AND mission_id=? AND day=?",
            (identity_key, mission_id, day)).fetchone()[0]

    COOLDOWN_DAYS = (7, 14, 30)  # first quit, second within 30 days, third and later

    def _lock_days(self, wallet: str) -> int:
        recent = self.db.execute("SELECT COUNT(*) FROM stakes WHERE wallet=? AND locked_at > ?",
                                 (wallet, self.clock() - 30 * 86_400)).fetchone()[0]
        return self.COOLDOWN_DAYS[min(recent, len(self.COOLDOWN_DAYS) - 1)]

    def _lock(self, session_id: str, wallet: str) -> float:
        """Cool-down: the stake stays in the pool, owned by nobody else, until unlock_at; then it's returned."""
        unlock_at = self.clock() + self._lock_days(wallet) * 86_400
        self.db.execute("UPDATE stakes SET status='LOCKED', locked_at=?, unlock_at=? WHERE session_id=? AND status='HELD'",
                        (self.clock(), unlock_at, session_id))
        return unlock_at

    def _reject(self, row: sqlite3.Row, err: AttestError) -> None:
        p = self._profile(row["wallet"])
        p.rejected_count += 1
        held = self._held_stake(row["id"]) / 10 ** self._decimals()
        m = self._mission(row["mission_id"])
        with self.db:
            self._save_profile(p)
            unlock_at = self._lock(row["id"], row["wallet"]) if m.cooldown and held else None
            if not (m.no_loss or m.cooldown):
                self.db.execute("UPDATE stakes SET status='FORFEITED' WHERE session_id=? AND status='HELD'", (row["id"],))
            self.db.execute("UPDATE sessions SET state='REJECTED', receipt=? WHERE id=?",
                            (json.dumps({"status": "REJECTED", "reason": err.reason, "detail": err.detail,
                                         "stake_forfeited": 0 if (m.no_loss or m.cooldown) else held,
                                         "stake_returned": held if m.no_loss else 0,
                                         "stake_locked": held if unlock_at else 0, "unlock_at": unlock_at}),
                             row["id"]))
        event("rejected", row["id"], mission_id=row["mission_id"], reason=err.reason)
        if m.no_loss:
            self._release_stakes()

    def _release_stakes(self) -> None:
        """Return stakes that are due: No-Loss stakes of failed or abandoned sessions, and cool-down stakes
        whose lock has run out. Abandoned cool-down sessions are locked first. A refund that fails on the
        network is retried on the next call."""
        now = self.clock()
        abandoned = "s.expires_at < ? AND s.state IN ('CREATED','ACTIVE','SUBMITTED')"
        for r in self.db.execute(f"SELECT k.session_id, k.wallet, k.mission_id FROM stakes k JOIN sessions s ON s.id=k.session_id "
                                 f"WHERE k.status='HELD' AND {abandoned}", (now,)).fetchall():
            m = self.missions.get(r["mission_id"])
            if m and m.cooldown:
                with self.db:
                    self._lock(r["session_id"], r["wallet"])
        rows = self.db.execute(
            "SELECT k.session_id, k.wallet, k.amount_base, k.mission_id, k.status FROM stakes k JOIN sessions s ON s.id=k.session_id "
            f"WHERE (k.status='HELD' AND (s.state='REJECTED' OR ({abandoned}))) OR (k.status='LOCKED' AND k.unlock_at <= ?)",
            (now, now)).fetchall()
        for r in rows:
            m = self.missions.get(r["mission_id"])
            if r["status"] == "HELD" and not (m and m.no_loss):
                continue
            try:
                sig = self.attestor.refund(r["wallet"], r["amount_base"])
            except Exception as e:  # network / RPC: retried next time
                log.warning("stake refund for %s failed: %s", r["session_id"], e)
                continue
            with self.db:
                self.db.execute("UPDATE stakes SET status='RETURNED', signature=COALESCE(signature, ?) "
                                "WHERE session_id=? AND status=?", (sig, r["session_id"], r["status"]))
            event("refunded", r["session_id"], mission_id=r["mission_id"], signature=sig)

    # ---------- protocol steps ----------

    def create_session(self, wallet: str, mission_id: str, ephemeral_pubkey: str) -> dict:
        self._release_stakes()  # abandoned No-Loss sessions, cool-downs that have run out
        policy = self._mission(mission_id)
        identity.decode_wallet(wallet)
        identity.load_ephemeral_key(ephemeral_pubkey)
        now = self.clock()
        ttl = policy.duration_seconds + self.ttl  # the whole process must fit, plus slack to start it
        session_id, nonce = str(uuid.uuid4()), secrets.token_hex(16)
        message = identity.siws_message(
            domain=self.domain, wallet=wallet, chain_id=self.chain_id, nonce=nonce,
            session_id=session_id, mission_name=policy.name, ephemeral_pubkey_b64=ephemeral_pubkey,
            issued_at=now, expires_at=now + ttl)
        stake_base, stake_tx = self._stake_base(policy), None
        if stake_base:
            try:
                stake_tx = self.attestor.build_stake_tx(wallet, stake_base)
            except Exception as e:  # network / RPC
                raise AttestError(Reason.NETWORK_ERROR, f"could not build stake transaction: {e}") from e
        with self.lock, self.db:
            self.db.execute(
                "INSERT INTO sessions (id, nonce, mission_id, wallet, ephemeral_pubkey, siws_message, state, "
                "created_at, expires_at, chain_head) VALUES (?,?,?,?,?,?,'CREATED',?,?,?)",
                (session_id, nonce, mission_id, wallet, ephemeral_pubkey, message, now, now + ttl,
                 evidence.genesis(session_id, nonce)))
            if stake_tx:
                self.db.execute("INSERT INTO stakes (session_id, wallet, mission_id, amount_base, unsigned_tx, signature, status) "
                                "VALUES (?,?,?,?,?,NULL,'ISSUED')",
                                (session_id, wallet, mission_id, stake_base, stake_tx))
        event("session_created", session_id, mission_id=mission_id, staked=bool(stake_tx))
        stake = ({"amount": stake_base / 10 ** self._decimals(), "symbol": self.symbol(policy),
                  "transaction": base64.b64encode(stake_tx).decode()} if stake_tx else None)
        return {"session_id": session_id, "nonce": nonce, "siws_message": message,
                "expires_at": now + ttl, "policy": policy.public(), "stake": stake,
                "mode": "development" if self.dev_mode else "production"}

    def authorize(self, session_id: str, wallet_signature_b58: str, signed_stake_tx_b64: str | None = None) -> dict:
        with self.lock:
            row = self._session(session_id)
            self._expect(row, "CREATED")
            self._live(row)
            identity.verify_wallet_signature(row["wallet"], row["siws_message"], wallet_signature_b58)
            stake = self.db.execute("SELECT * FROM stakes WHERE session_id=?", (session_id,)).fetchone()
            if stake is not None and not signed_stake_tx_b64:
                raise AttestError(Reason.STAKE_REQUIRED, "this mission requires a signed stake transaction")
        # Network call outside the lock; state is re-checked before the transition below.
        elig = self.sgt.check(row["wallet"])
        if not elig.eligible:
            raise AttestError(Reason.SGT_NOT_ELIGIBLE, "wallet holds no Seeker Genesis Token")
        identity_key = elig.sgt_mint or f"dev:{row['wallet']}"
        policy = self._mission(row["mission_id"])
        with self.lock:
            if self._claims_today(identity_key, policy.mission_id, utc_day(self.clock())) \
                    >= policy.max_claims_per_identity_per_day:
                raise AttestError(Reason.ALREADY_CLAIMED, "daily claim limit reached for this device")
        stake_sig = None
        if stake is not None:  # network call outside the lock; the message must be exactly what we issued
            try:
                stake_sig = self.attestor.submit_stake(base64.b64decode(signed_stake_tx_b64), stake["unsigned_tx"])
            except Exception as e:
                raise AttestError(Reason.STAKE_FAILED, str(e)[:200]) from e
        with self.lock:
            # ponytail: a claim racing in between the checks would leave this stake HELD on a CREATED
            # session, which forfeits on expiry; refund on that race if it ever matters.
            self._expect(self._session(session_id), "CREATED")
            now = self.clock()
            with self.db:
                self.db.execute("UPDATE sessions SET state='ACTIVE', started_at=?, sgt_mint=?, "
                                "eligibility_mode=? WHERE id=?", (now, elig.sgt_mint, elig.mode, session_id))
                if stake_sig:
                    self.db.execute("UPDATE stakes SET status='HELD', signature=? WHERE session_id=?",
                                    (stake_sig, session_id))
        event("authorized", session_id, mission_id=row["mission_id"], eligibility=elig.mode)
        return {"state": "ACTIVE", "started_at": now, "eligibility": elig.mode, "sgt_mint": elig.sgt_mint,
                "stake_signature": stake_sig}

    def add_checkpoint(self, session_id: str, cp: CheckpointData, hash_hex: str, signature_b64: str) -> dict:
        with self.lock:
            row = self._session(session_id)
            self._expect(row, "ACTIVE")
            self._live(row)
            existing = self.db.execute("SELECT hash FROM checkpoints WHERE session_id=? AND idx=?",
                                       (session_id, cp.index)).fetchone()
            if existing is not None and existing["hash"].hex() == hash_hex:
                return {"accepted": True, "index": cp.index, "duplicate": True}  # idempotent retry
            count = self.db.execute("SELECT COUNT(*) FROM checkpoints WHERE session_id=?",
                                    (session_id,)).fetchone()[0]
            try:
                if cp.index != count:
                    raise AttestError(Reason.EVIDENCE_CHAIN_INVALID, f"expected checkpoint {count}, got {cp.index}")
                expected = evidence.checkpoint_hash(row["chain_head"], cp, row["nonce"])
                if expected.hex() != hash_hex:
                    raise AttestError(Reason.EVIDENCE_CHAIN_INVALID, f"checkpoint {cp.index} hash mismatch")
                identity.verify_ephemeral_signature(row["ephemeral_pubkey"], expected, signature_b64)
            except AttestError as e:
                self._reject(row, e)
                raise
            with self.db:
                self.db.execute("INSERT INTO checkpoints (session_id, idx, timestamp_ms, elapsed_ms, foreground, "
                                "interactions, response_ms, still_pct, touch_motion, hash, received_at) "
                                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                                (session_id, cp.index, cp.timestamp_ms, cp.elapsed_ms, int(cp.foreground),
                                 cp.interactions, cp.response_ms, cp.still_pct, cp.touch_motion, expected,
                                 self.clock()))
                self.db.execute("UPDATE sessions SET chain_head=? WHERE id=?", (expected, session_id))
        event("checkpoint", session_id, index=cp.index)
        return {"accepted": True, "index": cp.index}

    def submit_evidence(self, session_id: str, root_hex: str, signature_b64: str) -> dict:
        with self.lock:
            row = self._session(session_id)
            self._expect(row, "ACTIVE")
            self._live(row)
            root = evidence.evidence_root(row["chain_head"])
            try:
                if root.hex() != root_hex:
                    raise AttestError(Reason.EVIDENCE_CHAIN_INVALID, "evidence root does not match checkpoints")
                identity.verify_ephemeral_signature(row["ephemeral_pubkey"], root, signature_b64)
            except AttestError as e:
                self._reject(row, e)
                raise
            with self.db:
                self.db.execute("UPDATE sessions SET state='SUBMITTED', submitted_at=?, evidence_root=? "
                                "WHERE id=?", (self.clock(), root, session_id))
        event("submitted", session_id)
        return {"state": "SUBMITTED", "evidence_root": root_hex}

    def verify(self, session_id: str) -> dict:
        with self.lock:
            row = self._session(session_id)
            self._expect(row, "SUBMITTED")
            policy = self._mission(row["mission_id"])
            cps = [ObservedCheckpoint(r["idx"], r["received_at"], bool(r["foreground"]), r["interactions"],
                                      r["response_ms"], r["still_pct"], r["touch_motion"])
                   for r in self.db.execute("SELECT * FROM checkpoints WHERE session_id=? ORDER BY idx",
                                            (session_id,))]
            # P1 = wallet signature + device eligibility (authorize); P2 = + continuous process evidence
            # whose root matched (submit). P3/P4 need witnesses/hardware attestation: not implemented.
            achieved = 2
            day = utc_day(self.clock())
            seq = self._claims_today(self._identity_key(row), policy.mission_id, day)
            try:
                physical = evaluate(policy, row["started_at"], row["submitted_at"], cps, achieved,
                                    enforce_physical=self.enforce_physical)
                if seq >= policy.max_claims_per_identity_per_day:
                    raise AttestError(Reason.ALREADY_CLAIMED, "daily claim limit reached for this device")
            except AttestError as e:
                self._reject(row, e)
                return {"verified": False, "assurance": policy.required_assurance,
                        "mission_id": policy.mission_id, "reason": e.reason, "detail": e.detail}

            profile = self._profile(row["wallet"])
            league_before, rank_before = league_for(profile.xp), self._rank(profile.xp)
            # Reserve the token reward from the sponsor's pool; unfunded missions still attest, unpaid.
            reward_base = self._reward_base(policy)
            pool = self._pool_remaining(policy.mission_id)
            funded = reward_base > 0 and pool >= reward_base
            reserved = reward_base if funded else 0
            # Finishers earn a bonus on their stake, paid from the pool that quitters' stakes feed.
            stake_base = self._held_stake(session_id)
            bonus = min(round(stake_base * policy.bonus_rate), max(pool - reserved, 0))
            cap_left = max(self.daily_reward_cap - self._rewarded_today(self._identity_key(row), day), 0)
            capped = reserved + bonus > cap_left
            reserved = min(reserved, cap_left)
            bonus = min(bonus, cap_left - reserved)
            profile.apply_verified(day, achieved, policy.reward_xp)
            receipt = {
                "status": "VERIFIED",
                "mode": "development" if self.dev_mode else "production",
                "session_id": session_id,
                "mission_id": policy.mission_id,
                "mission_name": policy.name,
                "assurance": f"P{achieved}",
                "duration_seconds": round(row["submitted_at"] - row["started_at"], 1),
                "checkpoint_count": len(cps),
                "evidence_root": row["evidence_root"].hex(),
                "checks": {
                    "wallet_signature": "PASSED",
                    "seeker_eligibility": "PASSED" if row["eligibility_mode"] == "SGT_VERIFIED" else "DEV_BYPASS",
                    "session_nonce": "PASSED",
                    "evidence_chain": "PASSED",
                    "mission_policy": "PASSED",
                    "replay": "PASSED",
                    **physical,
                },
                "physical": {"still_pct": [cp.still_pct for cp in cps],
                             "touch_motion": [cp.touch_motion for cp in cps]},
                "reward": {"xp": policy.reward_xp, "token": reserved / 10 ** self._decimals(),
                           "symbol": self.symbol(policy),
                           "funding": "RESERVED" if funded else "UNFUNDED" if reward_base else "NONE",
                           "stake": stake_base / 10 ** self._decimals(), "bonus": bonus / 10 ** self._decimals(),
                           "daily_cap_reached": capped},
                "league_before": league_before,
                "rank_before": rank_before,
                "day": day,
                "verified_at": self.clock(),
                "settlement": {"status": "PENDING"},
            }
            with self.db:
                self.db.execute("INSERT INTO claims VALUES (?,?,?,?,?,?)",
                                (session_id, self._identity_key(row), policy.mission_id, day, seq, reserved + bonus))
                self._save_profile(profile)
                receipt.update(league_after=league_for(profile.xp), rank_after=self._rank(profile.xp),
                               streak=profile.current_streak, total_xp=profile.xp)
                self.db.execute("UPDATE sessions SET state='VERIFIED', receipt=? WHERE id=?",
                                (json.dumps(receipt), session_id))
        event("verified", session_id, mission_id=policy.mission_id, assurance=f"P{achieved}")
        receipt["settlement"] = self.settle(session_id)
        if self.monad:
            receipt["monad"] = json.loads(self._session(session_id)["receipt"]).get("monad")
        return {"verified": True, "assurance": f"P{achieved}", "mission_id": policy.mission_id,
                "evidence_root": receipt["evidence_root"], "reason": None, "receipt": receipt}

    def settle(self, session_id: str) -> dict:
        """Anchor a VERIFIED session on-chain. Safe to retry; the chain also rejects a 2nd write per day."""
        with self.lock:
            row = self._session(session_id)
            self._expect(row, "VERIFIED")
            if session_id in self.settling:
                raise AttestError(Reason.INVALID_STATE, "settlement already in progress")
            self.settling.add(session_id)
            receipt = json.loads(row["receipt"])
            claim = self.db.execute("SELECT seq, reward_base FROM claims WHERE session_id=?", (session_id,)).fetchone()
        try:
            result = self.attestor.settle(AttestationRecord(
                wallet=row["wallet"], day=receipt["day"], mission_id=row["mission_id"],
                evidence_root=row["evidence_root"], assurance_level=int(receipt["assurance"][1:]),
                sgt_mint=row["sgt_mint"], seq=claim["seq"],
                reward_base=claim["reward_base"] + self._held_stake(session_id)))
        finally:
            with self.lock:
                self.settling.discard(session_id)
        settlement = {"status": result.status, "signature": result.signature,
                      "network": result.network, "detail": result.detail,
                      "reward_token": result.reward_base / 10 ** self._decimals(),
                      "reward_symbol": self.symbol(self._mission(row["mission_id"])),
                      "reward_mint": result.reward_mint}
        if result.signature and result.network in EXPLORER:
            settlement["explorer_url"] = f"https://explorer.solana.com/tx/{result.signature}{EXPLORER[result.network]}"
        receipt["settlement"] = settlement
        if self.monad and receipt.get("monad", {}).get("status") != "CONFIRMED":
            try:
                receipt["monad"] = self.monad.anchor(self._identity_key(row), row["mission_id"], receipt["day"],
                                                     claim["seq"], bytes(row["evidence_root"]), int(receipt["assurance"][1:]))
            except Exception as e:  # Monad is an extra anchor: never let it undo a Solana settlement
                receipt["monad"] = {"status": "FAILED", "chain": "monad-testnet", "detail": str(e)[:200]}
        state = "SETTLED" if result.status == "CONFIRMED" else "VERIFIED"
        with self.lock, self.db:
            self.db.execute("UPDATE sessions SET state=?, receipt=? WHERE id=?",
                            (state, json.dumps(receipt), session_id))
            if state == "SETTLED":
                self.db.execute("UPDATE stakes SET status='RETURNED' WHERE session_id=? AND status='HELD'",
                                (session_id,))
        event("settlement", session_id, status=result.status)
        return settlement

    # ---------- sponsor pools ----------

    def symbol(self, policy: MissionPolicy) -> str:
        """Off mainnet the reward mint is a test token; never let a receipt call it SKR."""
        return policy.reward_symbol if self.network == "mainnet" else f"{policy.reward_symbol}-TEST"

    def _decimals(self) -> int:
        return getattr(self.attestor, "reward_decimals", 0) or 0

    def _reward_base(self, policy: MissionPolicy) -> int:
        if not getattr(self.attestor, "reward_mint", None):
            return 0
        return round(policy.reward_token * 10 ** self._decimals())

    def _stake_base(self, policy: MissionPolicy) -> int:
        if not getattr(self.attestor, "reward_mint", None):
            return 0
        return round(policy.stake_token * 10 ** self._decimals())

    def _held_stake(self, session_id: str) -> int:
        row = self.db.execute("SELECT amount_base FROM stakes WHERE session_id=? AND status='HELD'",
                              (session_id,)).fetchone()
        return row[0] if row else 0

    def _forfeited(self, mission_id: str) -> int:
        """Stakes lost to the pool: rejected sessions, plus held stakes whose session expired unfinished."""
        m = self.missions[mission_id]
        if m.no_loss or m.cooldown:
            return 0
        return self.db.execute(
            "SELECT COALESCE(SUM(k.amount_base),0) FROM stakes k JOIN sessions s ON s.id = k.session_id "
            "WHERE k.mission_id=? AND (k.status='FORFEITED' OR (k.status='HELD' AND s.expires_at < ? "
            "AND s.state IN ('CREATED','ACTIVE','SUBMITTED')))", (mission_id, self.clock())).fetchone()[0]

    def _pool_remaining(self, mission_id: str) -> int:
        deposited = self.db.execute("SELECT COALESCE(SUM(amount_base),0) FROM sponsor_deposits WHERE mission_id=?",
                                    (mission_id,)).fetchone()[0]
        reserved = self.db.execute("SELECT COALESCE(SUM(reward_base),0) FROM claims WHERE mission_id=?",
                                   (mission_id,)).fetchone()[0]
        return deposited + self._forfeited(mission_id) - reserved

    def sponsor_deposit(self, mission_id: str, signature: str) -> dict:
        """Credit a mission's pool with a confirmed on-chain transfer into the attestor's pool account."""
        self._mission(mission_id)
        try:
            dep = self.attestor.verify_deposit(signature)
        except RuntimeError as e:
            raise AttestError(Reason.INVALID_STATE, str(e)) from e
        try:
            with self.lock, self.db:
                self.db.execute("INSERT INTO sponsor_deposits VALUES (?,?,?,?,?)",
                                (signature, mission_id, dep.amount_base, dep.depositor, self.clock()))
        except sqlite3.IntegrityError as e:
            raise AttestError(Reason.NONCE_REPLAY, "deposit transaction already credited") from e
        event("sponsor_deposit", None, mission_id=mission_id, amount_base=dep.amount_base)
        return {"mission_id": mission_id, "credited": dep.amount_base / 10 ** self._decimals(),
                "depositor": dep.depositor, **self.pools()[mission_id]}

    def pools(self) -> dict:
        d = 10 ** self._decimals() if self._decimals() else 1
        out = {}
        for m in self.missions.values():
            deposited = self.db.execute("SELECT COALESCE(SUM(amount_base),0) FROM sponsor_deposits WHERE mission_id=?",
                                        (m.mission_id,)).fetchone()[0]
            remaining = self._pool_remaining(m.mission_id)
            out[m.mission_id] = {"symbol": self.symbol(m), "sponsor": m.sponsor,
                                 "deposited": deposited / d, "forfeited": self._forfeited(m.mission_id) / d,
                                 "remaining": remaining / d,
                                 "reward_per_claim": m.reward_token,
                                 "claims_funded": int(remaining // max(self._reward_base(m), 1)) if self._reward_base(m) else 0}
        return out

    def faucet(self, wallet: str) -> dict:
        """DEVELOPMENT ONLY: one grant of fee SOL + test tokens per wallet; never on mainnet."""
        if self.network == "mainnet":
            raise AttestError(Reason.INVALID_STATE, "no faucet on mainnet")
        identity.decode_wallet(wallet)
        with self.lock:
            if self.db.execute("SELECT 1 FROM faucet_grants WHERE wallet=?", (wallet,)).fetchone():
                return {"granted": False, "detail": "already funded"}
        try:
            sig = self.attestor.faucet(wallet, 10_000_000, 50 * 10 ** self._decimals())  # 0.01 SOL, 50 test tokens
        except Exception as e:
            raise AttestError(Reason.NETWORK_ERROR, str(e)[:200]) from e
        with self.lock, self.db:
            self.db.execute("INSERT OR IGNORE INTO faucet_grants VALUES (?,?,?)", (wallet, sig, self.clock()))
        event("faucet", None)
        return {"granted": True, "signature": sig, "sol": 0.01, "tokens": 50}

    # ---------- reads ----------

    def receipt(self, session_id: str) -> dict:
        row = self._session(session_id)
        if row["receipt"] is None:
            raise AttestError(Reason.INVALID_STATE, f"no receipt while session is {row['state']}")
        return {"state": row["state"], **json.loads(row["receipt"])}

    FREEZE_PRICE = 2.0   # SKR per streak freeze
    FREEZE_BURN = 0.5    # share of the price burned; the rest is protocol revenue (not credited to any pool)

    def freeze_order(self, wallet: str) -> dict:
        """Unsigned SKR transfer (user -> pool) for one streak freeze; the wallet signs it."""
        identity.decode_wallet(wallet)
        amount = round(self.FREEZE_PRICE * 10 ** self._decimals())
        try:
            tx = self.attestor.build_stake_tx(wallet, amount)
        except Exception as e:
            raise AttestError(Reason.NETWORK_ERROR, f"could not build the freeze transaction: {e}") from e
        order_id = str(uuid.uuid4())
        with self.db:
            self.db.execute("INSERT INTO freeze_orders (id, wallet, amount_base, unsigned_tx, status, created_at) "
                            "VALUES (?,?,?,?,'ISSUED',?)", (order_id, wallet, amount, tx, self.clock()))
        return {"order_id": order_id, "transaction": base64.b64encode(tx).decode(), "price": self.FREEZE_PRICE,
                "burn": self.FREEZE_PRICE * self.FREEZE_BURN, "symbol": self.symbol(next(iter(self.missions.values())))}

    def freeze_pay(self, order_id: str, signed_tx_b64: str) -> dict:
        """Submit the signed payment, burn half of it from the pool, credit one freeze."""
        with self.lock:
            row = self.db.execute("SELECT * FROM freeze_orders WHERE id=?", (order_id,)).fetchone()
            if row is None:
                raise AttestError(Reason.SESSION_NOT_FOUND, "no such freeze order")
            if row["status"] != "ISSUED":
                raise AttestError(Reason.INVALID_STATE, "freeze order already paid")
            try:
                pay_sig = self.attestor.submit_stake(base64.b64decode(signed_tx_b64), row["unsigned_tx"])
            except Exception as e:
                raise AttestError(Reason.STAKE_FAILED, str(e)) from e
            burn = round(row["amount_base"] * self.FREEZE_BURN)
            try:
                burn_sig = self.attestor.burn(burn)
            except Exception as e:  # paid but not burned: record it, the burn can be redone from the pool
                log.warning("freeze burn failed for %s: %s", order_id, e)
                burn_sig = None
            p = self._profile(row["wallet"])
            # Repair window: a freeze can rescue a streak whose last proof was yesterday or the day before (one missed
            # day, repaired within 24 h). Past that the streak is gone, and the freeze only covers future misses.
            if p.last_day is not None and utc_day(self.clock()) - p.last_day > 2 + p.freezes:
                p.current_streak = 0
            p.freezes += 1
            with self.db:
                self._save_profile(p)
                self.db.execute("UPDATE freeze_orders SET status='PAID', payment_signature=?, burn_signature=? WHERE id=?",
                                (pay_sig, burn_sig, order_id))
        event("freeze_bought", None, burned_base=burn if burn_sig else 0)
        return {"freezes": p.freezes, "payment_signature": pay_sig, "burn_signature": burn_sig,
                "burned": burn / 10 ** self._decimals() if burn_sig else 0}

    SQUAD_MAX = 8

    def squad_create(self, wallet: str, name: str) -> dict:
        identity.decode_wallet(wallet)
        if self.db.execute("SELECT 1 FROM squad_members WHERE wallet=?", (wallet,)).fetchone():
            raise AttestError(Reason.INVALID_STATE, "already in a squad")
        squad_id, code = str(uuid.uuid4()), secrets.token_hex(3).upper()  # 6-character invite code
        with self.db:
            self.db.execute("INSERT INTO squads VALUES (?,?,?,?)", (squad_id, code, name.strip()[:40] or "Squad", self.clock()))
            self.db.execute("INSERT INTO squad_members VALUES (?,?,?)", (squad_id, wallet, utc_day(self.clock())))
        return self.squad(wallet)

    def squad_join(self, wallet: str, code: str) -> dict:
        identity.decode_wallet(wallet)
        row = self.db.execute("SELECT id FROM squads WHERE code=?", (code.strip().upper(),)).fetchone()
        if row is None:
            raise AttestError(Reason.SESSION_NOT_FOUND, "no squad with that code")
        if self.db.execute("SELECT 1 FROM squad_members WHERE wallet=?", (wallet,)).fetchone():
            raise AttestError(Reason.INVALID_STATE, "already in a squad")
        if self.db.execute("SELECT COUNT(*) FROM squad_members WHERE squad_id=?", (row["id"],)).fetchone()[0] >= self.SQUAD_MAX:
            raise AttestError(Reason.INVALID_STATE, f"squad is full ({self.SQUAD_MAX})")
        with self.db:
            self.db.execute("INSERT INTO squad_members VALUES (?,?,?)", (row["id"], wallet, utc_day(self.clock())))
        return self.squad(wallet)

    def squad(self, wallet: str) -> dict | None:
        """The wallet's squad: members, who is facedown now, who has a verified session today, and the
        squad streak (consecutive days on which every member who had joined verified at least once)."""
        row = self.db.execute("SELECT s.* FROM squads s JOIN squad_members m ON m.squad_id=s.id WHERE m.wallet=?",
                              (wallet,)).fetchone()
        if row is None:
            return None
        members = self.db.execute("SELECT wallet, joined_day FROM squad_members WHERE squad_id=? ORDER BY joined_day",
                                  (row["id"],)).fetchall()
        today, now = utc_day(self.clock()), self.clock()
        days = {}
        for m in members:
            days[m["wallet"]] = {r[0] for r in self.db.execute(
                "SELECT c.day FROM claims c JOIN sessions s ON s.id=c.session_id WHERE s.wallet=?", (m["wallet"],))}
        def everyone(day: int) -> bool:
            present = [m for m in members if m["joined_day"] <= day]
            return bool(present) and all(day in days[m["wallet"]] for m in present)
        d = today if everyone(today) else today - 1
        streak = 0
        while d >= min(m["joined_day"] for m in members) and everyone(d):
            streak, d = streak + 1, d - 1
        return {
            "name": row["name"], "code": row["code"], "streak": streak,
            "done_today": sum(today in days[m["wallet"]] for m in members), "size": len(members),
            "members": [{
                "wallet": m["wallet"], "you": m["wallet"] == wallet, "done_today": today in days[m["wallet"]],
                "facedown_now": self.db.execute("SELECT 1 FROM sessions WHERE wallet=? AND state='ACTIVE' AND expires_at > ?",
                                                (m["wallet"], now)).fetchone() is not None,
            } for m in members],
        }

    def live(self) -> dict:
        """How many sessions are running right now (authorized, not yet submitted, not expired)."""
        n = self.db.execute("SELECT COUNT(*) FROM sessions WHERE state='ACTIVE' AND expires_at > ?",
                            (self.clock(),)).fetchone()[0]
        return {"facedown_now": n}

    def history(self, wallet: str, limit: int = 30) -> list[dict]:
        """Finished sessions (verified or rejected), newest first, from the stored receipts only."""
        identity.decode_wallet(wallet)
        rows = self.db.execute(
            "SELECT id, mission_id, state, started_at, receipt FROM sessions WHERE wallet=? AND receipt IS NOT NULL "
            "ORDER BY started_at DESC LIMIT ?", (wallet, limit)).fetchall()
        out = []
        for r in rows:
            rc = json.loads(r["receipt"])
            m = self.missions.get(r["mission_id"])
            settlement = rc.get("settlement") or {}
            out.append({
                "session_id": r["id"], "mission_id": r["mission_id"], "mission_name": m.name if m else r["mission_id"],
                "state": r["state"], "started_at": r["started_at"],
                "duration_seconds": rc.get("duration_seconds", m.duration_seconds if m else 0),
                "reason": rc.get("reason"), "stake_forfeited": rc.get("stake_forfeited", 0),
                "stake_returned": rc.get("stake_returned", 0),
                "stake_locked": rc.get("stake_locked", 0), "unlock_at": rc.get("unlock_at"),
                "paid": settlement.get("reward_token", 0),
                "symbol": settlement.get("reward_symbol") or (self.symbol(m) if m else None),
                "settlement_status": settlement.get("status"), "explorer_url": settlement.get("explorer_url"),
            })
        return out

    def profile(self, wallet: str) -> dict:
        identity.decode_wallet(wallet)
        p = self._profile(wallet)
        today = utc_day(self.clock())
        claims = dict(self.db.execute(
            "SELECT c.mission_id, COUNT(*) FROM claims c JOIN sessions s ON s.id = c.session_id "
            "WHERE s.wallet=? AND c.day=? GROUP BY c.mission_id", (wallet, today)).fetchall())
        return {**p.view(today, self._rank(p.xp) if p.verified_count else None), "claims_today": claims}
