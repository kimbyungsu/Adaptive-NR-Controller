"""nrctl 리포트 계산 테스트(가상 기록). 실행: python -m unittest pc/test_nrctl.py"""

import argparse
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nrctl  # noqa: E402

S = 1000
T0 = 1_800_000_000_000  # 임의 기준 시각(ms)


def params(**kw):
    ap = nrctl.build_parser()
    argv = ["report"]
    for k, v in kw.items():
        argv += ["--" + k.replace("_", "-"), str(v)]
    return nrctl.Params(ap.parse_args(argv))


class Log:
    """가상 기록 작성기. 시각은 T0 기준 초."""

    def __init__(self, nr_mode=True, screen=True, subs=1):
        self.recs = [{"t": T0, "ev": "start", "activeSubs": subs, "pid": 1}]
        self.add(0, "mode", nr=nr_mode, mask=840583 if nr_mode else 316295)
        self.add(0, "screen", on=screen)

    def add(self, sec, ev, **kw):
        o = {"t": T0 + int(sec * S), "ev": ev}
        o.update(kw)
        self.recs.append(o)
        return self

    def use(self, a, b):
        """데이터 사용 구간 [a, b]초."""
        return self.add(b + 4, "data", **{"from": T0 + int(a * S), "ms": int((b - a) * S)})

    def drop(self, sec, active=True, dwell=5):
        self.add(sec - dwell, "nr_on", screen=True)
        return self.add(sec, "nr_off", sinceDataMs=0 if active else 30 * S, dwellMs=dwell * S, screen=True)

    def run(self, end, p=None):
        self.add(end, "alive")
        self.recs.sort(key=lambda o: o["t"])
        sess = nrctl.split_sessions(self.recs)
        self.assert_one(sess)
        sim = nrctl.Sim(p or params(), sess[0])
        for o in sess[0].recs:
            sim.feed(o)
        sim.finish(sess[0].span[1])
        return sim

    @staticmethod
    def assert_one(sess):
        assert len(sess) == 1, len(sess)


def states(sim):
    return [(round((t - T0) / S), b, why) for t, _, b, why in sim.log]


class IntervalTest(unittest.TestCase):
    def test_helpers(self):
        a = nrctl.merge_iv([(5, 10), (0, 3), (2, 4), (10, 12)])
        self.assertEqual(a, [(0, 4), (5, 12)])
        self.assertEqual(nrctl.intersect(a, [(3, 6), (11, 20)]), [(3, 4), (5, 6), (11, 12)])
        self.assertEqual(nrctl.total(a), 11)
        self.assertTrue(nrctl.covered(a, 6, 12))
        self.assertFalse(nrctl.covered(a, 3, 6))
        self.assertEqual(nrctl.time_to_accumulate(a, 2, 5), 8)
        self.assertIsNone(nrctl.time_to_accumulate(a, 2, 50))
        self.assertFalse(nrctl.after_start_inside(a, 5))  # 구간을 연 시각은 제외
        self.assertTrue(nrctl.after_start_inside(a, 6))
        self.assertTrue(nrctl.after_start_inside(a, 12))


