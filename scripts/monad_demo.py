"""End-to-end PRESENCE on Monad: a real verified session unlocks an agent's large withdrawal from HumanGatedVault.

    # ATTEST running with MONAD_REGISTRY + MONAD_ATTESTOR_KEY set, e.g. on :8791
    uv run --project backend python scripts/monad_demo.py session http://127.0.0.1:8791 demo_device.key
    uv run --project backend python scripts/monad_demo.py identity demo_device.key   # -> bytes32 for the vault

The device key file is created on first use (a throwaway test wallet). Prints every on-chain artifact.
"""
import hashlib, os, sys, time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
import httpx
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from tests.sim import SimDevice, SimSession


def device(path: str) -> SimDevice:
    d = SimDevice()
    if os.path.exists(path):
        d.wallet_key = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(open(path).read().strip()))
    else:
        open(path, "w").write(d.wallet_key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                                                         serialization.NoEncryption()).hex())
    return d


def identity(d: SimDevice) -> str:
    """The PRESENCE identity in development mode is dev:<wallet>; on-chain it is its SHA-256."""
    return "0x" + hashlib.sha256(f"dev:{d.wallet}".encode()).hexdigest()


def session(base: str, d: SimDevice, mission_id: str = "quick-free") -> dict:
    c = httpx.Client(base_url=base, timeout=120)
    ok = lambda r: r.json() if r.status_code < 400 else sys.exit(f"{r.request.url.path}: {r.status_code} {r.text}")
    mission = next(m for m in ok(c.get("/missions")) if m["mission_id"] == mission_id)
    s = ok(c.post("/session", json={"wallet": d.wallet, "mission_id": mission_id, "ephemeral_pubkey": d.ephemeral_pubkey}))
    print("session", s["session_id"], "wallet", d.wallet)
    ok(c.post(f"/session/{s['session_id']}/authorize", json={"signature": d.sign_wallet(s["siws_message"])}))
    sess = SimSession(d, s["session_id"], s["nonce"])
    for i in range(mission["required_checkpoints"]):
        time.sleep(mission["checkpoint_interval_seconds"])
        ok(c.post(f"/session/{sess.session_id}/checkpoint", json=sess.next_checkpoint(int(time.time() * 1000))))
        print(f"checkpoint {i + 1}/{mission['required_checkpoints']}")
    ok(c.post(f"/session/{sess.session_id}/evidence", json=sess.evidence()))
    return ok(c.post("/verify", json={"session_id": sess.session_id}))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "identity":
        print(identity(device(sys.argv[2])))
    elif cmd == "session":
        r = session(sys.argv[2], device(sys.argv[3]))
        print("verified:", r["verified"], "| monad:", r["receipt"].get("monad"))
