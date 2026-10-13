<p align="center"><img src="docs/img/banner.png" alt="FACEDOWN: commit, put it down, prove it. Live on Monad testnet. Proof of human for AI agents." width="100%"></p>

<p align="center">
  <a href="docs/whitepaper/presence_whitepaper.pdf"><b>Whitepaper</b></a> ·
  <a href="#monad-metropolis-submission"><b>Monad submission</b></a> ·
  <a href="#judges-try-it-in-60-seconds-no-install"><b>Try it</b></a> ·
  <a href="#knock-to-approve-a-human-in-the-loop-for-ai-agents"><b>Knock to Approve</b></a> ·
  <a href="#why-monad"><b>Why Monad</b></a> ·
  <a href="#how-it-works"><b>How it works</b></a> ·
  <a href="#run-it"><b>Run it</b></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Monad-testnet%20live-6E54FF?labelColor=1D2B24" alt="Monad testnet live">
  <img src="https://img.shields.io/badge/track-Trust%2C%20Identity%20%26%20AI%20Infra-6E54FF?labelColor=1D2B24" alt="Track: Trust, Identity and AI Infrastructure">
  <img src="https://img.shields.io/badge/Solidity-Foundry-363636?labelColor=1D2B24" alt="Solidity, Foundry">
  <img src="https://img.shields.io/badge/Android-Kotlin%20%C2%B7%20Compose-3DDC84?logo=android&logoColor=white&labelColor=1D2B24" alt="Android Kotlin Compose">
  <img src="https://img.shields.io/badge/tests-59%20backend%20%C2%B7%2010%20contract%20%C2%B7%2010%20Android-1F9D55?labelColor=1D2B24" alt="Tests">
</p>

**AI agents can now operate phones, so any reward that checks for taps can be farmed.** **PRESENCE** proves that a
real person physically did a process on a real device, and records it once on **Monad**, where any contract can check
it with `isPresent()`. The session is timed by our server, every check-in must be a physical knock the accelerometer
feels, and claims are bound to one device. **FACEDOWN** is the first app built on it: book a session, lay the phone
face-down, knock on its back when it buzzes, and the verified session lands in `PresenceRegistry` on Monad.

<table><tr>
<td width="150" align="center"><img src="app/src/main/res/drawable-nodpi/mascot_wave.webp" width="130" alt="Pip waving"></td>
<td>

**Hi, I'm Pip.** I live inside FACEDOWN: I'm on the launch screen, I count down with you, and I celebrate when the
server verifies your session. I'll walk you through this page. The short version: **phones are built to be picked up,
and nothing holds you to the promise you made yourself. We built the thing that does, and we made it provable.**

</td></tr></table>

## Monad Metropolis submission

| | |
|---|---|
| **Primary track** | **Trust, Identity & AI Infrastructure**: a protocol-level primitive for trust and provenance in the AI-agent era. PRESENCE proves a real person physically did a process on a real device, and any Monad contract can check it. |
| **Built during the hackathon** | `PresenceRegistry` (+ `presentDays`), `AgentGuard` (Knock to Approve, on-chain P-256), `SustainedHumanAirdrop`, `PresenceVault` (stakes, No-Loss refunds, freezes in MON), `HumanGatedVault`; 10 Foundry tests; ATTEST's Monad settlement, MetaMask (SIWE) sign-in, the `/agent` web demo and the `/monad` verifier. FACEDOWN's app and verification engine are the existing context we built on. |
| **Sponsor bounty: Privy** (beyond login) | The AI agent's wallet is a **Privy server wallet** with a **Privy policy**: it may sign only transactions to `AgentGuard` on Monad (chain 10143); Privy refuses anything else (`policy_violation`). Inside that, `AgentGuard` makes any transfer over 0.01 MON wait for a human knock. The FACEDOWN app signs in with Google through Privy and uses the embedded wallet to sign sessions, stakes and freezes on Monad (Privy Android SDK). |

### Judges: try it in 60 seconds, no install

