"""Knock to Approve: an AI agent's large transfer waits for its human to knock on their phone.

The phone (the /agent page, any phone browser) holds a P-256 key. The agent asks AgentGuard to send; a transfer above
the limit becomes a pending intent. The phone, face-down, feels a knock, signs sha256(intentId) and ATTEST relays it:
AgentGuard verifies the P-256 signature itself (Monad precompile 0x100) and pays. ATTEST also records the presence in
PresenceRegistry. Web sensors are weaker evidence than the native app, so these records use assurance level 1.

With PRIVY_AGENT_WALLET set, the agent is a Privy server wallet whose Privy policy only lets it sign transactions to
AgentGuard on Monad: Privy bounds *where* the agent can send, AgentGuard bounds *how much* it can send without a human.
"""
import os
import hashlib
import threading
import time
from pathlib import Path

import httpx
from eth_account import Account
from eth_utils import keccak, to_checksum_address
from fastapi import FastAPI
from fastapi.responses import FileResponse
from pydantic import BaseModel

from .errors import AttestError, Reason
from .monad import EXPLORER, MonadAnchor

SEL = {n: keccak(text=n)[:4] for n in ("bindDevice(bytes32,bytes32)", "send(bytes32,address,uint256)",
                                        "approve(bytes32,bytes32,bytes32)", "keys(bytes32)")}
INTENT = "0x" + keccak(text="IntentRequested(bytes32,bytes32,address,uint256,uint64)").hex()
SENT = "0x" + keccak(text="Sent(bytes32,address,uint256,bytes32,bool)").hex()
MIN_PEAK = 1.2      # m/s^2: a knock on a phone lying on a table
MIN_FACEDOWN_MS = 800


def b32(h: str) -> bytes:
    b = bytes.fromhex(h.removeprefix("0x"))
    if len(b) != 32: raise AttestError(Reason.INVALID_IDENTITY, "expected 32 bytes")
    return b


class Sender:
    """Signs and sends from one key; one transaction at a time so nonces never collide."""
    def __init__(self, anchor: MonadAnchor, key: str):
        self.anchor, self.account, self.lock = anchor, Account.from_key(key), threading.Lock()

    def send(self, to: str, data: bytes, value: int = 0) -> dict:
        rpc, me = self.anchor._rpc, self.account.address
        with self.lock:
            call = {"from": me, "to": to, "data": "0x" + data.hex(), "value": hex(value)}
            gas = int(int(rpc("eth_estimateGas", [call, "latest"]), 16) * 1.1)  # Monad bills the gas limit, keep it tight
            base = int(rpc("eth_getBlockByNumber", ["latest", False])["baseFeePerGas"], 16)
            tip = int(rpc("eth_maxPriorityFeePerGas", []), 16)
            tx = {"type": 2, "chainId": self.anchor.chain_id, "nonce": int(rpc("eth_getTransactionCount", [me, "pending"]), 16),
                  "to": to, "data": data, "value": value, "gas": gas, "maxPriorityFeePerGas": tip, "maxFeePerGas": base + tip}
            h = rpc("eth_sendRawTransaction", [self._sign(tx)])
        for _ in range(60):
            r = rpc("eth_getTransactionReceipt", [h])
            if r:
                if int(r["status"], 16) != 1: raise AttestError(Reason.INVALID_STATE, f"transaction reverted: {h}")
                return r
            time.sleep(0.5)
        raise AttestError(Reason.NETWORK_ERROR, f"transaction not confirmed: {h}")

    def _sign(self, tx: dict) -> str:
        return "0x" + self.account.sign_transaction(tx).raw_transaction.hex()


class PrivySender(Sender):
    """The agent's key lives in Privy (a server wallet); Privy signs only what the wallet's policy allows."""
    def __init__(self, anchor: MonadAnchor, wallet_id: str, app_id: str, app_secret: str):
        self.anchor, self.lock, self.wallet_id = anchor, threading.Lock(), wallet_id
        self.http = httpx.Client(base_url="https://api.privy.io/v1", auth=(app_id, app_secret),
                                 headers={"privy-app-id": app_id}, timeout=30)
        r = self.http.get(f"/wallets/{wallet_id}"); r.raise_for_status()
        self.account = type("PrivyAccount", (), {"address": to_checksum_address(r.json()["address"])})()

    def _sign(self, tx: dict) -> str:
        body = {"to": tx["to"], "data": "0x" + tx["data"].hex(), "value": hex(tx["value"]), "chain_id": tx["chainId"],
                "type": 2, "nonce": tx["nonce"], "gas_limit": hex(tx["gas"]), "max_fee_per_gas": hex(tx["maxFeePerGas"]),
                "max_priority_fee_per_gas": hex(tx["maxPriorityFeePerGas"])}
        r = self.http.post(f"/wallets/{self.wallet_id}/rpc", json={"method": "eth_signTransaction", "params": {"transaction": body}})
        if r.status_code != 200:
            raise AttestError(Reason.INVALID_STATE, f"Privy refused to sign: {r.json().get('error', r.status_code)}")
        return r.json()["data"]["signed_transaction"]


class Bind(BaseModel):
    x: str
    y: str


