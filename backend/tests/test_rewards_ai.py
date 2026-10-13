"""Stakes, forfeits and pool bonuses; sponsor deposits; multi-claim; automation heuristics; mission drafting."""

import base64

import pytest

from attest import anomaly, mission_generator
from attest.policy import build_policy

from .conftest import MISSION, run_process, start, submit_and_verify
from .sim import SimDevice


def deposit(api, attestor, sig, amount_base, mission=MISSION):
    attestor.deposits[sig] = amount_base
    return api.post("/sponsor/deposit", json={"mission_id": mission, "signature": sig})


def pool(api, mission=MISSION):
    return api.get("/sponsor/pools").json()["missions"][mission]


def test_finisher_gets_stake_back_plus_pool_bonus(api, clock, device, attestor):
    assert deposit(api, attestor, "seed", 1_000_000).status_code == 200  # sponsor seeds the pool with 1 SKR
    sess = start(api, device)
    assert len(attestor.stakes) == 1  # the 1 SKR stake was submitted at authorize
    run_process(api, sess, clock)
    rc = submit_and_verify(api, sess).json()["receipt"]
    assert (rc["reward"]["stake"], rc["reward"]["bonus"]) == (1.0, 0.2)
    assert attestor.records[-1].reward_base == 1_200_000  # one transfer: stake + 20% bonus
    assert rc["settlement"]["reward_token"] == 1.2
    assert pool(api)["remaining"] == 0.8


def test_bonus_is_capped_by_the_pool_but_stake_always_returns(api, clock, device, attestor):
    sess = start(api, device)
    run_process(api, sess, clock)
    rc = submit_and_verify(api, sess).json()["receipt"]
    assert (rc["reward"]["stake"], rc["reward"]["bonus"]) == (1.0, 0.0)
    assert attestor.records[-1].reward_base == 1_000_000


def test_quitter_stake_is_locked_not_lost_and_the_lock_escalates(api, clock, attestor):
    """Cool-down Lock: quitting keeps the loss (no access for 7 / 14 / 30 days) without anyone winning it."""
    quitter = SimDevice()
    q = start(api, quitter)
    run_process(api, q, clock, foreground=False)  # switched apps during the session
    assert submit_and_verify(api, q).json()["verified"] is False
    rc = api.get(f"/session/{q.session_id}/receipt").json()
    assert rc["stake_locked"] == 1.0 and rc["stake_forfeited"] == 0
    assert rc["unlock_at"] == clock.t + 7 * 86_400
    assert pool(api)["forfeited"] == 0.0  # nobody else wins it
    q2 = start(api, quitter)
    run_process(api, q2, clock, foreground=False)
    submit_and_verify(api, q2)
    assert api.get(f"/session/{q2.session_id}/receipt").json()["unlock_at"] == clock.t + 14 * 86_400
    clock.advance(7 * 86_400 + 1)
    start(api, SimDevice())  # any later session releases stakes that are due
    assert attestor.refunds == [(quitter.wallet, 1_000_000)]  # the first lock ran out; the second still holds


def test_finishers_bonus_comes_from_sponsors_only(api, clock, attestor):
    f = start(api, SimDevice())
    run_process(api, f, clock)
    assert submit_and_verify(api, f).json()["receipt"]["reward"]["bonus"] == 0.0  # unfunded pool: no bonus
    deposit(api, attestor, "dep-bonus", 5_000_000)
    g = start(api, SimDevice())
    run_process(api, g, clock)
    assert submit_and_verify(api, g).json()["receipt"]["reward"]["bonus"] == 0.2


def test_unfinished_session_is_locked_on_expiry(api, clock, device, attestor):
    start(api, device)  # stake held, then the user walks away
    clock.advance(120 + 301)
    start(api, SimDevice())
    assert pool(api)["forfeited"] == 0.0
    clock.advance(7 * 86_400 + 1)
    start(api, SimDevice())
    assert attestor.refunds == [(device.wallet, 1_000_000)]