Open **[presence-h7uy.onrender.com/agent](https://presence-h7uy.onrender.com/agent) on your phone's browser** (Chrome on Android; the APK is optional):

1. **Set up this phone.** The browser makes a P-256 key that never leaves the device and binds it to `AgentGuard`.
2. **Agent sends 0.005 MON.** Under the 0.01 limit: it goes through with no human.
3. **Agent sends 0.03 MON.** Over the limit: **blocked on-chain**, waiting for you.
4. **Put the phone face-down and knock twice on its back.** The phone signs that exact transfer, and `AgentGuard`
   checks the signature itself through Monad's P-256 precompile (`0x100`, RIP-7212). The money moves, and your presence
   is recorded in `PresenceRegistry`.

## PRESENCE on Monad: proof of a human, for contracts and AI agents

PRESENCE verifies a real person's physical session off-chain (server-timed, hash-chained, physical knock check-ins)
and records the verdict **once** in [`PresenceRegistry`](contracts/src/PresenceRegistry.sol) on Monad. Identities and
missions go on-chain only as SHA-256 hashes. Contracts ask two questions:

- **Is a human here now?** `isPresent(identity, minAssurance, maxAgeDays)`
- **Does a human keep showing up?** `presentDays(identity, fromDay, toDay)`: distinct UTC days verified present.
  Proof of personhood checks a human once; this makes a farm's cost grow with identities × days
  (cf. the bounded-participation argument in [arXiv 2609.35300](https://arxiv.org/abs/2609.35300)).

### Knock to Approve: a human in the loop for AI agents

[`AgentGuard`](contracts/src/AgentGuard.sol) holds an agent's budget. The agent itself is a **Privy server wallet**: a Privy policy lets it sign only calls to `AgentGuard` on Monad, so even a compromised agent can't send funds anywhere else (we tested it: Privy answers `policy_violation`). Privy bounds *where* the agent can send; `AgentGuard` bounds *how much* it can send without its human. The agent spends small amounts alone; anything
above its limit becomes a pending intent that only its human can release, **by physically knocking on their phone**.
The phone signs `sha256(intentId)` with a P-256 key (WebCrypto in any phone browser; the same curve as Android
Keystore and Passkeys), and the contract verifies it with Monad's P-256 precompile. The server relays the
transaction but **cannot forge an approval**: without the phone's key, the contract reverts. Each signature is bound to
one intent (contract, chain, account, recipient, amount, nonce) and expires in 120 s.

**Live on Monad testnet (chain 10143), all contracts source-verified on Sourcify:**

| | |
|---|---|
| `PresenceRegistry` (v2) | [`0x5519dacf…E52B`](https://testnet.monadvision.com/address/0x5519dacface816519ACa3ee3cD592fd4c61dE52B) |
| `AgentGuard` | [`0xdC3Ba526…1B11`](https://testnet.monadvision.com/address/0xdC3Ba526FF565D0c76131D54a30aFA3c55041B11) (agent limit 0.01 MON; agent = Privy server wallet `0x55215B31…2271`) |
| `SustainedHumanAirdrop` | [`0x62CbaaAc…64d1`](https://testnet.monadvision.com/address/0x62CbaaAc53ef415fE0262E41e3ce4C2B9af164d1) (pays only after 3 present days in 30) |
| `PresenceVault` | [`0xfC157D4b…163B`](https://testnet.monadvision.com/address/0xfC157D4b8D9573413a3A793056D7625eD972163B) (stakes, refunds, freezes) |
| ① Phone key bound to the agent's account | [`0x41bd309d…0439`](https://testnet.monadvision.com/tx/0x41bd309df3eb8168f0ec604d5bb3551fe68e4732572fd06ad32c73f713d80439) |
| ② Agent (signed by Privy) sends 0.005 MON: **no approval needed** | [`0xaa726c9c…ff84`](https://testnet.monadvision.com/tx/0xaa726c9cdf881b438c162ef28fe3055a7865a3bf6034b26d426209ac8aa3ff84) |
| ③ Agent sends 0.03 MON: **held on-chain** (`IntentRequested`) | [`0xa33e7f59…3e59`](https://testnet.monadvision.com/tx/0xa33e7f59a14f99c57a6f6f7a7c49a9bf4e4a7b89c41466e4bf09f9b92b683e59) |
| ③b Agent tries to sign a transfer to any other address | **refused by Privy** (`policy_violation`), never reaches the chain |
| ④ A forged signature: **reverted** by the contract | reproduced by [`backend/scripts/knock_e2e.py`](backend/scripts/knock_e2e.py) |
| ⑤ The knock's P-256 signature: **verified on-chain, 0.03 MON sent** | [`0x0881af9d…f9b2`](https://testnet.monadvision.com/tx/0x0881af9d492b67d1edf291bb93bffb2130ff1f2f36eb7f0c5dcde2e6c4a8f9b2) |
| ⑥ The knock recorded as presence | [`0x3dcaa0a3…0365`](https://testnet.monadvision.com/tx/0x3dcaa0a3185f0a0941aa4d638a58afdf8f3a938fd2753b501adef71f253d0365) |
| A real FACEDOWN session from the phone app (MetaMask sign-in, 4 knock check-ins) | [`0xe07ccaa1…59c4`](https://testnet.monadvision.com/tx/0xe07ccaa13b6a2d2c381a9a455a7dd99e48509c68e9cb5d2337c5453412c759c4) |

The first demo ([`HumanGatedVault`](contracts/src/HumanGatedVault.sol): an agent withdrawal blocked, then allowed after
a verified session) is still live; all addresses and hashes are in
[`contracts/deployments/monad-testnet.json`](contracts/deployments/monad-testnet.json).
Reproduce: `cd contracts && forge test` (10 tests, real P-256 signatures), then `backend/scripts/knock_e2e.py`
against a running ATTEST.

### Add it to your contract: one line

```solidity
interface IPresence {
    function isPresent(bytes32 identity, uint8 minAssurance, uint32 maxAgeDays) external view returns (bool);
    function presentDays(bytes32 identity, int64 fromDay, int64 toDay) external view returns (uint256);
}

// a human verified present in the last day
require(IPresence(0x5519dacface816519ACa3ee3cD592fd4c61dE52B).isPresent(id, 2, 1), "human presence required");
```

### How it compares

| | Proves a unique human | Proves a human is **here, now** | Keeps proving it **every day** | Agent can't fake it with taps | Extra hardware |
|---|---|---|---|---|---|
| CAPTCHA | no | weakly, once | no | no (agents solve them) | none |
| World ID | **yes** (iris, once) | no | no | n/a | Orb |
| Passkeys / device attestation | no | a device, not a body | no | no (taps a button) | none |
| **PRESENCE** | no (one per device) | **yes**: physical knocks felt by the accelerometer, server-timed | **yes**: `presentDays` | **yes**: needs a phone lying face-down and a physical knock | none (the phone) |

PRESENCE is complementary to proof of personhood: World ID says *who is unique*; PRESENCE says *a body was at this
device just now, and keeps showing up*. A web-browser knock is weaker evidence than the native app, so `/agent`
records at assurance 1 and the FACEDOWN app at 2.

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
- **Wallets and agent-payment apps** that want a physical-presence check before a high-value approval (built as a
  testnet demo: `AgentGuard`; no customer yet).

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
| Contracts | `PresenceRegistry`, `AgentGuard`, `SustainedHumanAirdrop`, `PresenceVault`, `HumanGatedVault`, source-verified on Sourcify |

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

## Measured: physics catches scripts

<table><tr>
<td width="50%"><img src="docs/img/charts/touch_motion.png" alt="Touch motion: injected taps on a real phone fall below the 150 mm/s² floor"></td>
<td width="50%" valign="top">

**Scripts get caught by physics, not by timing.** Script-driven taps on a real phone read 20–115 mm/s² (median 35.5)
against a 150 mm/s² floor; their answer *timing* looked human. **And we say where it breaks:** a device that fakes its
own sensors (the emulator's virtual accelerometer, or a privileged system-level agent, see
[ActReal, arXiv 2608.30038](https://arxiv.org/abs/2608.30038)) passes today. Hardware key attestation is the fix.

</td></tr></table>

## What FACEDOWN does

| | Feature | How |
|---|---|---|
| ⏱️ | **Book a session** | A daily time with a reminder, and a length: **2, 10, 25 min or a Facedown Hour**. Pick **Free** (a sponsor pays), **No-Loss** (lock test tokens, always get them back, only finishers earn) or **Stake** (finish for your stake back + up to 20% from sponsors). |
| 📱 | **Put it face-down** | The screen goes black. The phone buzzes a few times at moments you can't predict; **knock on the back** to answer. One missed knock is forgiven. |
| 🧪 | **Prove it** | The accelerometer proves the phone lay face-down and still, and that every answer was a physical knock, not input injected by a script or AI agent. The server times everything on its own clock. |
| ⛓️ | **Get paid on-chain** | Each verified session is recorded once in `PresenceRegistry` on **Monad**; any contract can check it with `isPresent()`. |
| 🔒 | **Cool-down Lock** | Quit a Stake session and your stake is **locked for 7 days** (14, then 30 for repeat quits), then it comes back. Never lost, never given to anyone else. |
| 🛑 | **Urge Catcher** | Open Instagram, YouTube or X and FACEDOWN pauses first: *go facedown 10 min instead?* *Open anyway* unlocks after 10 seconds. |
| 🟢 | **Presence Minutes** | Whenever the phone lies face-down and still, a ring on Today fills toward a 3-hour daily goal, without opening the app. |
| 👥 | **Squads** | 3–8 friends share **one streak** that breaks for everyone if anyone misses a day. A **streak freeze** (paid in tokens, half burned) covers one missed day. |

<p align="center">
<img src="screenshots/home.png" width="15.5%" alt="Today: Presence Minutes ring, 2/10/25/60 min, Free | No-Loss | Stake">
<img src="screenshots/urge.png" width="15.5%" alt="Urge Catcher: go facedown 10 min instead of opening the app">
<img src="screenshots/mission.png" width="15.5%" alt="Confirm: No-Loss terms before any wallet prompt">
<img src="screenshots/facedown.png" width="15.5%" alt="Face-down: the screen goes black while the session runs">
<img src="screenshots/receipt.png" width="15.5%" alt="Receipt: human touch and phone-down verified, recorded on-chain">
<img src="screenshots/progress.png" width="15.5%" alt="Progress: squad streak and members, streak freezes">
<br><sub>Today · Urge Catcher · Confirm · Face-down · Receipt · Progress. Android emulator on <b>devnet</b>, development mode
(device check bypassed and labelled). Rewards are test tokens with no value.</sub>
</p>

## FACEDOWN in four moments

<table>
<tr>
<td width="50%" valign="top">

**① Choose how long, and what's at stake.** 2, 10, 25 or 60 minutes. Free, No-Loss or Stake, with the exact terms
on screen before any wallet prompt. MetaMask signs once.

<img src="docs/img/gif/how.gif" alt="Choosing length and stake in the app">

</td>
<td width="50%" valign="top">

**② Put it face-down, knock when it buzzes.** The screen goes dark. Check-ins come at unpredictable moments; a knock
on the back answers without picking the phone up.

<img src="docs/img/gif/facedown.gif" alt="The phone face-down; knock on the back when it buzzes">

</td>
</tr>
<tr>
<td valign="top">

**③ The server verifies, the flame lights.** Server-timed duration, every hash in the evidence chain, human touch and
phone-down. Then the session is recorded on Monad and the stake comes back with a bonus.

<img src="docs/img/gif/verified.gif" alt="Session verified: the flame lights and the outcome is recorded on-chain">

</td>
<td valign="top">

**④ Bring friends, catch the urge.** A squad shares one streak. Reach for a distracting app and FACEDOWN asks first.

<img src="docs/img/gif/squad.gif" alt="Squad: one streak together">
<img src="docs/img/gif/urge.gif" alt="Urge Catcher: FaceDown asks first">

</td>
</tr>
</table>

<table><tr>
<td width="150" align="center"><img src="app/src/main/res/drawable-nodpi/mascot_guard.webp" width="130" alt="Pip on guard"></td>
<td>

**Pip, on guard duty:** "People ask me: *can't you just cheat?* Try it. Here's what happens."

</td></tr></table>

| Attempt | Why it fails |
|---|---|
| Fake the timer / fast-forward | The **server** times the session from its own clock. Checkpoints that arrive early are rejected. |
| Use the phone during the session | Leaving the app is recorded in every checkpoint and fails the session. Picking the phone up breaks face-down stillness. |
| Let a script or an AI phone agent answer | Answers are knocks felt by the accelerometer (or a finger tap's jolt). Injected input moves nothing: production rejects it (`AUTOMATION_DETECTED`). **Measured on a real phone below.** |
| Replay or forge evidence | Checkpoints form a SHA-256 hash chain signed by a per-session Android Keystore key bound into the wallet's sign-in. |
| Farm sponsor pools with many wallets | One claim per **device identity** per mission per day, and a per-device daily reward cap. |
| Swap the stake transaction in the wallet | ATTEST submits the stake only if it moves exactly what was issued; wallets may add priority fees, nothing else. |
| Claim the payout twice | Payout and attestation are one transaction on a PDA that can be created once. |
| Record the same session twice | `PresenceRegistry` is create-once per (identity, mission, day, claim index). |
| An AI agent drains its owner's vault | `HumanGatedVault` reverts with `HumanPresenceRequired` above its limit unless the owner was present recently. |

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

## The business

- **Consumer (FACEDOWN):** sponsor-funded Free and No-Loss tiers for reach; streak freezes as a token sink and revenue;
  later a Pro tier and a small rake on opt-in Stake pools (where legal).
- **Infrastructure (PRESENCE):** proof-of-human-process per attestation, sold to airdrop issuers, quest platforms and
  ecosystem campaigns on Monad. "5 verified facedown sessions" is a quest a bot can't farm.
- **Sponsors:** parents funding a student's pool, companies funding employees' focus hours, protocols funding
  campaigns. Sponsor deposits are verified on-chain before they're credited.
- **No new token, no emissions.** Rewards are transfers from sponsor pools, so the economy can't inflate away.

## Roadmap (designed, not built)

- **P4 hardware attestation.** Android Key Attestation for the session key, so a phone that lies about its sensors is
  refused. Closes the biggest gap we measured.
- **Guardian quorum.** Several independent attestors instead of one operator, each re-checking the same evidence
  ([trust-model.md](docs/trust-model.md)).
- **Yield-funded No-Loss.** On mainnet, locked stakes earn yield (e.g. a lending vault on Monad) that funds the
  finisher reward instead of sponsors. Not built: no such vault on devnet to test against.
- **Presence Model.** Replace the hand-tuned knock/stillness thresholds with a small on-device classifier trained on
  real session traces (opt-in). Not built: we don't have the data yet, and a model without data would be theatre.
- **Presence Passport.** A monthly soulbound badge for verified presence that other apps read through `verifyPresence()`.
- **Urge Forecast.** Suggest Facedown Hours from the phone's own usage stats (the hours you lose most).
- **Pledge mode.** Quitting sends the stake to a cause you pick instead of locking it (needs legal review).

<table><tr>
<td width="150" align="center"><img src="app/src/main/res/drawable-nodpi/mascot_thinking.webp" width="130" alt="Pip thinking"></td>
<td>

**Pip, being honest:** "Read this before you quote us. We'd rather you hear the limits from us."

</td></tr></table>

## Honest limitations

- **One attestor today.** ATTEST signs every record; `PresenceRegistry` supports several attestors (`setAttestor`), and
  a quorum of independent attestors re-checking the same deterministic evidence is the next step.
- **Sensors can be faked by a privileged agent** ([ActReal](https://arxiv.org/abs/2608.30038)): motion evidence is a
  strong signal, not proof, until hardware key attestation is required.
- **Real knocks across many phones are not yet measured**; the 150 mm/s² floor is a design value.
- **The Android app now signs in with MetaMask** (MetaMask Android SDK, EIP-4361 + `personal_sign`, Monad testnet):
  [`releases/presence-monad.apk`](releases/presence-monad.apk). On Monad, missions carry no stake yet.
- **Testnet only**, development mode (device check bypassed and labelled), test tokens with no value. No customers yet.

## Run it

```bash
cd contracts && forge test                      # 10 tests: registry, presentDays, vault, AgentGuard with real P-256 sigs
cd backend && uv run pytest -q                  # 59 tests: protocol, policy, physical evidence, caps, Monad, MetaMask SIWE
# ATTEST anchored on Monad (key from your own .env.local, never committed):
MONAD_REGISTRY=0x5519dacface816519ACa3ee3cD592fd4c61dE52B MONAD_ATTESTOR_KEY=0x... ATTEST_DEV_MODE=true \
  uv run --project backend python -m attest.app          # then open http://127.0.0.1:8787/monad
# Knock to Approve also needs AGENT_GUARD=0xdC3Ba526FF565D0c76131D54a30aFA3c55041B11 and either PRIVY_AGENT_WALLET +
# PRIVY_APP_ID + PRIVY_APP_SECRET (agent = Privy server wallet) or AGENT_KEY (a local key); then open /agent
uv run --project backend python scripts/monad_demo.py session http://127.0.0.1:8787 device.key
```

## Credits

- **Film:** stock footage from [Pexels](https://www.pexels.com) (Pexels License); music "Mountains" by Andrew Ev
  ([Mixkit](https://mixkit.co) licence); voice by Kokoro-82M TTS (Apache-2.0). Every app screen is the real app.
- **Fonts:** Manrope and Inter (SIL OFL).
- **AI tools:** Claude Code (Anthropic) wrote much of the code, tests, docs, whitepaper and film under the team's
  direction and review.

## Team WARRIO

- **Aaditya** (iocryp15), lead · [@Aaditya1273](https://github.com/Aaditya1273)
- **nebula10**

<table><tr>
<td width="150" align="center"><img src="app/src/main/res/drawable-nodpi/mascot_champion.webp" width="130" alt="Pip celebrating"></td>
<td>

**Pip, signing off:** "Put it down. Prove it. Start with 2 minutes."

</td></tr></table>
