"""HTTP surface of the ATTEST engine. Routes only translate HTTP <-> service calls."""

import os
import logging
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from . import identity, mission_generator, store
from .config import Settings
from .errors import HTTP_STATUS, AttestError, Reason
from .evidence import CheckpointData
from .policy import load_missions
from .service import Attest
from .settlement import SolanaAttestor, UnconfiguredAttestor


class CreateSession(BaseModel):
    wallet: str
    mission_id: str
    ephemeral_pubkey: str = Field(description="base64 X.509 DER, EC P-256")


class Authorize(BaseModel):
    signature: str = Field(description="base58 ed25519 signature of siws_message by the wallet")
    stake_transaction: str | None = Field(None, description="base64 stake transaction from /session, signed by the wallet")


class Checkpoint(BaseModel):
    index: int = Field(ge=0, le=1000)
    timestamp_ms: int
    elapsed_ms: int = Field(ge=0)
    foreground: bool
    interactions: int = Field(ge=0, le=100_000)
    response_ms: int = Field(ge=-1, le=600_000)
    still_pct: int = Field(-1, ge=-1, le=100)
    touch_motion: int = Field(-1, ge=-1, le=1_000_000)
    hash: str = Field(pattern="^[0-9a-f]{64}$")
    signature: str = Field(description="base64 DER ECDSA(P-256, SHA-256) over the raw hash bytes")


class Evidence(BaseModel):
    evidence_root: str = Field(pattern="^[0-9a-f]{64}$")
    signature: str


class Verify(BaseModel):
    session_id: str


class SponsorDeposit(BaseModel):
    mission_id: str
    signature: str = Field(description="confirmed transaction that moved REWARD_MINT into the pool account")


class Faucet(BaseModel):
    wallet: str


class SquadCreate(BaseModel):
    wallet: str
    name: str = Field("Squad", max_length=40)


class SquadJoin(BaseModel):
    wallet: str
    code: str = Field(min_length=6, max_length=6)


class GenerateMission(BaseModel):
    prompt: str = Field(min_length=5, max_length=1000)


def build_service(s: Settings) -> Attest:
    sgt = identity.DevSgtVerifier() if s.dev_mode else identity.RpcSgtVerifier(s.sgt_rpc_url)
    attestor = (SolanaAttestor(s.solana_rpc_url, s.network, s.program_id, Path(s.attestor_keypair),
                               reward_mint=s.reward_mint, reward_decimals=s.reward_decimals)
                if s.program_id and s.attestor_keypair else UnconfiguredAttestor())
    monad = None
    if os.environ.get("MONAD_REGISTRY") and os.environ.get("MONAD_ATTESTOR_KEY"):
        from .monad import TESTNET_RPC, MonadAnchor
        monad = MonadAnchor(os.environ["MONAD_REGISTRY"], os.environ["MONAD_ATTESTOR_KEY"],
                            os.environ.get("MONAD_RPC_URL", TESTNET_RPC))
    return Attest(store.connect(s.db_path), load_missions(s.missions_path), sgt, attestor,
                  domain=s.domain, chain_id=s.chain_id, dev_mode=s.dev_mode, network=s.network,
                  ttl_seconds=s.session_ttl_seconds, enforce_physical=s.enforce_physical, monad=monad)


class LinkBody(BaseModel):
    address: str
    signature: str


