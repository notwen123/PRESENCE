"""DEVELOPMENT ONLY (devnet/localnet): re-fund each mission's test pool when the server starts on an empty database.

Free hosts reset their disk on restart, which empties the pool ledger, so sponsored sessions would earn nothing.
The devnet attestor is the SKR-TEST mint authority: it mints test tokens into its own pool and credits each
mint like a sponsor deposit (the same balance check as a real deposit). Refuses to run on mainnet or outside
development mode; real SKR can't be minted by us anyway.

    uv run python -m attest.devseed
"""
import struct

from solders.instruction import AccountMeta, Instruction

from .app import build_service
from .config import Settings
from .settlement import TOKEN_PROGRAM, SolanaAttestor

PER_MISSION = 20  # test tokens per mission per fresh start


def main() -> None:
    s = Settings.from_env()
    if s.network == "mainnet" or not s.dev_mode:
        raise SystemExit("devseed runs only in development mode off mainnet")
    svc = build_service(s)
    a = svc.attestor
    if not isinstance(a, SolanaAttestor) or not a.reward_mint:
        return print("devseed: no reward pool configured")
    if svc.db.execute("SELECT 1 FROM sponsor_deposits LIMIT 1").fetchone():
        return print("devseed: pools already funded")
    amount = PER_MISSION * 10 ** a.reward_decimals
    for mission_id in svc.missions:
        try:  # best effort: an RPC hiccup must never stop the server from starting
            sig = a.send(Instruction(TOKEN_PROGRAM, bytes([7]) + struct.pack("<Q", amount), [
                AccountMeta(a.reward_mint, is_signer=False, is_writable=True),
                AccountMeta(a.pool, is_signer=False, is_writable=True),
                AccountMeta(a.keypair.pubkey(), is_signer=True, is_writable=False)]))
            a.confirm(sig)
            svc.sponsor_deposit(mission_id, sig)
            print(f"devseed: {mission_id} +{PER_MISSION} test tokens ({sig})")
        except Exception as e:
            print(f"devseed: {mission_id} not funded ({e})")


if __name__ == "__main__":
    main()
