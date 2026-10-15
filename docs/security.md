# Security

## Threats and what actually mitigates them

| Threat | Mitigation in this code | Residual risk |
|---|---|---|
| **Replay** | Server nonce (UNIQUE), single-use state machine: any repeated step returns `NONCE_REPLAY` | — |
| **Double record** | SQLite claim registry checked under a lock, `UNIQUE (identity_key, mission_id, day, seq)`; on Monad, `PresenceRegistry.record` reverts with `AlreadyAttested` for a repeated (identity, mission, day, seq) | — |
| **Double payout** | ATTEST records first, then pays; a session is settled once (in-progress guard, state `SETTLED`). A retry after a recorded-but-unpaid session fails at `record` rather than paying again | Record and payment are two transactions, not one; a crash between them leaves a recorded, unpaid session that needs manual follow-up |
| **Client tampering** | Client never reports results; server recomputes hashes, measures time, evaluates policy | A modified client can still *fake process signals* (taps, foreground, sensor numbers). See below. |
| **Fake / compressed evidence** | Hash chain recomputed server-side; checkpoint cadence and total duration use server receive times | Real-time scripted input on a real device |
| **Device farming** | One claim index per device identity per mission per day, and a per-device daily reward cap (`DAILY_REWARD_CAP_PER_DEVICE`, 0.1 MON live) | In development mode (the live server) the identity is the wallet, so a farmer with many wallets gets many identities |
| **Session theft** | Every checkpoint and the root are signed by a per-session Android Keystore key whose fingerprint is inside the SIWE message the wallet signed | Not hardware attestation; a rooted device can extract keys from software keystores |
| **Wrong-wallet sign-in** | ATTEST recovers the EIP-191 signer of the exact SIWE text it issued and requires it to be the session's wallet; the message carries chain id 10143 and an expiry | — |
| **Witness collusion** | N/A: P3 not implemented | — |
| **Privacy leakage** | Counts plus two motion summaries per window; no raw sensor data, no location; on Monad only SHA-256 hashes of identity and mission plus a 32-byte root | Stakes and payouts are ordinary MON transfers, so a wallet's activity in `PresenceVault` is public |
| **Stake swapped by a client** | The wallet sends `stake(ref)` itself; ATTEST accepts it only if the receipt has a `Staked` event from `PresenceVault` with the issued `ref`, the session's wallet as sender, and the exact amount | — |
| **Draining the reward pool** | Reward ≤ mission's deposited pool; bonus ≤ `stake × bonus_rate` and ≤ pool; per-device daily cap; only the attestor can `pay` | Development mode keys claims by wallet |
| **Stake lost to a race** | Claim limit is checked before the stake is accepted and again at verify | A concurrent claim between the checks leaves the stake held until expiry (documented `ponytail:` note); No-Loss and cool-down stakes are refunded later, not lost |
| **Fake sponsor deposits** | `/sponsor/deposit` reads the transaction receipt from Monad and credits only the MON in `Funded` events from `PresenceVault`; each transaction hash credits once | — |
| **Unfunded rewards** | Rewards are reserved from the mission's deposited pool at verify; an empty pool still records the session but pays no reward (`funding: UNFUNDED`) | — |
| **Rogue records** | `PresenceRegistry.record` only from allowlisted attestors; day must be today or yesterday (UTC); assurance 1..4 | Attestor key compromise. It is an environment secret today; it belongs in a KMS/HSM in production. |
| **Rogue payouts** | `PresenceVault.pay` / `burn` only from the attestor | Same key as above |
| **Agent drains its owner** | `AgentGuard`: the agent may send ≤ 0.01 MON alone; above that, an intent that needs a P-256 signature by the bound phone over `sha256(intentId)`, verified on-chain via the RIP-7212 precompile at `0x100`. The intent id covers guard, chain, account, recipient, amount and nonce; it expires in 120 s and executes once. A forged signature reverts (`backend/scripts/knock_e2e.py`) | Below the limit the agent can make repeated small sends; there is no per-day limit in the contract |
| **Agent sends somewhere else** | The agent is a Privy server wallet whose Privy policy allows `eth_signTransaction` only to `AgentGuard` on chain 10143; Privy answers `policy_violation` otherwise | Relies on Privy enforcing the policy |
| **ATTEST forges an approval** | `AgentGuard.approve` is callable only by the attestor, but it verifies the phone's signature itself; ATTEST can't produce one without the phone's key | ATTEST can refuse to relay (liveness, not safety) |
| **Device key rebinding** | `AgentGuard.bindDevice`: the account id is `keccak256(x, y)` of the key and binds once (`AlreadyBound`) | — |
| **Auth token exfiltration** | Only the public wallet address is stored in app prefs; the Privy session is held by the Privy SDK; `allowBackup=false` | Rooted devices |
| **Cleartext traffic** | Cleartext allowed only in the **debug** build's network security config | Release builds need an HTTPS `presence.backendUrl` |

## Automation heuristics

Each window's check-in appears at a random moment 15–60% into the window. The app records how long it was pending before it was
answered (`response_ms`), and that value is inside the signed, hash-chained checkpoint. `anomaly.assess`
(statistics, not machine learning) scores the answers:

- answers under 150 ms, faster than human reaction to an unexpected prompt
- answer times that barely vary (spread under 15 ms is treated as certain, under 40 ms as suspicious)

A risk score ≥ 0.9 rejects the run with `AUTOMATION_DETECTED`. This catches naive scripts. **A script that
injects human-like random delays defeats it.** It raises the cost of farming; it does not prevent it.

**Physical evidence (production).** Each check-in must jolt the accelerometer (`min_touch_motion`, 150 mm/s²): a knock
on the back of the face-down phone, or a finger on the glass. The phone must also lie still and face-down for most of
each window (`min_still_pct`, 60%). Injected input from adb, an accessibility service or an AI phone agent doesn't move
the device, so it fails with `AUTOMATION_DETECTED`. Measured on a real phone, script-driven taps read 20–115 mm/s²
(median 35.5), below the floor, while their timing looked human. This does not stop a modified app that fabricates the
two numbers, a device that fakes its own sensors (an emulator's virtual accelerometer, or a privileged system-level
agent), or a mechanical knocker: that needs hardware attestation (P4), not built.

## Honest limitations

- **One attestor today.** One ATTEST server holds the key that writes `PresenceRegistry`, pays from `PresenceVault`
  and relays to `AgentGuard`.
- **Development mode on the live server.** The device check is bypassed and labelled; identity is the wallet; the
  physical rules are reported, not enforced (unless `ATTEST_ENFORCE_PHYSICAL=true`).
- **Motion can be spoofed** by a modified app or a privileged agent that controls the sensors. P2 plus the heuristics
  makes farming slower and per-device, not impossible. PRESENCE does not claim Sybil resistance beyond one claim per
  device identity.
- **Web knocks are weaker.** On `/agent` the browser self-reports face-down time and peak jolt; only the P-256
  signature is checked on-chain. These records use assurance 1.
- **Testnet only.** All amounts are testnet MON with no value. No audits.
- Missions do not prove location. No GPS is collected.
- There is no Play Integrity / key attestation check yet (that is what P4 would add).
- The ATTEST server is a single process with one lock and SQLite (on the free Render plan, `/tmp/attest.db`, which
  does not survive a redeploy). Fine for a pilot; not horizontally scalable.

## Logging

`service.event()` writes one JSON line per stage (`session_id`, `mission_id`, stage, result, reason, ts). Keys,
signatures, auth tokens and evidence payloads are never logged. The Android client logs only stage + reason.