def test_stake_is_required_and_must_be_the_issued_transaction(api, device):
    body = api.post("/session", json={"wallet": device.wallet, "mission_id": MISSION,
                                      "ephemeral_pubkey": device.ephemeral_pubkey}).json()
    assert body["stake"]["amount"] == 1.0 and body["stake"]["symbol"] == "SKR-TEST"
    sig = device.sign_wallet(body["siws_message"])
    r = api.post(f"/session/{body['session_id']}/authorize", json={"signature": sig})
    assert r.status_code == 402 and r.json()["error"] == "STAKE_REQUIRED"
    forged = base64.b64encode(b"signed:stake:attacker:1").decode()  # a different transaction
    r = api.post(f"/session/{body['session_id']}/authorize", json={"signature": sig, "stake_transaction": forged})
    assert r.status_code == 402 and r.json()["error"] == "STAKE_FAILED"


def test_deposit_cannot_be_credited_twice_or_faked(api, attestor):
    assert deposit(api, attestor, "dep1", 1_000_000).status_code == 200
    r = api.post("/sponsor/deposit", json={"mission_id": MISSION, "signature": "dep1"})
    assert r.status_code == 409 and r.json()["error"] == "NONCE_REPLAY"
    r = api.post("/sponsor/deposit", json={"mission_id": MISSION, "signature": "not-a-deposit"})
    assert r.status_code == 409 and "did not move" in r.json()["detail"]


def test_three_clock_ins_a_day_each_with_its_own_claim_index(api, clock, device, attestor):
    for expected_seq in range(3):
        sess = start(api, device)
        run_process(api, sess, clock)
        assert submit_and_verify(api, sess).json()["verified"] is True
        assert attestor.records[-1].seq == expected_seq
    body = api.post("/session", json={"wallet": device.wallet, "mission_id": MISSION,
                                      "ephemeral_pubkey": device.ephemeral_pubkey}).json()
    import tests.conftest as c
    r = api.post(f"/session/{body['session_id']}/authorize", json=c.authorize_body(device, body))
    assert r.json()["error"] == "ALREADY_CLAIMED"
    assert api.get(f"/profile/{device.wallet}").json()["claims_today"] == {MISSION: 3}


def test_dev_faucet_grants_once(api, device):
    assert api.post("/dev/faucet", json={"wallet": device.wallet}).json()["granted"] is True
    assert api.post("/dev/faucet", json={"wallet": device.wallet}).json()["granted"] is False


def test_metronome_answers_are_flagged_as_automation(api, clock, device):
    sess = start(api, device)
    run_process(api, sess, clock, response_ms=300)  # identical answer time every window
    body = submit_and_verify(api, sess).json()
    assert body["verified"] is False and body["reason"] == "AUTOMATION_DETECTED"


def test_human_answer_times_pass_and_bot_patterns_score_high():
    assert anomaly.assess([700, 1200, 450, 900, 1600, 800]).risk < anomaly.THRESHOLD
    assert anomaly.assess([300, 302, 301, 299, 300, 301]).risk >= anomaly.THRESHOLD
    assert anomaly.assess([40, 60, 30, 50, 45, 55]).risk >= anomaly.THRESHOLD
    assert anomaly.assess([300, 300]).risk == 0  # too few samples to judge


def test_generator_drafts_an_enforceable_policy(api):
    r = api.post("/missions/generate",
                 json={"prompt": "Study session: stay 2 minutes with 12 checks, 2 times per day, 0.5 SKR"})
    body = r.json()
    assert r.status_code == 200 and body["status"] == "DRAFT_FOR_REVIEW" and body["source"] == "rules"
    m = body["mission"]
    assert (m["duration_seconds"], m["required_checkpoints"], m["checkpoint_interval_seconds"]) == (120, 12, 10)
    assert m["max_claims_per_identity_per_day"] == 2 and m["reward"]["token"] == 0.5
    build_policy(m)