def create_app(service: Attest | None = None) -> FastAPI:
    svc = service or build_service(Settings.from_env())
    app = FastAPI(title="ATTEST Engine", version="0.1.0")

    @app.exception_handler(AttestError)
    async def attest_error(_: Request, e: AttestError):
        return JSONResponse(status_code=HTTP_STATUS.get(e.reason, 400),
                            content={"error": e.reason, "detail": e.detail})

    @app.get("/health")
    def health():
        monad_only = svc.monad and isinstance(svc.attestor, UnconfiguredAttestor)
        return {"ok": True, "mode": "development" if svc.dev_mode else "production",
                "network": "monad testnet" if monad_only else svc.network, "settlement": type(svc.attestor).__name__,
                "physical": "enforced" if svc.enforce_physical else "reported",
                "anchor": "monad-testnet" if svc.monad else None}

    @app.get("/monad", include_in_schema=False)
    def monad_page():
        """Live verifier for PRESENCE on Monad: reads the registry and the agent-guard vault in the browser."""
        return FileResponse(Path(__file__).parent / "static" / "monad.html")

    @app.get("/missions")
    def missions():
        # Without a token pool (Monad-only deployment) only missions with no stake can run.
        staking = not isinstance(svc.attestor, UnconfiguredAttestor)
        return [{**m.public(), "reward": {**m.public()["reward"], "symbol": svc.symbol(m)}}
                for m in svc.missions.values() if staking or not m.stake_token]

    @app.post("/session")
    def create_session(body: CreateSession):
        return svc.create_session(body.wallet, body.mission_id, body.ephemeral_pubkey)

    # ---- MetaMask mobile: the app opens /metamask inside MetaMask's browser, which signs and posts back here.
    links: dict[str, tuple[float, str | None]] = {}

    @app.get("/metamask", include_in_schema=False)
    def metamask_page():
        return FileResponse(Path(__file__).parent / "static" / "metamask.html")

    @app.post("/wallet/link")
    def wallet_link():
        import secrets, time
        for k in [k for k, (t, _) in links.items() if time.time() - t > 600]: links.pop(k, None)
        code = secrets.token_urlsafe(9)
        links[code] = (time.time(), None)
        return {"code": code}

    @app.post("/wallet/link/{code}")
    def wallet_link_sign(code: str, body: LinkBody):
        if code not in links: raise AttestError(Reason.SESSION_NOT_FOUND, "unknown or expired link")
        identity.verify_wallet_signature(body.address, f"Link MetaMask to FACEDOWN\ncode: {code}", body.signature)
        links[code] = (links[code][0], body.address)
        return {"ok": True}

    @app.get("/wallet/link/{code}")
    def wallet_link_status(code: str):
        return {"address": links.get(code, (0, None))[1]}

    @app.get("/session/{session_id}/siwe")
    def session_siwe(session_id: str):
        row = svc._session(session_id)
        return {"wallet": row["wallet"], "message": row["siws_message"], "state": row["state"]}

    @app.post("/session/{session_id}/authorize")
    def authorize(session_id: str, body: Authorize):
        return svc.authorize(session_id, body.signature, body.stake_transaction)

    @app.post("/session/{session_id}/checkpoint")
    def checkpoint(session_id: str, body: Checkpoint):
        cp = CheckpointData(body.index, body.timestamp_ms, body.elapsed_ms, body.foreground, body.interactions,
                            body.response_ms, body.still_pct, body.touch_motion)
        return svc.add_checkpoint(session_id, cp, body.hash, body.signature)

    @app.post("/session/{session_id}/evidence")
    def submit_evidence(session_id: str, body: Evidence):
        return svc.submit_evidence(session_id, body.evidence_root, body.signature)

    @app.post("/verify")
    def verify(body: Verify):
        return svc.verify(body.session_id)

    @app.post("/session/{session_id}/settle")
    def settle(session_id: str):
        return svc.settle(session_id)

    @app.get("/session/{session_id}/receipt")
    def receipt(session_id: str):
        return svc.receipt(session_id)

    @app.post("/missions/generate")
    def generate_mission(body: GenerateMission):
        try:
            return mission_generator.generate(body.prompt)
        except ValueError as e:
            return JSONResponse(status_code=422, content={"error": "INVALID_MISSION", "detail": str(e)})

    @app.post("/sponsor/deposit")
    def sponsor_deposit(body: SponsorDeposit):
        return svc.sponsor_deposit(body.mission_id, body.signature)

    @app.get("/sponsor/pools")
    def pools():
        pool = getattr(svc.attestor, "pool", None)
        return {"pool_account": str(pool) if pool else None,
                "mint": str(svc.attestor.reward_mint) if svc.attestor.reward_mint else None,
                "missions": svc.pools()}

    @app.post("/dev/faucet")
    def faucet(body: Faucet):
        return svc.faucet(body.wallet)

    @app.get("/profile/{wallet}")
    def profile(wallet: str):
        return svc.profile(wallet)

    @app.post("/streak/freeze")
    def freeze_order(body: Faucet):
        return svc.freeze_order(body.wallet)

    @app.post("/streak/freeze/{order_id}")
    def freeze_pay(order_id: str, body: Authorize):
        return svc.freeze_pay(order_id, body.stake_transaction or "")

    @app.post("/squads")
    def squad_create(body: SquadCreate):
        return svc.squad_create(body.wallet, body.name)

    @app.post("/squads/join")
    def squad_join(body: SquadJoin):
        return svc.squad_join(body.wallet, body.code)

    @app.get("/squads/{wallet}")
    def squad(wallet: str):
        return svc.squad(wallet)

    @app.get("/live")
    def live():
        return svc.live()

    @app.get("/history/{wallet}")
    def history(wallet: str):
        return svc.history(wallet)

    return app


def main() -> None:
    import os

    import uvicorn
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    uvicorn.run(create_app(), host=os.environ.get("ATTEST_HOST", "0.0.0.0"),
                port=int(os.environ.get("ATTEST_PORT", "8787")))


if __name__ == "__main__":
    main()