class SimTest(unittest.TestCase):
    def test_three_active_drops_then_probe_pass(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s)
        lg.use(400, 480)  # 쿨다운(90~390초) 뒤 재시험 평가 창에서 데이터 80초
        sim = lg.run(1000)
        self.assertEqual(sim.triggers["drops"], 1)
        self.assertEqual([k for _, k in sim.switches], ["lte", "nr"])
        self.assertEqual(sim.switches[0][0], T0 + 90 * S)
        self.assertEqual(sim.switches[1][0], T0 + 390 * S)  # C_base 300초
        self.assertEqual(sim.probes["pass"], 1)
        self.assertIn((460, "WATCH", "probe_pass"), states(sim))  # 평가 시작 400초 + 데이터 60초

    def test_long_use_split_by_tick_counts_fully(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s)
        for a in range(0, 1000, 30):  # 데몬이 30초마다 끊어 남긴 조각(계속 사용 중)
            lg.use(a, min(a + 30, 1000))
        sim = lg.run(1100)
        sess = nrctl.split_sessions(lg.recs)[0]
        self.assertEqual(nrctl.total(sess.use), 1000 * S)
        self.assertIn((460, "WATCH", "probe_pass"), states(sim))  # 평가 시작 400초 + 누적 60초

    def test_idle_drops_do_not_count(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s, active=False)
        sim = lg.run(600)
        self.assertEqual(sim.switches, [])
        self.assertEqual(states(sim), [(0, "WATCH", "activate"), (300, "GOOD", "t_clear")])

    def test_lte_mode_is_inactive(self):
        lg = Log(nr_mode=False)
        for s in (10, 50, 90):
            lg.drop(s)
        sim = lg.run(600)
        self.assertEqual(sim.switches, [])
        self.assertEqual(sim.state, "INACTIVE")

    def test_call_clears_window(self):
        lg = Log()
        lg.drop(10)
        lg.drop(50)
        lg.add(60, "call", state=2)
        lg.drop(90)  # 통화 중: 판정은 나지만 가드로 보류, 창 비움
        lg.add(100, "call", state=0)
        lg.drop(140)  # 창이 비워져 1회째
        sim = lg.run(600)
        self.assertEqual(sim.switches, [])
        self.assertEqual(sim.suppressed["call"], 0)  # 통화 시작 때 창을 비워 90초·140초 끊김은 1·2회째(3회 미만)

    def test_screen_off_is_observation_gap(self):
        lg = Log()
        lg.drop(10)
        lg.drop(50)
        lg.add(60, "screen", on=False)
        lg.add(70, "screen", on=True)
        lg.drop(90)
        sim = lg.run(600)
        self.assertEqual(sim.switches, [])

    def test_oos_waits_for_service_then_cools(self):
        lg = Log()
        lg.drop(10)  # WATCH
        lg.add(20, "oos", reg=1, sinceDataMs=0, screen=True)
        lg.add(25, "service", outMs=5 * S, screen=True)
        sim = lg.run(100)
        self.assertEqual(sim.suppressed["no_service"], 1)
        self.assertEqual(sim.triggers["oos"], 1)
        self.assertEqual(sim.switches[0][0], T0 + 25 * S)

    def test_probe_failure_backs_off_after_budget_gap(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s)
        # 재시험 390초, 평가 시작 400초. 410·420초 두 번 끊김 → 실패 판정, 최소 간격(120초) 때문에 510초에 LTE
        lg.drop(410, dwell=3)
        lg.drop(420, dwell=3)
        sim = lg.run(2000)
        self.assertEqual([k for _, k in sim.switches], ["lte", "nr", "lte", "nr"])
        self.assertEqual(sim.switches[2][0], T0 + 510 * S)
        self.assertEqual(sim.suppressed["budget_gap"], 1)
        self.assertEqual(sim.switches[3][0], T0 + 1110 * S)  # 레벨 1: 600초 쿨다운
        self.assertEqual(sim.level, 1)

    def test_cooldown_waits_for_screen_on(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s)
        lg.add(200, "screen", on=False)
        lg.add(700, "screen", on=True)  # 쿨다운은 390초에 끝났지만 화면이 꺼져 있어 700초에 재시험
        sim = lg.run(800)
        self.assertEqual(sim.switches[1], (T0 + 700 * S, "nr"))

    def test_dual_sim_never_switches(self):
        lg = Log(subs=2)
        for s in (10, 50, 90):
            lg.drop(s)
        sim = lg.run(600)
        self.assertEqual(sim.switches, [])
        self.assertEqual(sim.holds["dual_sim"], 1)  # 활성화 때부터 보류: 끊김을 창에 넣지 않는다

    def test_drops_during_call_and_grace_are_ignored(self):
        lg = Log()
        lg.add(60, "call", state=2)
        lg.drop(80)
        lg.drop(90)
        lg.add(100, "call", state=0)
        lg.drop(120)  # 통화 종료 후 유예(30초) 안
        lg.drop(140)  # 유예가 풀린 뒤 새 창의 1회째
        sim = lg.run(600)
        self.assertEqual(sim.switches, [])
        self.assertEqual(sim.holds["call"], 1)
        self.assertEqual([t for t, _ in sim.drops], [T0 + 140 * S])  # 80·90·120초 끊김은 창에 들어가지 않음

    def test_probe_window_restarts_after_screen_off(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s)
        lg.drop(410, dwell=3)  # 재시험(390초) 평가 창(400초~) 1회째
        lg.add(420, "screen", on=False)
        lg.add(500, "screen", on=True)  # 평가 창을 500초부터 다시 연다
        lg.drop(520, dwell=3)  # 새 창의 1회째 → 실패 아님(N_probe 2)
        sim = lg.run(1000)
        self.assertEqual([k for _, k in sim.switches], ["lte", "nr"])
        self.assertEqual(sim.probes["undecided"], 1)
        self.assertIn((800, "WATCH", "probe_undecided"), states(sim))  # 500 + P_max 300

    def test_expired_oos_does_not_trigger_after_long_outage(self):
        lg = Log()
        lg.add(20, "oos", reg=1, sinceDataMs=0, screen=True)
        lg.add(500, "service", outMs=480 * S, screen=True)
        sim = lg.run(600)
        self.assertEqual(sim.switches, [])
        self.assertEqual(sim.suppressed["no_service"], 1)

    def test_external_nr_restriction_holds_probe(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s)
        lg.add(200, "allowed", reason=1, mask=316295, nr=False)  # USER 외 사유가 NR을 막음
        lg.add(600, "allowed", reason=1, mask=840583, nr=True)   # 제한 해제
        sim = lg.run(700)
        self.assertEqual(sim.holds["restricted"], 1)
        self.assertEqual(sim.switches[1], (T0 + 600 * S, "nr"))  # 쿨다운은 390초에 끝났지만 해제 때까지 대기

    def test_hourly_budget(self):
        lg = Log()
        t = 10
        for _ in range(4):  # 15분마다 3회 끊김(매번 쿨다운 조건 충족) → 시간당 전환 4회 상한에 걸려야 한다
            lg.drop(t)
            lg.drop(t + 20)
            lg.drop(t + 40)
            t += 900
        sim = lg.run(3600 * 2, params(c_base=60, b_gap=10))
        lte_times = [round((s - T0) / S) for s, k in sim.switches if k == "lte"]
        self.assertTrue(all(b - a >= 10 for a, b in zip(lte_times, lte_times[1:])))
        per_hour = [s for s, _ in sim.switches if s - T0 < 3600 * S]
        self.assertLessEqual(len(per_hour), 4)

    def test_hold_during_settle_keeps_settle_end(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s)
        lg.add(391, "screen", on=False)  # 재시험(390초) 정착 구간(~400초) 안의 짧은 보류
        lg.add(392, "screen", on=True)
        lg.add(395, "oos", reg=1, sinceDataMs=0, screen=True)  # 정착 구간 사건 → 판정에 쓰지 않음
        lg.add(396, "service", outMs=S, screen=True)
        sim = lg.run(1000)
        self.assertEqual([k for _, k in sim.switches], ["lte", "nr"])
        self.assertEqual(sim.triggers["probe_oos"], 0)
        self.assertIn((700, "WATCH", "probe_undecided"), states(sim))  # 평가 창은 400초부터(P_max 300)


