"""Anchor verified sessions in PresenceRegistry on Monad (EVM). The verdict is ATTEST's; Monad holds the
create-once record so any contract can call isPresent().

Identity and mission go on-chain as SHA-256 hashes: the registry never sees a wallet address or device id.

    MONAD_RPC_URL (default: testnet), MONAD_REGISTRY, MONAD_ATTESTOR_KEY (0x... hex; never commit it)
"""
import hashlib
import time

import httpx
from eth_account import Account
from eth_utils import keccak

TESTNET_RPC = "https://testnet-rpc.monad.xyz"
TESTNET_CHAIN_ID = 10143
EXPLORER = "https://testnet.monadvision.com/tx/"
RECORD = keccak(text="record(bytes32,bytes32,int64,uint8,bytes32,uint8)")[:4]


def h32(s: str) -> bytes:
    return hashlib.sha256(s.encode()).digest()


def record_calldata(identity: bytes, mission: bytes, day: int, seq: int, root: bytes, assurance: int) -> bytes:
    """ABI-encode record(...): six static 32-byte words."""
    word = lambda n: (n % (1 << 256)).to_bytes(32, "big")  # two's complement for int64
    return RECORD + identity + mission + word(day) + word(seq) + root + word(assurance)


class MonadAnchor:
    def __init__(self, registry: str, key: str, rpc_url: str = TESTNET_RPC, chain_id: int = TESTNET_CHAIN_ID,
                 timeout: float = 20):
        self.registry, self.rpc_url, self.chain_id, self.timeout = registry, rpc_url, chain_id, timeout
        self.account = Account.from_key(key)

    def _rpc(self, method: str, params: list):
        for wait in (0.5, 1, 2, 4, None):  # public RPCs answer 429 under load
            r = httpx.post(self.rpc_url, timeout=self.timeout, json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
            if r.status_code != 429 or wait is None:
                break
            time.sleep(wait)
        r.raise_for_status()
        body = r.json()
        if "error" in body:
            raise RuntimeError(body["error"].get("message", str(body["error"])))
        return body["result"]

    def anchor(self, identity_key: str, mission_id: str, day: int, seq: int, evidence_root: bytes, assurance: int) -> dict:
        data = record_calldata(h32(identity_key), h32(mission_id), day, seq, evidence_root, assurance)
        me = self.account.address
        call = {"from": me, "to": self.registry, "data": "0x" + data.hex()}
        gas = int(int(self._rpc("eth_estimateGas", [call, "latest"]), 16) * 1.2)  # Monad bills the gas limit: keep it tight
        base = int(self._rpc("eth_getBlockByNumber", ["latest", False])["baseFeePerGas"], 16)
        tip = int(self._rpc("eth_maxPriorityFeePerGas", []), 16)
        tx = {"type": 2, "chainId": self.chain_id, "nonce": int(self._rpc("eth_getTransactionCount", [me, "pending"]), 16),
              "to": self.registry, "data": data, "value": 0, "gas": gas,
              "maxPriorityFeePerGas": tip, "maxFeePerGas": base + tip}
        raw = self.account.sign_transaction(tx).raw_transaction
        tx_hash = self._rpc("eth_sendRawTransaction", ["0x" + raw.hex()])
        for _ in range(40):
            rcpt = self._rpc("eth_getTransactionReceipt", [tx_hash])
            if rcpt:
                ok = int(rcpt["status"], 16) == 1
                return {"status": "CONFIRMED" if ok else "REVERTED", "chain": "monad-testnet", "tx": tx_hash,
                        "registry": self.registry, "block": int(rcpt["blockNumber"], 16),
                        "explorer_url": EXPLORER + tx_hash}
            time.sleep(0.5)
        return {"status": "PENDING", "chain": "monad-testnet", "tx": tx_hash, "explorer_url": EXPLORER + tx_hash}
