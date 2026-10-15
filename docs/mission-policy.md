# Mission policy

Policies are data, validated by `policy.build_policy` into the typed `MissionPolicy`. The live server loads
`backend/missions_monad.json` (`ATTEST_MISSIONS` in `render.yaml`). All rules are evaluated in one function,
`policy.evaluate`. The app renders whatever `/missions` returns.

## Shipped missions (`missions_monad.json`)

All amounts are testnet MON with no value. Every mission requires P2, one answered check-in per window, the phone
still at least 60% of each window, a check-in jolt of at least 150 mm/s², and forgives one missed check-in
(`allowed_misses: 1`).

| Mission | Session | Check-ins | Claims/day | Mode | Stake | Reward on finish |
|---|---|---|---|---|---|---|
| `quick-free` | 2 min | 4 (one per 30 s) | 1 | Free | — | 0.005 MON from the sponsor pool, 60 XP |
| `quick-noloss` | 2 min | 4 (one per 30 s) | 3 | No-Loss | 0.05 MON, returned either way | 0.005 MON from the sponsor pool, 60 XP |
| `quick-clock-in` | 2 min | 4 (one per 30 s) | 3 | Stake (cool-down) | 0.05 MON | stake back + up to 20% bonus from the pool, 60 XP |
| `urge-break` | 10 min | 2 (one per 5 min) | 3 | Free | — | 0.01 MON, 100 XP |
| `deep-free-25` | 25 min | 5 (one per 5 min) | 1 | Free | — | 0.02 MON, 300 XP |
| `deep-focus-25` | 25 min | 5 (one per 5 min) | 1 | Stake (cool-down) | 0.2 MON | stake back + up to 20% bonus, 300 XP |
| `facedown-hour-free` | 60 min | 4 (one per 15 min) | 1 | Free | — | 0.05 MON, 600 XP |
| `facedown-hour-noloss` | 60 min | 4 (one per 15 min) | 1 | No-Loss | 0.2 MON, returned either way | 0.05 MON, 600 XP |
| `facedown-hour` | 60 min | 4 (one per 15 min) | 1 | Stake (cool-down) | 0.2 MON | stake back + up to 20% bonus, 600 XP |

Each window's check-in appears at a random moment 15–60% into the window. The phone buzzes and the user knocks on its
back (or taps *I'm here*).

- **Free:** no stake. A finisher gets the reward if the mission's sponsor pool covers it.
- **No-Loss:** the stake is sent to `PresenceVault` with `stake(ref)` and comes back whether the session passes or
  not; only finishers get the reward.
- **Stake (cool-down):** finish and get the stake back plus `stake × bonus_rate`, capped by what the mission's pool
  holds. Quit (leave the app, miss more check-ins than allowed, or fail a rule) and the stake is locked for 7 days
  (14, then 30 for repeat quits within 30 days), then returned. It is never given to anyone else.
- **Daily cap:** sponsor rewards and bonuses for one device identity are capped per UTC day
  (`DAILY_REWARD_CAP_PER_DEVICE`, 0.1 MON on the live server); settlement pays `min(reward, cap left today)`. Stakes
  don't count against the cap.

```json
{
  "mission_id": "quick-noloss",
  "required_assurance": "P2",
  "duration_seconds": 120, "required_checkpoints": 4, "checkpoint_interval_seconds": 30,
  "min_interactions_per_checkpoint": 1, "max_claims_per_identity_per_day": 3,
  "reward": { "xp": 60, "token": 0.005, "symbol": "MON" },
  "stake": { "token": 0.05, "bonus_rate": 0, "no_loss": true },
  "min_still_pct": 60, "min_touch_motion": 150, "allowed_misses": 1
}
```

Sessions expire `duration + 300 s` after creation. An abandoned No-Loss stake is refunded, and an abandoned cool-down
stake is locked, the next time ATTEST sweeps stakes (on each new session).

## Rules enforced

Nine rules in `policy.evaluate`, plus the claim limit in the service:

| # | Rule | Measured by | Failure |
|---|---|---|---|
| 1 | achieved assurance ≥ `required_assurance` | service | `POLICY_NOT_SATISFIED` |
| 2 | checkpoint count ≥ `required_checkpoints` | server | `POLICY_NOT_SATISFIED` |
| 3 | `submitted_at - started_at ≥ duration_seconds - tolerance` | server clock | `POLICY_NOT_SATISFIED` |
| 4 | each checkpoint arrives ≥ `interval - tolerance` after the previous | server clock | `POLICY_NOT_SATISFIED` |
| 5 | every window stayed in the foreground | evidence | `POLICY_NOT_SATISFIED` |
| 6 | answered check-ins per window ≥ minimum, with at most `allowed_misses` windows short | evidence | `POLICY_NOT_SATISFIED` |
| 7 | check-in answer times look human ([security.md](security.md#automation-heuristics)) | evidence | `AUTOMATION_DETECTED` |
| 8 | each answered check-in jolted the phone ≥ `min_touch_motion` (human touch) | evidence | `AUTOMATION_DETECTED` |
| 9 | the phone lay still ≥ `min_still_pct` of each window (phone down) | evidence | `POLICY_NOT_SATISFIED` |
| — | claims today for this identity and mission < max | claim registry | `ALREADY_CLAIMED` |

`timing_tolerance_seconds` defaults to 3. Every violated rule among 1–6 is listed in `detail`. Rules 8 and 9 are
enforced in production and reported as `FLAGGED` on the receipt in development mode.

## Load-time validation (`build_policy`)

- unknown assurance level, or `witness_required: true` (P3 not implemented): refused
- `duration_seconds` must equal `required_checkpoints × checkpoint_interval_seconds`; interval ≥ 5 s
- `allowed_misses` must leave at least one answered check-in
- `max_claims_per_identity_per_day` in 1..255 (it becomes the `uint8 seq` in `PresenceRegistry.record`)
- `no_loss` needs a stake and no bonus; `cooldown` needs a stake and excludes `no_loss`
- `bonus_rate` in 0..1; `min_still_pct` in 0..100; no negative rewards, stakes or motion floors

## Drafting missions from plain language

`POST /missions/generate {"prompt": "Stay 2 minutes with 4 checks, 0.005 MON reward"}` returns a **draft for
review**, never installed automatically.
- **Default:** a deterministic parser (`mission_generator.parse`), with no network and the same output every time.
- **Optional:** with `OPENAI_API_KEY` set, an LLM drafts the JSON instead. The LLM path is untested here because no key was available.
- **Either way:** the draft must pass `build_policy`, so a generated policy can't ask for something the engine wouldn't enforce.