class QuietAfterModeChangeTest(unittest.TestCase):
    def test_user_mode_change_transient_is_not_counted(self):
        lg = Log(nr_mode=False)
        lg.add(100, "mode", nr=True, mask=840583, **{"from": "allowed"})  # 사용자가 5G 우선 선택
        lg.add(100.3, "oos", reg=1, sinceDataMs=3000, screen=True)
        lg.add(102, "service", outMs=1700, screen=True)
        lg.add(120, "oos", reg=1, sinceDataMs=0, screen=True)  # 조용한 구간(10초) 뒤
        lg.add(121, "service", outMs=1000, screen=True)
        sim = lg.run(200)
        self.assertEqual([round((s - T0) / S) for s, _ in sim.switches], [121])


class WifiHoldTest(unittest.TestCase):
    def test_drops_while_on_wifi_are_ignored(self):
        lg = Log()
        lg.add(5, "net", default="wifi", wifi=True)
        for s in (10, 50, 90):
            lg.drop(s)
        lg.add(100, "net", default="cellular", wifi=False)
        sim = lg.run(300)
        self.assertEqual(sim.switches, [])
        self.assertEqual(sim.holds["wifi"], 1)


class WifiRestoreTest(unittest.TestCase):
    def test_wifi_restores_original_in_cooldown(self):
        lg = Log()
        for s in (10, 50, 90):
            lg.drop(s)
        lg.add(150, "net", default="wifi", wifi=True)
        lg.add(400, "net", default="cellular", wifi=False)
        sim = lg.run(1000)
        self.assertEqual([(round((t - T0) / S), k) for t, k in sim.switches], [(90, "lte"), (150, "nr")])
        self.assertEqual(sim.wifi_restores, 1)  # 리포트는 이것을 재시험과 나눠 센다

    def test_lte_user_is_never_switched_by_wifi(self):
        lg = Log(nr_mode=False)
        lg.add(10, "net", default="wifi", wifi=True)
        lg.add(100, "net", default="cellular", wifi=False)
        sim = lg.run(500)
        self.assertEqual(sim.switches, [])


