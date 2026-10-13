"""Monad anchoring: calldata matches the Solidity ABI; verified sessions are recorded with hashed ids; a Monad
failure never undoes the Solana settlement."""
from eth_abi import encode

from attest.monad import RECORD, h32, record_calldata

from .conftest import run_process, start, submit_and_verify


def test_calldata_matches_the_solidity_abi():
    ident, mission, root = h32("sgt:abc"), h32("quick-free"), bytes(range(32))
    for day in (20_740, -1):
        assert record_calldata(ident, mission, day, 2, root, 2) == RECORD + encode(
            ["bytes32", "bytes32", "int64", "uint8", "bytes32", "uint8"], [ident, mission, day, 2, root, 2])


class FakeMonad:
    def __init__(self, fail=False): self.calls, self.fail = [], fail

    def anchor(self, identity_key, mission_id, day, seq, root, assurance):
        if self.fail: raise RuntimeError("rpc down")
        self.calls.append((identity_key, mission_id, day, seq, root, assurance))
        return {"status": "CONFIRMED", "chain": "monad-testnet", "tx": "0xabc"}


def test_verified_session_is_anchored_on_monad(api, svc, clock, device):
    svc.monad = FakeMonad()
    sess = start(api, device); run_process(api, sess, clock)
    body = submit_and_verify(api, sess).json()
    assert body["receipt"]["monad"]["status"] == "CONFIRMED"
    ident, mission, _, seq, root, assurance = svc.monad.calls[0]
    assert ident == f"dev:{device.wallet}" and seq == 0 and assurance == 2 and len(root) == 32
    assert api.get(f"/session/{sess.session_id}/receipt").json()["monad"]["tx"] == "0xabc"


def test_monad_failure_never_undoes_settlement(api, svc, clock, device):
    svc.monad = FakeMonad(fail=True)
    sess = start(api, device); run_process(api, sess, clock)
    rc = submit_and_verify(api, sess).json()["receipt"]
    assert rc["settlement"]["status"] == "CONFIRMED" and rc["monad"]["status"] == "FAILED"
