"""SQLite persistence. Durable so nonce/claim state survives restarts (replay protection)."""

import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  nonce TEXT NOT NULL UNIQUE,
  mission_id TEXT NOT NULL,
  wallet TEXT NOT NULL,
  ephemeral_pubkey TEXT NOT NULL,
  siws_message TEXT NOT NULL,
  state TEXT NOT NULL,
  created_at REAL NOT NULL,
  expires_at REAL NOT NULL,
  started_at REAL,
  submitted_at REAL,
  chain_head BLOB NOT NULL,
  sgt_mint TEXT,
  eligibility_mode TEXT,
  evidence_root BLOB,
  receipt TEXT
);
CREATE TABLE IF NOT EXISTS checkpoints (
  session_id TEXT NOT NULL REFERENCES sessions(id),
  idx INTEGER NOT NULL,
  timestamp_ms INTEGER NOT NULL,
  elapsed_ms INTEGER NOT NULL,
  foreground INTEGER NOT NULL,
  interactions INTEGER NOT NULL,
  response_ms INTEGER NOT NULL,
  still_pct INTEGER NOT NULL DEFAULT -1,
  touch_motion INTEGER NOT NULL DEFAULT -1,
  hash BLOB NOT NULL,
  received_at REAL NOT NULL,
  PRIMARY KEY (session_id, idx)
);
-- One row per verified claim. identity_key = SGT mint (production) or dev:<wallet>.
CREATE TABLE IF NOT EXISTS claims (
  session_id TEXT PRIMARY KEY REFERENCES sessions(id),
  identity_key TEXT NOT NULL,
  mission_id TEXT NOT NULL,
  day INTEGER NOT NULL,
  seq INTEGER NOT NULL,          -- claim index today for this mission; part of the on-chain PDA seeds
  reward_base INTEGER NOT NULL,  -- reward reserved from the sponsor pool, in mint base units
  UNIQUE (identity_key, mission_id, day, seq)
);
CREATE INDEX IF NOT EXISTS claims_by_identity_day ON claims(identity_key, mission_id, day);
-- Sponsor deposits into the attestor's reward pool, verified on-chain. One row per transaction.
CREATE TABLE IF NOT EXISTS sponsor_deposits (
  signature TEXT PRIMARY KEY,
  mission_id TEXT NOT NULL,
  amount_base INTEGER NOT NULL,
  depositor TEXT,
  created_at REAL NOT NULL
);
-- The user's own stake for a session. Held in the pool account from authorize onward.
-- HELD -> RETURNED (verified + settled) | FORFEITED (rejected, or the session expired unfinished).
CREATE TABLE IF NOT EXISTS stakes (
  session_id TEXT PRIMARY KEY REFERENCES sessions(id),
  wallet TEXT NOT NULL,
  mission_id TEXT NOT NULL,
  amount_base INTEGER NOT NULL,
  unsigned_tx BLOB NOT NULL,
  signature TEXT,
  status TEXT NOT NULL,           -- ISSUED | HELD | RETURNED | FORFEITED | LOCKED (cool-down)
  locked_at REAL,
  unlock_at REAL
);
-- DEVELOPMENT faucet: one grant per wallet.
CREATE TABLE IF NOT EXISTS faucet_grants (
  wallet TEXT PRIMARY KEY,
  signature TEXT NOT NULL,
  created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS profiles (
  wallet TEXT PRIMARY KEY,
  xp INTEGER NOT NULL DEFAULT 0,
  verified_count INTEGER NOT NULL DEFAULT 0,
  rejected_count INTEGER NOT NULL DEFAULT 0,
  reputation INTEGER NOT NULL DEFAULT 0,
  current_streak INTEGER NOT NULL DEFAULT 0,
  best_streak INTEGER NOT NULL DEFAULT 0,
  last_day INTEGER,
  freezes INTEGER NOT NULL DEFAULT 0
);
-- Squads: 3-8 friends sharing one streak. One squad per wallet.
CREATE TABLE IF NOT EXISTS squads (
  id TEXT PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS squad_members (
  squad_id TEXT NOT NULL REFERENCES squads(id),
  wallet TEXT NOT NULL UNIQUE,
  joined_day INTEGER NOT NULL
);
-- Streak freezes bought with SKR: user -> pool transfer, half of it burned.
CREATE TABLE IF NOT EXISTS freeze_orders (
  id TEXT PRIMARY KEY,
  wallet TEXT NOT NULL,
  amount_base INTEGER NOT NULL,
  unsigned_tx BLOB NOT NULL,
  status TEXT NOT NULL,           -- ISSUED | PAID
  payment_signature TEXT,
  burn_signature TEXT,
  created_at REAL NOT NULL
);
"""


def connect(path: str | Path) -> sqlite3.Connection:
    db = sqlite3.connect(path, check_same_thread=False)  # default mode: `with db:` is one transaction
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript(SCHEMA)
    for table, col, default in (("checkpoints", "still_pct", -1), ("checkpoints", "touch_motion", -1),
                                ("profiles", "freezes", 0)):  # databases created before these columns existed
        try:
            db.execute(f"ALTER TABLE {table} ADD COLUMN {col} INTEGER NOT NULL DEFAULT {default}")
        except sqlite3.OperationalError:
            pass
    for col in ("locked_at", "unlock_at"):
        try:
            db.execute(f"ALTER TABLE stakes ADD COLUMN {col} REAL")
        except sqlite3.OperationalError:
            pass
    return db