class SessionTest(unittest.TestCase):
    def test_stop_record_of_other_instance_does_not_close_session(self):
        recs = [
            {"t": T0 + 10050, "ev": "start", "pid": 202},
            {"t": T0 + 11000, "ev": "stopped", "by": "nrctl-kill", "pid": 101},  # 이전 실행의 늦은 기록
            {"t": T0 + 12000, "ev": "screen", "on": True},
            {"t": T0 + 20000, "ev": "stopped", "by": "request", "pid": 202},
        ]
        sess = nrctl.split_sessions(recs)
        self.assertEqual(len(sess), 1)
        self.assertEqual(sess[0].ended_by, "stopped")
        self.assertEqual(sess[0].end_t, T0 + 20000)
        self.assertEqual([o["ev"] for o in sess[0].recs], ["start", "screen", "stopped"])


class StopCommandTest(unittest.TestCase):
    """nrctl stop: 대상 pid를 적은 종료 요청만 보내고, 프로세스 번호로 신호를 보내지 않는다."""

    class FakeDevice:
        def __init__(self, alive_polls):
            self.cmds = []
            self.alive_polls = alive_polls  # REMOTE_ALIVE에 "yes"로 답할 횟수

        def running_pid(self):
            return 101

        def shell(self, script, timeout=60, check=True):
            self.cmds.append(script)
            if script == nrctl.REMOTE_ALIVE % 101:
                if self.alive_polls > 0:
                    self.alive_polls -= 1
                    return "yes\n"
                return ""
            return ""

    def run_stop(self, dev):
        orig_connect, orig_wait = nrctl.connect, nrctl.STOP_WAIT_SEC
        nrctl.connect = lambda args: dev
        nrctl.STOP_WAIT_SEC = 1
        try:
            return nrctl.cmd_stop(None)
        finally:
            nrctl.connect, nrctl.STOP_WAIT_SEC = orig_connect, orig_wait

    def test_graceful(self):
        dev = self.FakeDevice(alive_polls=1)
        self.assertEqual(self.run_stop(dev), 0)
        self.assertIn("echo 101 > %s" % nrctl.REMOTE_STOP_REQ, dev.cmds)
        self.assertFalse(any("kill" in c for c in dev.cmds))

    def test_unresponsive_reports_failure_without_signal(self):
        dev = self.FakeDevice(alive_polls=10**6)
        with self.assertRaises(nrctl.NrctlError):
            self.run_stop(dev)
        self.assertFalse(any("kill" in c for c in dev.cmds))
        # 종료가 확인되지 않았으므로 pid 파일·요청 파일을 지우지 않는다(검증 3회차 지적: 지우면 status가 "꺼짐"으로 오보)
        self.assertFalse(any("rm " in c for c in dev.cmds))
        self.assertEqual(dev.shell(nrctl.REMOTE_ALIVE % 101), "yes\n")  # 여전히 살아 있는 것으로 조회됨

    def test_graceful_cleans_only_files_pointing_to_pid(self):
        dev = self.FakeDevice(alive_polls=0)
        self.run_stop(dev)
        cleanup = [c for c in dev.cmds if c.startswith("for f in")]
        self.assertEqual(len(cleanup), 1)
        self.assertIn('= "101" ] && rm -f $f', cleanup[0])  # 이 pid를 가리킬 때만 지움
        self.assertLess(dev.cmds.index(nrctl.REMOTE_ALIVE % 101), dev.cmds.index(cleanup[0]))  # 종료 확인 뒤


