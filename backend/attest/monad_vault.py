"""Stakes, sponsor rewards, refunds and freeze burns in MON through PresenceVault on Monad.

Same model as the Solana pool it replaces: users stake into the vault and sponsors fund it; only the ATTEST attestor
pays out (stake back + bonus, No-Loss refunds, Cool-down unlocks, free-session rewards) or burns. Each verified session
is also recorded once in PresenceRegistry. The wallet (MetaMask) sends the stake itself; ATTEST then checks the
transaction on-chain (right vault, right reference, right sender, exact amount) before accepting it.
"""
import json
import secrets
import threading
import time

from eth_account import Account
from eth_utils import keccak, to_checksum_address

from .monad import EXPLORER, TESTNET_CHAIN_ID, TESTNET_RPC, MonadAnchor
from .settlement import AttestationRecord, Deposit, Settlement

STAKE = keccak(text="stake(bytes32)")[:4]
FUND = keccak(text="fund()")[:4]
PAY = keccak(text="pay(address,uint256,bytes32)")[:4]
BURN = keccak(text="burn(uint256)")[:4]
STAKED = "0x" + keccak(text="Staked(bytes32,address,uint256)").hex()
FUNDED = "0x" + keccak(text="Funded(address,uint256)").hex()
NETWORK = "monad testnet"


def word(n: int) -> bytes: return n.to_bytes(32, "big")
def addr_word(a: str) -> bytes: return bytes(12) + bytes.fromhex(a[2:])


class MonadVault:
    reward_decimals = 18

    def __init__(self, vault: str, registry: str, key: str, rpc_url: str = TESTNET_RPC, chain_id: int = TESTNET_CHAIN_ID):
        self.vault = to_checksum_address(vault)
        self.anchor = MonadAnchor(registry, key, rpc_url, chain_id)
        self.account = Account.from_key(key)
        self.reward_mint = self.vault  # truthy: pools are enabled; amounts are MON (18 decimals)
        self.pool = self.vault
        self.network = NETWORK

    # ---- chain helpers
    def _rpc(self, method, params): return self.anchor._rpc(method, params)

    _lock = threading.Lock()  # one transaction at a time: concurrent sends would reuse the same nonce

    def _send(self, to: str, data: bytes, value: int = 0) -> str:
        with self._lock:
            return self._send_locked(to, data, value)

    def _send_locked(self, to: str, data: bytes, value: int = 0) -> str:
        me = self.account.address
        call = {"from": me, "to": to, "data": "0x" + data.hex(), "value": hex(value)}
        gas = int(int(self._rpc("eth_estimateGas", [call, "latest"]), 16) * 1.2)
        base = int(self._rpc("eth_getBlockByNumber", ["latest", False])["baseFeePerGas"], 16)
        tip = int(self._rpc("eth_maxPriorityFeePerGas", []), 16)
        tx = {"type": 2, "chainId": self.anchor.chain_id, "nonce": int(self._rpc("eth_getTransactionCount", [me, "pending"]), 16),
              "to": to, "data": data, "value": value, "gas": gas, "maxPriorityFeePerGas": tip, "maxFeePerGas": base + tip}
        h = self._rpc("eth_sendRawTransaction", ["0x" + self.account.sign_transaction(tx).raw_transaction.hex()])
        self._receipt(h)
        return h

    def _receipt(self, h: str, attempts: int = 60) -> dict:
        for _ in range(attempts):
            r = self._rpc("eth_getTransactionReceipt", [h])
            if r:
                if int(r["status"], 16) != 1:
                    raise RuntimeError(f"transaction {h} reverted")
                return r
            time.sleep(0.5)
        raise RuntimeError(f"transaction {h} not confirmed")

    def _log(self, rcpt: dict, topic0: str) -> list[dict]:
        return [l for l in rcpt["logs"] if l["address"].lower() == self.vault.lower() and l["topics"][0].lower() == topic0.lower()]

    # ---- Attestor interface used by the service
    def build_stake_tx(self, wallet: str, amount_base: int) -> bytes:
        """What the wallet must send: stake(ref) with exactly `amount_base` wei. The JSON is shown to the MetaMask page."""
        ref = "0x" + secrets.token_bytes(32).hex()
        return json.dumps({"to": self.vault, "data": "0x" + (STAKE + bytes.fromhex(ref[2:])).hex(),
                           "value": hex(amount_base), "from": wallet, "ref": ref, "chainId": hex(self.anchor.chain_id)}).encode()

    def submit_stake(self, signed_tx: bytes, unsigned_tx: bytes) -> str:
        """`signed_tx` is the transaction hash MetaMask returned. Accept it only if it staked exactly what we issued."""
        want = json.loads(unsigned_tx)
        tx_hash = signed_tx.decode().strip()
        rcpt = self._receipt(tx_hash)
        for l in self._log(rcpt, STAKED):
            if (l["topics"][1].lower() == want["ref"].lower()
                    and "0x" + l["topics"][2][-40:].lower() == want["from"].lower()
                    and int(l["data"], 16) == int(want["value"], 16)):
                return tx_hash
        raise RuntimeError("transaction did not stake the issued amount into the vault")

    def settle(self, rec: AttestationRecord) -> Settlement:
        identity = rec.sgt_mint or f"dev:{rec.wallet}"
        reg = self.anchor.anchor(identity, rec.mission_id, rec.day, rec.seq, bytes(rec.evidence_root), rec.assurance_level)
        if reg["status"] != "CONFIRMED":
            return Settlement(status="FAILED", network=NETWORK, detail=f"registry {reg['status']}")
        if rec.reward_base:
            self._send(self.vault, PAY + addr_word(rec.wallet) + word(rec.reward_base) + bytes(32))
        return Settlement(status="CONFIRMED", signature=reg["tx"], network=NETWORK, reward_base=rec.reward_base,
                          reward_mint=self.vault)

    def refund(self, wallet: str, amount_base: int) -> str:
        return self._send(self.vault, PAY + addr_word(wallet) + word(amount_base) + bytes(32))

    def burn(self, amount_base: int) -> str:
        return self._send(self.vault, BURN + word(amount_base))

    def verify_deposit(self, signature: str) -> Deposit:
        rcpt = self._receipt(signature)
        logs = self._log(rcpt, FUNDED)
        if not logs:
            raise RuntimeError("transaction did not fund the vault")
        return Deposit(amount_base=sum(int(l["data"], 16) for l in logs), depositor="0x" + logs[0]["topics"][1][-40:])

    def fund(self, amount_base: int) -> str:
        return self._send(self.vault, FUND, amount_base)

    def faucet(self, wallet: str, lamports: int, amount_base: int) -> str:
        """DEVELOPMENT ONLY: a little testnet MON so a new MetaMask wallet can pay gas and stake."""
        return self._send(to_checksum_address(wallet), b"", 10 ** 17)  # 0.1 MON


def explorer(tx: str) -> str: return EXPLORER + tx
