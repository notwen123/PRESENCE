<p align="center"><img src="docs/img/banner.png" alt="FACEDOWN: commit, put it down, prove it. Live on Monad testnet. Proof of human for AI agents." width="100%"></p>

<p align="center">
  <a href="docs/whitepaper/presence_whitepaper.pdf"><b>Whitepaper</b></a> ·
  <a href="#monad-metropolis-submission"><b>Monad submission</b></a> ·
  <a href="#presence-on-monad-proof-of-a-human-for-contracts-and-ai-agents"><b>Live on Monad</b></a> ·
  <a href="#why-monad"><b>Why Monad</b></a> ·
  <a href="#how-it-works"><b>How it works</b></a> ·
  <a href="#run-it"><b>Run it</b></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Monad-testnet%20live-6E54FF?labelColor=1D2B24" alt="Monad testnet live">
  <img src="https://img.shields.io/badge/track-Trust%2C%20Identity%20%26%20AI%20Infra-6E54FF?labelColor=1D2B24" alt="Track: Trust, Identity and AI Infrastructure">
  <img src="https://img.shields.io/badge/Solidity-Foundry-363636?labelColor=1D2B24" alt="Solidity, Foundry">
  <img src="https://img.shields.io/badge/Android-Kotlin%20%C2%B7%20Compose-3DDC84?logo=android&logoColor=white&labelColor=1D2B24" alt="Android Kotlin Compose">
  <img src="https://img.shields.io/badge/tests-53%20backend%20%C2%B7%205%20contract%20%C2%B7%2010%20Android-1F9D55?labelColor=1D2B24" alt="Tests">
</p>

**AI agents can now operate phones, so any reward that checks for taps can be farmed.** **PRESENCE** proves that a
real person physically did a process on a real device, and records it once on **Monad**, where any contract can check
it with `isPresent()`. The session is timed by our server, every check-in must be a physical knock the accelerometer
feels, and claims are bound to one device. **FACEDOWN** is the first app built on it: book a session, lay the phone
face-down, knock on its back when it buzzes, and the verified session lands in `PresenceRegistry` on Monad.

## Monad Metropolis submission

| | |
|---|---|
| **Primary track** | **Trust, Identity & AI Infrastructure**: a protocol-level primitive for trust and provenance in the AI-agent era. PRESENCE proves a real person physically did a process on a real device, and any Monad contract can check it with `isPresent()`. |
| **Built during the hackathon** | `PresenceRegistry` and `HumanGatedVault` (Solidity, Foundry, 5 tests), the ATTEST server's Monad anchoring (3 tests), the live verifier page (`/monad`) and the testnet demo below. FACEDOWN's app and verification engine are the existing context we built on. |
| **Sponsor bounties** | None claimed yet. We list a bounty here only once its integration is built and demoable. |

## PRESENCE on Monad: proof of a human, for contracts and AI agents

**Monad Metropolis · Trust, Identity & AI Infrastructure.** PRESENCE verifies a real person's physical session
off-chain (server-timed, hash-chained, physical knock check-ins) and records the verdict **once** in
[`PresenceRegistry`](contracts/src/PresenceRegistry.sol) on Monad. Any contract can then ask
`isPresent(identity, minAssurance, maxAgeDays)`. Identities and missions go on-chain only as SHA-256 hashes.

[`HumanGatedVault`](contracts/src/HumanGatedVault.sol) shows why that matters in the agent era: an AI agent's key can
move small amounts alone, but **any withdrawal above its limit needs a fresh proof that its human was physically
present**. Taps, scripts and agents can't produce that proof.

**Live on Monad testnet (chain 10143), both contracts source-verified (Sourcify exact match):**

