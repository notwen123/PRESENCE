# Demo script (about 3 minutes, on a real phone held by a person)

Setup: the live ATTEST server at `https://presence-h7uy.onrender.com` (development mode, Monad testnet; it is on a
free plan, so open `/health` a minute before filming to wake it). The FACEDOWN APK built with
`-Ppresence.backendUrl=https://presence-h7uy.onrender.com -Ppresence.privyAppId=… -Ppresence.privyClientId=…`
(public Privy IDs; without them the app falls back to MetaMask). A Google account on the phone. A second tab open on
`https://testnet.monadvision.com`. Film the phone and a table.

| # | Show | Say |
|---|---|---|
| 1 | Today: *Put it down. Prove it.*, 2 / 10 / 25 / 60 min, Free / No-Loss / Stake | "AI agents can tap screens now. PRESENCE proves a real person physically did something on a real phone, and records it on Monad. FACEDOWN is the first app on it." |
| 2 | *Continue with Google* → Privy sign-in → wallet chip shows the new address | "Google sign-in through Privy creates an embedded wallet on Monad. No seed phrase, no other app. In development the server sends it 0.1 testnet MON for gas." |
| 3 | Pick 2 min + **No-Loss** → confirm screen shows 0.05 MON | "No-Loss: I lock 0.05 MON and get it back either way. Finish, and a sponsor pays 0.005 MON." |
| 4 | Approve: the embedded wallet signs the sign-in and the stake in-app | "The wallet signs the sign-in message and the stake transaction right here; the app sends the stake to PresenceVault on Monad, and the server checks it moved exactly 0.05 MON." |
| 5 | Countdown → **turn the phone face-down on the table**: the screen goes black | "No lit screen, no pickup. The accelerometer reports that it's face-down and still. The server times the session on its own clock." |
| 6 | It buzzes → **knock on the back** (show your hand); repeat for each check-in | "A knock answers the check-in. A script or an AI agent can tap a screen; it can't knock on a table." |
| 7 | Pick up after the session → receipt: human touch + phone-down verified, stake back + 0.005 MON | "The server re-checked every hash and every rule." |
| 8 | Tap the receipt's Monad link → the `record` transaction on MonadVision | "One transaction in PresenceRegistry. Any contract can now ask `isPresent()` for this identity. Only hashes go on-chain." |
| 9 | Open `https://presence-h7uy.onrender.com/agent` in Chrome on the phone → *Set up this phone* | "Now the agent side. The browser makes a P-256 key that never leaves the phone and binds it to AgentGuard." |
| 10 | *Agent sends 0.005 MON* → sent | "The agent is a Privy server wallet. Under 0.01 MON it spends alone." |
| 11 | *Agent sends 0.03 MON* → *Knock to approve*, 120 s countdown | "Over the limit: held on-chain, waiting for me." |
| 12 | Tap *Start*, phone face-down, **two knocks** → approved, sent, presence recorded | "The phone signed this exact transfer. AgentGuard checked the signature itself through Monad's P-256 precompile. The server can't forge it, and Privy's policy won't let the agent sign a transfer to anywhere else." |

Say plainly on camera: this is Monad testnet and MON here has no value; the server runs in development mode (the device
check is bypassed and labelled, and the physical rules are reported rather than enforced); one server is the only
attestor today; and the web-browser knock is weaker evidence than the app, so it is recorded at assurance 1.

Optional extra: Progress → *Buy a streak freeze* → toast "Streak freeze added. 0.01 MON burned on Monad." (price
0.02 MON, half burned).

Reference transactions from a real run are in `contracts/deployments/monad-testnet.json` and the README.

Automated versions, against any running ATTEST:
`uv run --project backend python scripts/monad_demo.py session https://presence-h7uy.onrender.com device.key` (a
simulated client session, recorded on Monad) and `ATTEST_URL=https://presence-h7uy.onrender.com python
backend/scripts/knock_e2e.py` (Knock to Approve, including a forged signature that reverts). Both spend a little
testnet MON from the server's wallets.
