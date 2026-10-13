"""Live Knock to Approve check against a running ATTEST (default http://127.0.0.1:8791): bind, small send, blocked large send,
forged signature rejected on-chain, real P-256 approval. Spends a little testnet MON."""
import os
import httpx
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
B = os.environ.get("ATTEST_URL", "http://127.0.0.1:8791")
k = ec.generate_private_key(ec.SECP256R1()); n = k.public_key().public_numbers()
c = httpx.Client(timeout=120)
b = c.post(f"{B}/agent/bind", json={"x": "0x%064x" % n.x, "y": "0x%064x" % n.y}).json(); print("bind", b)
s = c.post(f"{B}/agent/request", json={"account": b["account"], "amount": 0.005}).json(); print("small", s)
i = c.post(f"{B}/agent/request", json={"account": b["account"], "amount": 0.03}).json(); print("big", i)
bad = c.post(f"{B}/agent/approve", json={"intent_id": i["intent_id"], "r": "0x"+"11"*32, "s": "0x"+"22"*32, "face_down_ms": 1000, "peak": 2}).json(); print("forged ->", bad)
r, s_ = decode_dss_signature(k.sign(bytes.fromhex(i["intent_id"][2:]), ec.ECDSA(hashes.SHA256())))
print("approve", c.post(f"{B}/agent/approve", json={"intent_id": i["intent_id"], "r": "0x%064x" % r, "s": "0x%064x" % s_, "face_down_ms": 1500, "peak": 3.1, "knocks": 2}).json())