def test_policies_the_engine_cannot_enforce_are_rejected():
    m = mission_generator.parse("event attendance for 1 minute")
    with pytest.raises(ValueError):
        build_policy({**m, "witness_required": True})
    with pytest.raises(ValueError):
        build_policy({**m, "duration_seconds": m["duration_seconds"] + 7})
    with pytest.raises(ValueError):
        build_policy({**m, "stake": {"token": 1, "bonus_rate": 2}})


def test_history_lists_finished_sessions_newest_first(api, clock, attestor):
    quitter = SimDevice()
    q = start(api, quitter)
    run_process(api, q, clock, foreground=False)
    submit_and_verify(api, q)
    clock.t += 3600
    f = start(api, quitter)
    run_process(api, f, clock)
    submit_and_verify(api, f)
    h = api.get(f"/history/{quitter.wallet}").json()
    assert [x["state"] for x in h] == ["SETTLED", "REJECTED"]
    assert h[0]["paid"] == 1.0 and h[1]["stake_locked"] == 1.0 and h[1]["unlock_at"] is not None
    assert h[1]["symbol"] == "SKR-TEST"  # rejected receipts carry no settlement, so it comes from the mission
    assert api.get(f"/history/{SimDevice().wallet}").json() == []


def test_live_counts_only_running_sessions(api, clock):
    assert api.get("/live").json() == {"facedown_now": 0}
    sess = start(api, SimDevice())
    assert api.get("/live").json() == {"facedown_now": 1}
    run_process(api, sess, clock)
    submit_and_verify(api, sess)
    assert api.get("/live").json() == {"facedown_now": 0}


def test_no_loss_returns_the_stake_whether_you_finish_or_not(api, svc, clock, attestor):
    quitter = SimDevice()
    q = start(api, quitter, mission="quick-noloss")
    run_process(api, q, clock, foreground=False)
    body = submit_and_verify(api, q).json()
    assert body["verified"] is False
    assert attestor.refunds == [(quitter.wallet, 1_000_000)]  # principal back, nothing forfeited
    assert svc._forfeited("quick-noloss") == 0
    h = api.get(f"/history/{quitter.wallet}").json()[0]
    assert h["stake_returned"] == 1.0 and h["stake_forfeited"] == 0
    finisher = SimDevice()
    f = start(api, finisher, mission="quick-noloss")
    run_process(api, f, clock)
    rc = submit_and_verify(api, f).json()["receipt"]
    assert rc["reward"]["stake"] == 1.0 and rc["reward"]["bonus"] == 0.0


def test_abandoned_no_loss_stake_is_refunded_after_expiry(api, clock, attestor):
    d = SimDevice()
    start(api, d, mission="quick-noloss")  # app killed: never finishes
    clock.advance(10_000)
    start(api, SimDevice())  # any later session sweeps expired No-Loss stakes
    assert attestor.refunds == [(d.wallet, 1_000_000)]


def test_streak_freeze_is_paid_in_skr_half_burned_and_covers_a_missed_day(api, clock, attestor):
    import base64
    d = SimDevice()
    s1 = start(api, d); run_process(api, s1, clock); submit_and_verify(api, s1)
    order = api.post("/streak/freeze", json={"wallet": d.wallet}).json()
    assert order["price"] == 2.0 and order["burn"] == 1.0
    signed = base64.b64encode(b"signed:" + base64.b64decode(order["transaction"])).decode()
    paid = api.post(f"/streak/freeze/{order['order_id']}", json={"signature": "-", "stake_transaction": signed}).json()
    assert paid["freezes"] == 1 and paid["burned"] == 1.0 and attestor.burns == [1_000_000]
    assert api.post(f"/streak/freeze/{order['order_id']}", json={"signature": "-", "stake_transaction": signed}).status_code == 409
    clock.advance(2 * 86_400)  # skip a whole day
    assert api.get(f"/profile/{d.wallet}").json()["streak"] == 1  # the freeze keeps it alive
    s2 = start(api, d); run_process(api, s2, clock); submit_and_verify(api, s2)
    p = api.get(f"/profile/{d.wallet}").json()
    assert p["streak"] == 2 and p["streak_freezes"] == 0


