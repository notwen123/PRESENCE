"""Critical-path tests: session, evidence, replay, policy, identity, attestation."""

from dataclasses import replace

import pytest

from attest import evidence, identity
from attest.errors import AttestError, Reason
from attest.evidence import CheckpointData
from attest.policy import ObservedCheckpoint, evaluate

from .conftest import MISSION, authorize_body, run_process, start, submit_and_verify
from .sim import SimDevice


def test_happy_path_produces_receipt_profile_and_settlement(api, clock, device, attestor):
    sess = start(api, device)
    run_process(api, sess, clock)
    r = submit_and_verify(api, sess)
    body = r.json()
    assert r.status_code == 200 and body["verified"] is True and body["reason"] is None
    rc = body["receipt"]
    assert rc["assurance"] == "P2" and rc["checkpoint_count"] == 4
    assert rc["duration_seconds"] == 120.0
    assert rc["checks"]["seeker_eligibility"] == "DEV_BYPASS"  # never claims SGT in dev mode
    assert rc["mode"] == "development"
    assert rc["reward"] == {"xp": 60, "token": 0.0, "symbol": "SKR-TEST", "funding": "NONE", "stake": 1.0, "bonus": 0.0, "daily_cap_reached": False}
    assert rc["streak"] == 1
    assert rc["settlement"]["status"] == "CONFIRMED"
    assert attestor.records[0].evidence_root.hex() == rc["evidence_root"]
    assert api.get(f"/session/{sess.session_id}/receipt").json()["state"] == "SETTLED"
    p = api.get(f"/profile/{device.wallet}").json()
    assert (p["xp"], p["streak"], p["league"], p["rank"], p["reputation"]) == (60, 1, "BRONZE", 1, 2)


def test_nonces_are_unique(api, device):
    nonces = {api.post("/session", json={"wallet": device.wallet, "mission_id": MISSION,
                                         "ephemeral_pubkey": device.ephemeral_pubkey}).json()["nonce"]
              for _ in range(20)}
    assert len(nonces) == 20


def test_expired_session_fails(api, clock, device):
    body = api.post("/session", json={"wallet": device.wallet, "mission_id": MISSION,
                                      "ephemeral_pubkey": device.ephemeral_pubkey}).json()
    clock.advance(421)  # 120 s mission + 300 s slack
    r = api.post(f"/session/{body['session_id']}/authorize",
                 json=authorize_body(device, body))
    assert r.status_code == 410 and r.json()["error"] == "SESSION_EXPIRED"


def test_modified_checkpoint_rejects_session(api, clock, device):
    sess = start(api, device)
    clock.advance(10)
    cp = sess.next_checkpoint(int(clock.t * 1000))
    cp["interactions"] += 5  # tamper after hashing
    r = api.post(f"/session/{sess.session_id}/checkpoint", json=cp)
    assert r.status_code == 400 and r.json()["error"] == "EVIDENCE_CHAIN_INVALID"
    assert api.get(f"/session/{sess.session_id}/receipt").json()["state"] == "REJECTED"


def test_incorrect_root_fails(api, clock, device):
    sess = start(api, device)
    run_process(api, sess, clock)
    bad = sess.evidence()
    bad["evidence_root"] = "00" * 32
    r = api.post(f"/session/{sess.session_id}/evidence", json=bad)
    assert r.json()["error"] == "EVIDENCE_CHAIN_INVALID"


def test_replay_second_claim_fails(api, clock, device):
    sess = start(api, device)
    run_process(api, sess, clock)
    assert submit_and_verify(api, sess).json()["verified"] is True
    r = api.post("/verify", json={"session_id": sess.session_id})
    assert r.status_code == 409 and r.json()["error"] == "NONCE_REPLAY"
    # Exceeding the daily claim limit: tests/test_rewards_ai.py::test_three_clock_ins_a_day_each_with_its_own_claim_index


def test_authorize_twice_is_replay(api, device):
    body = api.post("/session", json={"wallet": device.wallet, "mission_id": MISSION,
                                      "ephemeral_pubkey": device.ephemeral_pubkey}).json()
    auth = authorize_body(device, body)
    assert api.post(f"/session/{body['session_id']}/authorize", json=auth).status_code == 200
    r = api.post(f"/session/{body['session_id']}/authorize", json=auth)
    assert r.json()["error"] == "NONCE_REPLAY"


def test_insufficient_checkpoints_fail(api, clock, device):
    sess = start(api, device)
    run_process(api, sess, clock, checkpoints=3, interval=40)
    body = submit_and_verify(api, sess).json()
    assert body["verified"] is False and body["reason"] == "POLICY_NOT_SATISFIED"
    assert "3/4 checkpoints" in body["detail"]


def test_insufficient_duration_fails(api, clock, device):
    sess = start(api, device)
    run_process(api, sess, clock, interval=2)  # a script racing through the process
    body = submit_and_verify(api, sess).json()
    assert body["verified"] is False and "duration" in body["detail"]


def test_missing_interaction_and_background_fail(api, clock, device):
    sess = start(api, device)
    run_process(api, sess, clock, interactions=0, foreground=False)
    body = submit_and_verify(api, sess).json()
    assert body["verified"] is False
    assert "left foreground" in body["detail"] and "0 interactions" in body["detail"]


def test_wrong_assurance_fails(svc):
    policy = svc.missions[MISSION]
    cps = [ObservedCheckpoint(i, 30.0 * (i + 1), True, 1, 700 + 150 * i, 90, 200) for i in range(4)]
    with pytest.raises(AttestError) as e:
        evaluate(policy, 0, 120, cps, achieved_assurance=1)
    assert e.value.reason == Reason.POLICY_NOT_SATISFIED
    with pytest.raises(AttestError):
        evaluate(replace(policy, required_assurance="P3"), 0, 60, cps, achieved_assurance=2)
    evaluate(policy, 0, 120, cps, achieved_assurance=2)  # passes