class Request(BaseModel):
    account: str
    amount: float  # MON


class Approve(BaseModel):
    intent_id: str
    r: str
    s: str
    face_down_ms: int
    peak: float
    knocks: int = 1


def mount(app: FastAPI, svc, guard: str, agent_key: str | None, anchor: MonadAnchor, attestor_key: str) -> None:
    guard = to_checksum_address(guard)
    attestor = getattr(svc.attestor, "_send", None)  # share the vault's sender (same key, same nonce lock)
    att = Sender(anchor, attestor_key) if attestor is None else None
    def as_attestor(data: bytes) -> dict:
        try:
            if att: return att.send(guard, data)
            return anchor._rpc("eth_getTransactionReceipt", [svc.attestor._send(guard, data)])
        except RuntimeError as e:  # e.g. AgentGuard rejected the signature: execution reverted
            raise AttestError(Reason.INVALID_STATE, f"Monad rejected it: {e}")
    privy = os.environ.get("PRIVY_AGENT_WALLET")
    agent = (PrivySender(anchor, privy, os.environ["PRIVY_APP_ID"], os.environ["PRIVY_APP_SECRET"]) if privy
             else Sender(anchor, agent_key))
    recipient = Account.from_key(attestor_key).address  # demo payee: the project treasury, so funds cycle back
    intents: dict[str, dict] = {}
    approvals_today: dict[str, int] = {}

    @app.get("/agent", include_in_schema=False)
    def page():
        return FileResponse(Path(__file__).parent / "static" / "agent.html")

    @app.get("/agent/config")
    def config():
        return {"guard": guard, "agent": agent.account.address, "agent_wallet": "privy" if privy else "local key",
                "recipient": recipient, "limit_mon": 0.01,
                "registry": anchor.registry, "explorer": EXPLORER}

    @app.post("/agent/bind")
    def bind(b: Bind):
        x, y = b32(b.x), b32(b.y)
        account = "0x" + keccak(x + y).hex()
        got = anchor._rpc("eth_call", [{"to": guard, "data": "0x" + (SEL["keys(bytes32)"] + bytes.fromhex(account[2:])).hex()}, "latest"])
        if int(got[2:66] or "0", 16) == 0:
            r = as_attestor(SEL["bindDevice(bytes32,bytes32)"] + x + y)
            return {"account": account, "tx": r["transactionHash"], "bound": True}
        return {"account": account, "tx": None, "bound": True}

    @app.post("/agent/request")
    def request(q: Request):
        if not 0 < q.amount <= 0.05:
            raise AttestError(Reason.INVALID_STATE, "demo amounts are 0..0.05 MON")
        wei = round(q.amount * 10 ** 18)
        try:
            r = agent.send(guard, SEL["send(bytes32,address,uint256)"] + b32(q.account) + bytes(12) + bytes.fromhex(recipient[2:]) + wei.to_bytes(32, "big"))
        except RuntimeError as e:  # e.g. the agent's wallet is out of gas, or the guard is out of MON
            raise AttestError(Reason.NETWORK_ERROR, f"agent transaction failed: {e}")
        for l in r["logs"]:
            if l["topics"][0].lower() == INTENT.lower():
                iid = l["topics"][1]; deadline = int(l["data"][66:130], 16)
                intents[iid] = {"intent_id": iid, "account": q.account, "to": recipient, "amount": q.amount,
                                "deadline": deadline, "status": "WAITING_FOR_KNOCK", "request_tx": r["transactionHash"]}
                return intents[iid]
        return {"status": "SENT_WITHOUT_APPROVAL", "amount": q.amount, "to": recipient, "tx": r["transactionHash"]}

    @app.get("/agent/intent/{intent_id}")
    def intent(intent_id: str):
        if intent_id not in intents: raise AttestError(Reason.SESSION_NOT_FOUND, "unknown intent")
        return intents[intent_id]

    @app.post("/agent/approve")
    def approve(a: Approve):
        it = intents.get(a.intent_id)
        if not it or it["status"] != "WAITING_FOR_KNOCK":
            raise AttestError(Reason.INVALID_STATE, "no pending intent")
        if a.face_down_ms < MIN_FACEDOWN_MS or a.peak < MIN_PEAK:
            raise AttestError(Reason.AUTOMATION_DETECTED, f"no physical knock: face-down {a.face_down_ms} ms, peak {a.peak:.2f} m/s^2")
        r = as_attestor(SEL["approve(bytes32,bytes32,bytes32)"] + b32(a.intent_id) + b32(a.r) + b32(a.s))
        it.update(status="APPROVED_AND_SENT", approve_tx=r["transactionHash"])
        try:  # the knock is also a (web, lower-assurance) presence record
            day = int(time.time() // 86400); key = f"{it['account']}:{day}"
            seq = approvals_today.get(key, 0); approvals_today[key] = seq + 1
            root = hashlib.sha256(b32(a.intent_id) + b32(a.r) + b32(a.s)).digest()
            rec = anchor.anchor(f"agent:{it['account']}", "knock-to-approve", day, seq % 256, root, 1)
            it["presence_tx"] = rec.get("tx")
        except Exception as e:
            it["presence_error"] = str(e)[:120]
        return it