def test_squad_streak_needs_everyone_and_breaks_when_one_misses(api, clock):
    a, b = SimDevice(), SimDevice()
    sq = api.post("/squads", json={"wallet": a.wallet, "name": "Hostel 4"}).json()
    assert sq["size"] == 1 and len(sq["code"]) == 6
    assert api.post("/squads/join", json={"wallet": b.wallet, "code": sq["code"].lower()}).json()["size"] == 2
    assert api.post("/squads/join", json={"wallet": b.wallet, "code": sq["code"]}).status_code == 409  # one squad each

    def finish(d):
        s = start(api, d); run_process(api, s, clock); submit_and_verify(api, s)

    finish(a)
    view = api.get(f"/squads/{a.wallet}").json()
    assert view["done_today"] == 1 and view["streak"] == 0  # b hasn't gone facedown yet
    finish(b)
    assert api.get(f"/squads/{a.wallet}").json()["streak"] == 1
    clock.advance(86_400)
    finish(a); finish(b)
    assert api.get(f"/squads/{b.wallet}").json()["streak"] == 2
    clock.advance(86_400)
    finish(a)  # b misses today: the streak still stands until the day is over...
    assert api.get(f"/squads/{a.wallet}").json()["streak"] == 2
    clock.advance(86_400)
    assert api.get(f"/squads/{a.wallet}").json()["streak"] == 0  # ...then it breaks for everyone
    assert api.get(f"/squads/{SimDevice().wallet}").json() is None


def _verified_reward(api, clock, device):
    sess = start(api, device)
    run_process(api, sess, clock)
    r = submit_and_verify(api, sess).json()["receipt"]["reward"]
    return r["bonus"], r["daily_cap_reached"]


def test_daily_reward_cap_per_device_under_then_at_cap(api, svc, clock, device, attestor):
    deposit(api, attestor, "seed", 5_000_000)
    svc.daily_reward_cap = 0.3  # SKR per device per day
    assert _verified_reward(api, clock, device) == (0.2, False)  # under the cap: full bonus
    assert _verified_reward(api, clock, device) == (0.1, True)   # only what is left of the cap
    assert attestor.records[-1].reward_base == 1_100_000         # stake still returned in full
    assert _verified_reward(api, clock, device) == (0.0, True)   # at the cap: nothing more today


def test_daily_reward_cap_resets_the_next_day(api, svc, clock, device, attestor):
    deposit(api, attestor, "seed", 5_000_000)
    svc.daily_reward_cap = 0.2
    assert _verified_reward(api, clock, device) == (0.2, False)
    assert _verified_reward(api, clock, device) == (0.0, True)
    clock.advance(86_400)
    assert _verified_reward(api, clock, device) == (0.2, False)


def test_streak_repair_window_is_one_missed_day(api, clock, attestor):
    import base64
    def buy_freeze(d):
        order = api.post("/streak/freeze", json={"wallet": d.wallet}).json()
        signed = base64.b64encode(b"signed:" + base64.b64decode(order["transaction"])).decode()
        return api.post(f"/streak/freeze/{order['order_id']}", json={"signature": "-", "stake_transaction": signed}).json()
    late, ontime = SimDevice(), SimDevice()
    for d in (late, ontime):
        s = start(api, d); run_process(api, s, clock); submit_and_verify(api, s)
    clock.advance(2 * 86_400)            # missed yesterday: still inside the 24 h repair window
    buy_freeze(ontime)
    assert api.get(f"/profile/{ontime.wallet}").json()["streak"] == 1
    clock.advance(86_400)                # two missed days: the window has closed
    buy_freeze(late); buy_freeze(late)   # two freezes would have revived it before the window existed
    s = start(api, late); run_process(api, s, clock); submit_and_verify(api, s)
    assert api.get(f"/profile/{late.wallet}").json()["streak"] == 1  # restarted, not repaired
