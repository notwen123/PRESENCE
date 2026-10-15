# Trust model

## Who decides what

| Decision | Owner | Why |
|---|---|---|
| Session nonce | ATTEST | Client-generated nonces are replayable |
| Wallet identity | ATTEST (recovers the EIP-191 signer of the exact SIWE text it issued) | Client can be patched |
| Device identity | ATTEST (the identity key that claim limits and the daily cap are counted against) | An address alone proves nothing; in development mode it is the wallet |
| Timing / duration | ATTEST server clock | Client timestamps are informational only |
| Evidence validity | ATTEST (recomputes every hash, verifies every session-key signature) | |
| Policy PASS/FAIL | ATTEST `policy.evaluate` | |
| Claim limit, daily reward cap, XP, streak, league | ATTEST database | |
| Stake accepted | ATTEST reads the `Staked` event on Monad: right vault, issued `ref`, right sender, exact amount | The wallet sends the transaction itself |
| Payouts and burns | `PresenceVault`: only the attestor key can `pay` or `burn` | |
| Durable record | `PresenceRegistry` on Monad; only allowlisted attestors can `record` | Readable by anyone and any contract |
| Agent spend above 0.01 MON | The human's phone: `AgentGuard` verifies its P-256 signature on-chain | ATTEST relays but cannot forge it |
| Where the agent can send at all | Privy policy on the agent's server wallet: `eth_signTransaction` only to `AgentGuard` on chain 10143 | A compromised agent process can't sign transfers elsewhere |

The Android client never sends `verified`, `reward` or `assurance`. It sends evidence and signatures.

Wallet keys: the user's embedded wallet is created and held by Privy after Google sign-in; ATTEST never sees it.
The agent's key is a Privy server wallet. The attestor key (`0x5aB3036C…4954`) is an environment secret on the
ATTEST server.

## Assurance levels

| Level | Meaning in this implementation | Status |
|---|---|---|
| **P1 VERIFIED** | Wallet signed a fresh server nonce (SIWE) **and** passed the device check. The `/agent` web knock is also recorded at level 1. | Implemented (device check bypassed and labelled in development mode) |
| **P2 PROCESS** | P1 + a continuous, server-timed, session-key-signed evidence chain that satisfies the mission policy, including physical knock and stillness evidence | Implemented; every FACEDOWN app session records at 2 |
| **P3 WITNESSED** | P2 + fresh-challenge co-presence witnesses under a quorum policy | **Not implemented.** Policies with `witness_required` are refused at load time. |
| **P4 HIGH ASSURANCE** | Hardware-backed device attestation (Android key attestation for the session key) | **Not implemented** |

`PresenceRegistry.record` accepts levels 1 to 4; integrators pick the minimum with `isPresent(identity, minAssurance,
maxAgeDays)`. Assurance is a policy-defined level of evidence, not proof of personhood and not a bot-detection
guarantee.

## Development mode

`ATTEST_DEV_MODE=true` (set on the live server in `render.yaml`) replaces the device verifier with one that returns
`DEV_BYPASS`, and the identity key becomes `dev:<wallet>`. It is surfaced:

- in `/health`, every session response and every receipt (`"mode": "development"`),
- as `DEV_BYPASS` in the receipt's device-eligibility check,
- in the app as a "Development · <network>" label and a receipt warning ("the device check was bypassed and MON
  is a test token with no value").

Development mode also reports, instead of enforcing, the physical rules (`ATTEST_ENFORCE_PHYSICAL=true` turns
enforcement on). It is never the default (`Settings.from_env` defaults to `false`; a test enforces it).

## Path to several attestors (planned, not built)

Today one ATTEST server holds the only attestor key for `PresenceRegistry`, `PresenceVault` and `AgentGuard`. That is
a central point of trust, and it is the honest state of this MVP. What exists already:

- `PresenceRegistry` keeps an attestor allowlist (`isAttestor`, `setAttestor`) and stores which attestor wrote each
  record, so integrators can choose whom to trust.
- The evidence chain is deterministic and its golden vectors are public, so an independent ATTEST can recompute the
  same root from the same evidence.

What it would take:

1. **Independent verification.** Each attestor runs its own ATTEST over the same evidence and signs
   `(identity, mission, day, seq, evidenceRoot, assurance)`.
2. **Quorum on-chain.** A registry version whose `record` requires `m` of `n` attestor signatures (ECDSA via
   `ecrecover`) before writing.
3. **Stake and slashing.** Attestors bond MON behind their records; a record later proven fraudulent costs bond.

There is deliberately no code stub for this. A quorum mode that doesn't verify signatures would be a fake check.