| | |
|---|---|
| `PresenceRegistry` | [`0x1d13EAC5bADfA35eB2767f10A50C27CF54d7F179`](https://testnet.monadvision.com/address/0x1d13EAC5bADfA35eB2767f10A50C27CF54d7F179) |
| `HumanGatedVault` | [`0x2AF710af85914DEe0AA89017223638367645f6b4`](https://testnet.monadvision.com/address/0x2AF710af85914DEe0AA89017223638367645f6b4) (agent limit 0.01 MON) |
| ① Agent withdraws 0.03 MON, no presence proof: **blocked** (`HumanPresenceRequired`) | [`0xe257dd0b…70dc`](https://testnet.monadvision.com/tx/0xe257dd0baf4eacc1ea37ef191e27f8a75d3f4d548d23e83275977d06961970dc) |
| ② A real 2-minute FACEDOWN session is verified by ATTEST and recorded | [`0xa0466d10…8e52`](https://testnet.monadvision.com/tx/0xa0466d10fb58ae4c9fcfbc52360f234bf0b99c49fde663b2e31991fa85348e52) |
| ③ Same agent, same 0.03 MON: **allowed** | [`0x1d1befe4…3bf4`](https://testnet.monadvision.com/tx/0x1d1befe4e297cfef6b0924e3be01f7ac998f0f33dd6117929f53a4868d873bf4) |

All addresses and hashes: [`contracts/deployments/monad-testnet.json`](contracts/deployments/monad-testnet.json).
Reproduce: `cd contracts && forge test` (5 tests), then [`scripts/monad_demo.py`](scripts/monad_demo.py) against an
ATTEST server started with `MONAD_REGISTRY` and `MONAD_ATTESTOR_KEY`. The session in ② ran through the reference client (the
same protocol the app speaks), in development mode. A live verifier page is served by ATTEST at `/monad`.

## The problem

<table><tr>
<td width="55%" valign="top">

**Agents can tap. They can't physically be there.**

- **AI agents can now operate a phone.** They tap, type and approve, so any reward, quest or airdrop that only checks
  for taps on a screen can be farmed by a bot ([Liu & Huang, arXiv 2609.02154](https://arxiv.org/abs/2609.02154)).
- **Wallets are free; devices aren't.** One farmer can run thousands of wallets, but each needs a real phone to pass a
  physical check, and PRESENCE caps claims and rewards per device.
- **Focus tools can't be checked either.** Blockers are one tap from off, focus timers run on your own phone, and
  commitment apps need a referee (a friend, a reviewer or an AI looking at photos). Nothing real can ride on them.

**The missing piece: proof that a person physically did the process, cheap enough to pay out on-chain.** FACEDOWN
is how that proof gets made every day: a habit people want, that produces labelled human sessions.

</td>
<td width="45%"><img src="docs/img/gif/problem.gif" alt="Notifications pulling people away from what they meant to do, from the film"></td>
</tr></table>

### Who pays

- **Quest and airdrop issuers** that need bot-resistant distribution: priced per verified
  human-day, checked on-chain through [`verifyPresence()`](docs/integration.md).
- **Employers, schools and parents** funding focus pools: sponsor deposits pay verified sessions (built: sponsor pools,
  on-chain deposit verification).
- **Wallets and agent-payment apps** that want a physical-presence check before a high-value approval. *Hypothesis,
  not built.*

No customers have signed; these are the buyers we will test first.

### Economic security

Every device identity (hashed device identity; the wallet in development mode) can earn at most
`DAILY_REWARD_CAP_PER_DEVICE` in sponsor rewards and bonuses per UTC day; settlement pays `min(reward, cap left today)`.
Stakes are always returned in full and never count against the cap. The design argument, not a measured result:

> farming one device is only profitable if **cap_per_day × days > device_cost**,

so a low per-device daily cap (default 3 test tokens) makes device farms uneconomic, while a real person doing a few
sessions a day never notices it.

## Why Monad

PRESENCE needs a chain where a proof of presence is cheap to write, fast to finalise, and readable by **any** contract,
especially the EVM wallets and agent frameworks that AI agents already use. Measured on Monad testnet, 13 Oct 2026:

| Measured | Result |
|---|---|
| Recording one verified session (`record()`) | **1 transaction, 161,016 gas**, about 0.0164 MON at the testnet gas price of 102 gwei |
| Block time, averaged over 1,000 blocks | **0.304 s** |
| Contracts | `PresenceRegistry` and `HumanGatedVault`, both source-verified (Sourcify exact match) |

- **Composable by default.** It's the EVM, so any wallet, vault, quest or agent framework can call
  `isPresent(identity, minAssurance, maxAgeDays)` with no SDK. `HumanGatedVault` is about 50 lines.
- **Fast enough to sit in the loop.** With sub-second blocks, a presence proof can gate an agent's next action
  instead of a batch job hours later.
- **Agents live here.** Embedded and agent wallets on Monad (Privy, Dynamic, MetaMask Agent Wallet) are exactly the
  accounts that need a "was a human actually here?" check before high-value actions.
- **Privacy.** The registry stores only SHA-256 hashes of the device identity and the mission, plus a 32-byte evidence
  root. Raw sensor data never leaves the phone.

## How it works

```mermaid
sequenceDiagram
    participant App as FACEDOWN app (Android)
    participant A as ATTEST server
    participant R as PresenceRegistry (Monad)
    participant V as Any contract (e.g. HumanGatedVault)
    App->>A: start session (wallet signature + per-session Keystore key)
    loop every window, server-timed
        App->>A: hash-chained, key-signed checkpoint + motion summaries (stillness, knock jolt)
    end
    App->>A: submit
    A->>A: re-derive the chain, 9-rule policy on the server clock, bot-timing + touch + stillness checks
    A->>R: record(identityHash, missionHash, day, seq, evidenceRoot, assurance)
    V->>R: isPresent(identityHash, 2, 1)?
```

- **Client collects, ATTEST decides, Monad records.** The app never sends `verified` or `reward`.
- **Evidence chain.** `checkpoint_i = SHA256("PRESENCE/cp/v3" | prev | payload_i | nonce)`, byte-identical in Kotlin and
  Python (shared golden vectors). Only a 32-byte root goes on-chain.
- **Privacy.** Per window: a foreground flag, an answer count, one answer time and two motion summaries. Raw sensor data
  stays on the phone. On Monad, identities and missions are SHA-256 hashes.
- **Create-once.** `record()` reverts with `AlreadyAttested` for a repeated (identity, mission, day, claim index).
- **Economic security.** A per-device daily reward cap: farming one device pays only if *cap × days > device cost*.

The full maths is in the [whitepaper](docs/whitepaper/presence_whitepaper.pdf).

## What a cheater would try

| Attempt | Why it fails |
|---|---|
| Fake the timer / fast-forward | The **server** times the session from its own clock. Early checkpoints are rejected. |
| Use the phone during the session | Leaving the app is recorded in every checkpoint; picking it up breaks face-down stillness. |
| Let a script or an AI phone agent answer | Answers must be physical knocks the accelerometer feels. **29 of 30 taps injected by a script on a real phone** read below the human-touch floor. |
| Replay or forge evidence | Checkpoints form a SHA-256 chain signed by a per-session Android Keystore key bound into the sign-in. |
| Record the same session twice | `PresenceRegistry` is create-once per (identity, mission, day, claim index). |
| An agent drains its owner's vault | `HumanGatedVault` reverts with `HumanPresenceRequired` above its limit unless the owner was present recently. |

<table><tr>
<td width="50%"><img src="docs/img/charts/touch_motion.png" alt="Touch motion: injected taps on a real phone fall below the 150 mm/s² floor"></td>
<td width="50%" valign="top">

**Scripts get caught by physics, not by timing.** Script-driven taps on a real phone read 20–115 mm/s² (median 35.5)
against a 150 mm/s² floor; their answer *timing* looked human. **And we say where it breaks:** a device that fakes its
own sensors (the emulator's virtual accelerometer, or a privileged system-level agent, see
[ActReal, arXiv 2608.30038](https://arxiv.org/abs/2608.30038)) passes today. Hardware key attestation is the fix.

</td></tr></table>

## FACEDOWN: the app that makes the proofs

<table><tr>
<td width="50%"><img src="docs/img/gif/how.gif" alt="Choosing a session length in the app"></td>
<td width="50%"><img src="docs/img/gif/facedown.gif" alt="Phone face-down; knock on the back when it buzzes"></td>
</tr><tr>
<td><img src="docs/img/gif/squad.gif" alt="Squads share one streak"></td>
<td><img src="docs/img/gif/urge.gif" alt="Urge Catcher asks first"></td>
</tr></table>

| | Feature | How |
|---|---|---|
| ⏱️ | **Book a session** | 2, 10, 25 min or a Facedown Hour, with a daily reminder. Free (sponsor-funded) or with a stake that is never lost. |
| 📱 | **Put it face-down** | The screen goes black; the phone buzzes at unpredictable moments and you knock on the back to answer. |
| 🧪 | **Prove it** | Accelerometer stillness + knock jolt, server clock, signed hash chain. |
| ⛓️ | **Recorded on Monad** | Each verified session is written once to `PresenceRegistry`. |
| 🛑 | **Urge Catcher** | Opening a distracting app offers a 10-minute session first, or *Earn your scroll*: 60 s face-down unlocks it for 10 min. |
| 👥 | **Squads** | 3–8 friends share one streak; a freeze covers one missed day (repairable within 24 h). |

## The research behind it

Every product decision in FACEDOWN comes from a published study. Each number below is quoted from the paper's
abstract; follow the links.

<table><tr>
<td width="45%"><img src="docs/img/gif/research.gif" alt="The research cards from the film: 91%, 26 min, 57%"></td>
<td width="55%" valign="top">

| | Finding | Source |
|---|---|---|
| **91%** | of people improved attention, mental health or well-being after 2 weeks without mobile internet (RCT) | Castelo et al., [PNAS Nexus 2025](https://doi.org/10.1093/pnasnexus/pgaf017) |
| **26 min/day** | more phone time cut when people **chose their own** goal (73% larger; RCT, N = 149, 12 weeks) | Sachdeva, Somasundaram & Sharma (IIM Udaipur, ISB), [Soc Sci Med 2026](https://doi.org/10.1016/j.socscimed.2026.119683) |
| **57%** | fewer openings of target apps with a short pause first (the *one sec* app) | Grüning, Riedel & Lorenz-Spreen, [PNAS 2023](https://doi.org/10.1073/pnas.2213114120) |
| **90% vs 13.7%** | of people accepted reward programs vs deposit programs (n = 2,538); deposits worked better among those who took them (+13.2 pts) | Halpern et al., [NEJM 2015](https://doi.org/10.1056/NEJMoa1414293) |
| **2×** | Appointments **more than doubled** uptake vs financial commitment devices; most commitment-device takers lost their money | Derksen et al., [arXiv 2110.06876](https://arxiv.org/abs/2110.06876) |
| **+3.2 kg** | Group-pooled incentives beat individual ones for weight loss (95% CI 0.9–5.5) | Kullgren et al., [Ann Intern Med 2013](https://doi.org/10.7326/0003-4819-158-7-201304020-00002) |
| **98.4%** | Accuracy of a motion signal at telling human from AI-agent-driven logins (table-top; 92.6% hand-held; 116 pilot sessions) | Liu & Huang, [arXiv 2609.02154](https://arxiv.org/abs/2609.02154) |
| **9 of 12** | NFT play-to-earn games where trading players lost money on average | Gao et al., [arXiv 2602.13882](https://arxiv.org/abs/2602.13882) |

</td></tr></table>

<table><tr>
<td width="150" align="center"><img src="app/src/main/res/drawable-nodpi/mascot_scientist.webp" width="130" alt="Pip the scientist"></td>
<td>

**Pip, reading the papers:** "Here's what the research told us to build. **Rewards first**, because almost nobody
signs up to lose money (Halpern). **Daily appointments**, because they beat penalties (Derksen). **You pick the
length**, because self-chosen goals work 73% better (Sachdeva). **A pause before the app opens**, because it halves
openings (Grüning). **Squads**, because groups pull harder (Kullgren). And **no new token**, because emission-funded
games leave players at a loss (Gao)."

</td></tr></table>

## Honest limitations

- **One attestor today.** ATTEST signs every record; `PresenceRegistry` supports several attestors (`setAttestor`), and
  a quorum of independent attestors re-checking the same deterministic evidence is the next step.
- **Sensors can be faked by a privileged agent** ([ActReal](https://arxiv.org/abs/2608.30038)): motion evidence is a
  strong signal, not proof, until hardware key attestation is required.
- **Real knocks across many phones are not yet measured**; the 150 mm/s² floor is a design value.
- **The Android app now signs in with MetaMask** (MetaMask Android SDK, EIP-4361 + `personal_sign`, Monad testnet):
  [`releases/presence-monad-metamask.apk`](releases/presence-monad-metamask.apk). On Monad, missions carry no stake yet.
- **Testnet only**, development mode (device check bypassed and labelled), test tokens with no value. No customers yet.

## Run it

```bash
cd contracts && forge test                      # 5 tests: create-once, attestor auth, streaks, expiry, vault gate
cd backend && uv run pytest -q                  # 54 tests: protocol, policy, physical evidence, caps, Monad anchoring
# ATTEST anchored on Monad (key from your own .env.local, never committed):
MONAD_REGISTRY=0x1d13EAC5bADfA35eB2767f10A50C27CF54d7F179 MONAD_ATTESTOR_KEY=0x... ATTEST_DEV_MODE=true \
  uv run --project backend python -m attest.app          # then open http://127.0.0.1:8787/monad
uv run --project backend python scripts/monad_demo.py session http://127.0.0.1:8787 device.key
```

## Team WARRIO

- **Aaditya** (iocryp15), lead · [@Aaditya1273](https://github.com/Aaditya1273)
- **nebula10**

<table><tr>
<td width="150" align="center"><img src="app/src/main/res/drawable-nodpi/mascot_champion.webp" width="130" alt="Pip celebrating"></td>
<td>

**Pip, signing off:** "Put it down. Prove it. Start with 2 minutes."

</td></tr></table>