def test_signature_from_other_wallet_fails(api, device):
    body = api.post("/session", json={"wallet": device.wallet, "mission_id": MISSION,
                                      "ephemeral_pubkey": device.ephemeral_pubkey}).json()
    r = api.post(f"/session/{body['session_id']}/authorize",
                 json={"signature": SimDevice().sign_wallet(body["siws_message"])})
    assert r.status_code == 401 and r.json()["error"] == "INVALID_SIGNATURE"


def test_invalid_wallet_fails(api, device):
    r = api.post("/session", json={"wallet": "not-a-key", "mission_id": MISSION,
                                   "ephemeral_pubkey": device.ephemeral_pubkey})
    assert r.json()["error"] == "INVALID_IDENTITY"


def test_stolen_session_without_ephemeral_key_fails(api, clock, device):
    sess = start(api, device)
    sess.device = SimDevice()  # attacker knows session id + nonce but not the device's ephemeral key
    clock.advance(10)
    r = api.post(f"/session/{sess.session_id}/checkpoint", json=sess.next_checkpoint(int(clock.t * 1000)))
    assert r.json()["error"] == "INVALID_SIGNATURE"


def test_siws_message_binds_nonce_and_ephemeral_key(api, device):
    body = api.post("/session", json={"wallet": device.wallet, "mission_id": MISSION,
                                      "ephemeral_pubkey": device.ephemeral_pubkey}).json()
    msg = body["siws_message"]
    assert msg.startswith("presence.test wants you to sign in with your Solana account:\n" + device.wallet)
    assert f"Nonce: {body['nonce']}" in msg
    assert identity.ephemeral_fingerprint(device.ephemeral_pubkey) in msg


def test_evidence_chain_golden_vector():
    """Shared with Android EvidenceChainTest.kt: both implementations must produce these bytes."""
    cps = [CheckpointData(i, 1_700_000_000_000 + i * 10_000, (i + 1) * 10_000, True, 2, 700 + i * 100, 90 + i, 150 + i * 10)
           for i in range(6)]
    hashes = evidence.chain("session-1", "nonce-1", cps)
    assert evidence.genesis("session-1", "nonce-1").hex() == GOLDEN_GENESIS
    assert hashes[0].hex() == GOLDEN_CP0
    assert evidence.evidence_root(hashes[-1]).hex() == GOLDEN_ROOT


GOLDEN_GENESIS = "c52244702ffcfcac1b4198db2c0c1f086640a36441c9bd265217dec3fae9a5ef"
GOLDEN_CP0 = "b5312383492ebad14201269cbb6fbfa77c391156fe826ec187a083a10846b811"
GOLDEN_ROOT = "189610d148640d4be13c7399bf39f83329d1b0d014e50e513aa2bfeddbeaaffb"


def test_injected_taps_are_caught_when_physical_evidence_is_enforced(api, svc, clock, device):
    """An "I'm here" tap that didn't move the phone (a script or AI agent injecting input) fails."""
    svc.enforce_physical = True
    sess = start(api, device)
    run_process(api, sess, clock, touch_motion=0)
    body = submit_and_verify(api, sess).json()
    assert body["verified"] is False and body["reason"] == "AUTOMATION_DETECTED"
    assert "no finger touched it" in body["detail"]


def test_phone_picked_up_fails_when_enforced(api, svc, clock, device):
    svc.enforce_physical = True
    sess = start(api, device)
    run_process(api, sess, clock, still_pct=20)
    body = submit_and_verify(api, sess).json()
    assert body["verified"] is False and body["reason"] == "POLICY_NOT_SATISFIED"
    assert "phone still 20%" in body["detail"]


def test_human_session_passes_physical_checks_and_receipt_says_so(api, svc, clock, device):
    svc.enforce_physical = True
    sess = start(api, device)
    run_process(api, sess, clock)
    rc = submit_and_verify(api, sess).json()["receipt"]
    assert rc["checks"]["human_touch"] == "PASSED" and rc["checks"]["phone_down"] == "PASSED"
    assert rc["physical"]["still_pct"] == [92] * 4


def test_development_reports_physical_problems_without_failing(api, clock, device):
    """Emulators have no real motion: development flags it in the receipt instead of rejecting."""
    sess = start(api, device)
    run_process(api, sess, clock, touch_motion=0)
    body = submit_and_verify(api, sess).json()
    assert body["verified"] is True and body["receipt"]["checks"]["human_touch"] == "FLAGGED"


def test_one_missed_check_in_is_forgiven_two_are_not(api, clock, device):
    """N-1 rule: a soft knock that wasn't felt shouldn't cost a real session."""
    sess = start(api, device)
    for i in range(4):
        clock.advance(30)
        api.post(f"/session/{sess.session_id}/checkpoint",
                 json=sess.next_checkpoint(int(clock.t * 1000), interactions=0 if i == 2 else 1))
    assert submit_and_verify(api, sess).json()["verified"] is True
    other = start(api, SimDevice())
    for i in range(4):
        clock.advance(30)
        api.post(f"/session/{other.session_id}/checkpoint",
                 json=other.next_checkpoint(int(clock.t * 1000), interactions=0 if i in (1, 2) else 1))
    body = submit_and_verify(api, other).json()
    assert body["verified"] is False and "0 interactions" in body["detail"]
