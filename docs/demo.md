# Demo script (about 3 minutes, on a real phone held by a person)

Setup: the devnet ATTEST server (`.devnet/attest.env` + `ATTEST_DEV_MODE=true`), the phone on USB with
`adb reverse tcp:8787 tcp:8787`, the app built with `-Ppresence.backendUrl=http://127.0.0.1:8787`, and a wallet
(Solflare on devnet, backed up, or Solana Mobile's test wallet). Film the phone and a table.

| # | Show | Say |
|---|---|---|
| 1 | Today: *Put it down. Prove it.*, 2 / 25 / 60 min, Free / No-Loss / Stake | "Seeker is the only phone that can prove you were present. FACEDOWN turns phone-free time into something you can commit to and get paid for." |
| 2 | Pick 2 min + No-Loss → confirm screen | "No-Loss: you lock SKR and get it back either way. Finish and a sponsor pays you. Nobody loses principal." |
| 3 | Wallet: approve sign-in and the lock | "One wallet session signs the sign-in and the stake. The server only submits the stake it issued." |
| 4 | Countdown → **turn the phone face-down on the table**: the screen goes black | "No lit screen, no pickup. The accelerometer proves it's face-down and still." |
| 5 | It buzzes → **knock on the back** (show your hand) | "A knock answers the check-in. A script or an AI agent can tap a screen; it can't knock on a table." |
| 6 | Pick up after the session → receipt: human touch + phone-down verified, recorded on Solana, QR | "Attestation and payout are one Solana transaction. Scan it." |
| 7 | Progress → *Buy a streak freeze* → toast "1 SKR-TEST burned" | "Streak freezes cost SKR and half is burned on-chain: a real SKR sink." |
| 8 | Second run with Stake: leave the app → *stake went to the pool* | "Stake mode is for the committed: quit and it funds everyone who finished." |

Say plainly on camera: development mode (this phone isn't a Seeker, so Seeker eligibility is bypassed and labeled);
SKR-TEST is a valueless test mint; the mainnet switch is pointing `REWARD_MINT` at SKR.

Automated version (emulator: `adb emu sensor set acceleration 0:0:-9.81` is face-down):
`python3 scripts/device_e2e.py <adb-serial> happy free leave`