class RestoreTest(unittest.TestCase):
    """긴급 복구(DESIGN 5.12): 실제 허용 모드를 설정 화면(사용자 선택)에 맞춘다. 설정 값은 쓰지 않는다."""

    class FakePhone:
        def __init__(self, user, k2="9", kl="9", cmd_result=None, ignore_put=None):
            self.user = user
            self.settings = {"preferred_network_mode2": k2, "preferred_network_mode": kl}
            self.cmd_result = cmd_result
            self.ignore_put = ignore_put
            self.cmds = []
            self.state = {"original": 840583, "originalMode": 26, "sub": 2, "slot": 0}

        def running_pid(self):
            return None

        def run(self, args, timeout=60, check=True):
            return ""

        def shell(self, script, timeout=60, check=True):
            self.cmds.append(script)
            if script.startswith("cat %s" % nrctl.REMOTE_STATE):
                return json.dumps(self.state)
            if script.startswith("ls %s" % nrctl.REMOTE_DEX):
                return "OK\n"
            if "nrc.Nrd --query" in script:
                return "user=%d sub=2 slot=0\n" % self.user
            if script.startswith("dumpsys telephony.registry"):
                return "    mCallState=0\n    mRingingCallState=0\n"
            parts = script.split()
            if script.startswith("cmd phone set-allowed-network-types-for-users"):
                self.user = self.cmd_result if self.cmd_result is not None else int(parts[-1], 2)
                return "completed\n"
            if script.startswith("settings get global"):
                return self.settings.get(parts[3], "null") + "\n"
            if script.startswith("settings put global"):
                if parts[3] != self.ignore_put:
                    self.settings[parts[3]] = parts[4]
                return ""
            return ""

    def run_restore(self, ph):
        orig = nrctl.connect
        nrctl.connect = lambda args: ph
        try:
            return nrctl.cmd_restore(argparse.Namespace(yes=True))
        finally:
            nrctl.connect = orig

    def test_key_5g_user_lte_adds_nr_and_never_writes_settings(self):
        ph = self.FakePhone(316295, k2="26", kl="26")  # 설정 화면 5G 우선, 실제 LTE(쉬는 중 멈춤)
        self.assertEqual(self.run_restore(ph), 0)
        self.assertEqual(ph.user, 840583)
        self.assertEqual(ph.settings, {"preferred_network_mode2": "26", "preferred_network_mode": "26"})
        self.assertFalse(any(c.startswith("settings put") for c in ph.cmds))

    def test_key_lte_user_5g_removes_nr(self):
        ph = self.FakePhone(840583, k2="9", kl="9")  # 설정 화면 LTE 우선인데 실제 5G 포함
        self.assertEqual(self.run_restore(ph), 0)
        self.assertEqual(ph.user, 316295)

    def test_already_matching_writes_nothing(self):
        ph = self.FakePhone(316295, k2="9", kl="9")
        self.assertEqual(self.run_restore(ph), 0)
        self.assertFalse(any(c.startswith("cmd phone set") for c in ph.cmds))

    def test_different_mask_after_cmd_is_failure(self):
        ph = self.FakePhone(316295, k2="26", kl="26", cmd_result=840583 & ~2)  # 다른 마스크가 됨
        with self.assertRaises(nrctl.NrctlError):
            self.run_restore(ph)

    def test_change_during_confirmation_writes_nothing(self):
        ph = self.FakePhone(316295, k2="26", kl="26")
        orig_ask = nrctl._ask

        def ask_and_user_picks_lte(prompt, choices):
            ph.settings["preferred_network_mode2"] = "9"  # 확인하는 사이 사용자가 폰에서 LTE 우선을 고름
            return True
        nrctl._ask = ask_and_user_picks_lte
        orig = nrctl.connect
        nrctl.connect = lambda args: ph
        try:
            with self.assertRaises(nrctl.NrctlError):
                nrctl.cmd_restore(argparse.Namespace(yes=False))
        finally:
            nrctl.connect = orig
            nrctl._ask = orig_ask
        self.assertFalse(any(c.startswith("cmd phone set") for c in ph.cmds))
        self.assertEqual(ph.user, 316295)

    def test_unreadable_key_writes_nothing(self):
        ph = self.FakePhone(316295, k2="26; echo NRC_TEST", kl="26")
        with self.assertRaises(nrctl.NrctlError):
            self.run_restore(ph)
        self.assertFalse(any(c.startswith("cmd phone set") or c.startswith("settings put") for c in ph.cmds))


if __name__ == "__main__":
    unittest.main()
