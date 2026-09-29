package nrc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Policy 시험(JDK만 필요). 실행: bash daemon/test.sh
 * nrctl report 시뮬레이션 시험(pc/test_nrctl.py)과 같은 상황을 쓴다. 차이는 정착 구간이다:
 * 여기서는 전환 뒤 2초에 망 정상 확인 + 10초 = 전환 뒤 12초에 평가 창이 열린다(시뮬레이션은 10초).
 */
public final class PolicyTest {
    private static final long S = 1000;
    private static int failed;
    private static int passed;

    /** 가짜 환경: 쓰기를 기록하고, 정해 둔 결과를 순서대로 돌려준다. */
    static final class FakeEnv implements Policy.Env {
        final List<String> writes = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final ArrayDeque<Policy.Result> results = new ArrayDeque<>();
        /** 쓰기에 걸리는 시간(ms). 0이면 결과에 끝난 시각을 넣지 않는다. */
        long writeTakesMs;
        String callNow;
        boolean heavy;
        boolean indicatorOn;
        int keepCalls;
        int stopDone;
        Boolean lastStopRestored;

        /** 이 시각 전까지 쓰기를 BLOCKED(다시 쓸 시각 = 이 값)로 돌려준다(기록하지 않음). -1 = 없음. */
        long blockUntil = -1;

        @Override
        public Policy.Result write(long t, boolean allowNr, String why) {
            if (blockUntil > t) return Policy.Result.of(Policy.Kind.BLOCKED, blockUntil);
            writes.add((allowNr ? "nr@" : "lte@") + t / S);
            if (!results.isEmpty()) return results.poll();
            return writeTakesMs > 0 ? Policy.Result.of(Policy.Kind.OK, t + writeTakesMs) : Policy.Result.of(Policy.Kind.OK);
        }

        @Override
        public String callNow() {
            return callNow;
        }

        /** 대용량 트래픽을 재는 동안 일어나는 일(예: 통화 시작) 흉내. */
        Runnable duringHeavy;

        @Override
        public boolean heavyNow() {
            if (duringHeavy != null) duringHeavy.run();
            return heavy;
        }

        @Override
        public void indicator(boolean on) {
            indicatorOn = on;
        }

        @Override
        public void log(String ev, Object... kv) {
            StringBuilder sb = new StringBuilder(ev);
            for (Object o : kv) sb.append(' ').append(o);
            logs.add(sb.toString());
        }

        @Override
        public void persist(Policy.State s, boolean nrAllowed) {
        }

        @Override
        public void keepCurrentAsOriginal() {
            keepCalls++;
        }

        @Override
        public void stopDone(boolean restored) {
            stopDone++;
            lastStopRestored = restored;
        }

        boolean logged(String prefix) {
            for (String l : logs) if (l.startsWith(prefix)) return true;
            return false;
        }
    }

    /** 5G 우선 모드·화면 켜짐으로 시작하는 기본 상황. */
    static Object[] start(Params p, boolean nrMode, String blocked) {
        FakeEnv env = new FakeEnv();
        Policy pol = new Policy(p, env);
        pol.screen(0, true);
        pol.call(0, 0);
        if (blocked != null) pol.setBlocked(0, blocked);
        pol.setMode(0, nrMode);
        return new Object[]{pol, env};
    }

    static Object[] start() {
        return start(new Params(), true, null);
    }

    static void drop(Policy pol, long sec) {
        pol.nrOff(sec * S, 0, 5 * S, true);
    }

    public static void main(String[] args) {
        threeActiveDropsThenProbePass();
        idleDropsDoNotCount();
        lteModeIsInactive();
        callClearsWindowAndGraceIgnored();
        screenOffIsObservationGap();
        oosWaitsForServiceThenCools();
        expiredOosDoesNotTrigger();
        probeFailureBacksOffAfterBudgetGap();
        cooldownWaitsForScreenOn();
        dualSimNeverSwitches();
        hourlyBudget();
        probeWindowRestartsAfterScreenOff();
        holdDuringSettleKeepsSettleEnd();
        externalRestrictionHoldsProbe();
        settleTimeoutSafeStopsAndRestores();
        adoptedWriteBecomesUserMode();
        failedWriteHoldsUntilResume();
        rolledBackGoesObserve();
        stopRestoresAndDefersDuringCall();
        keepLteMakesLteOriginal();
        blockedDuringCooldownRestoresOnce();
        userSelectsLteDuringCooldown();
        heavyTrafficDefersProbe();
        indicatorFollowsControl();
        testCooldownUsesGuardsAndShortRest();
        userModeChangeTransientIsNotCounted();
        settleClockStartsWhenWriteEnds();
        quietAfterStartIgnoresTransient();
        callStartingDuringHeavyCheckBlocks();
        blockedWriteDoesNotSwitch();
        lteOosDuringCooldownKeepsCooldown();
        probeUseResetsAfterHold();
        probeUseCountsShortGaps();
        blockedProbeDoesNotSpin();
        stopRestoreFailureIsNotClean();
        brokenAdoptedStopsWithUserMode();
        wifiHidesIndicatorAndHolds();
        wifiRestoresOriginalFiveG();
        wifiNeverForcesFiveGWhenUserChoseLte();
        wifiRestoreWaitsForCall();
        userChoiceBeatsOverdueProbe();
        blockedWithRetryHintProbesAtHint();
        userPicks5gWhileActualLteRetests();
        System.out.println((failed == 0 ? "OK " : "FAILED " + failed + " / ") + passed + " checks passed");
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- Phase 1 시뮬레이션과 같은 상황

    static void threeActiveDropsThenProbePass() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("3회 끊김 → 90초 LTE", env.writes, "[lte@90]");
        pol.advance(390 * S);
        check("쿨다운 300초 → 390초 재시험", env.writes, "[lte@90, nr@390]");
        pol.dataActivity(400 * S, true); // 평가 창은 402초(정착 2초 확인 + 10초)부터
        pol.dataActivity(480 * S, false);
        pol.advance(470 * S);
        check("평가 402초 + 데이터 60초 = 462초 통과", env.logged("probe_result result pass"), true);
        check("통과 뒤 WATCH", pol.state, Policy.State.WATCH);
        pol.advance(1000 * S);
        check("T_clear(300초) 뒤 GOOD", pol.state, Policy.State.GOOD);
    }

    static void idleDropsDoNotCount() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        for (long s : new long[]{10, 50, 90}) pol.nrOff(s * S, 30 * S, 5 * S, true);
        pol.advance(600 * S);
        check("무활동 끊김은 세지 않음", env.writes, "[]");
        check("T_clear 뒤 GOOD", pol.state, Policy.State.GOOD);
    }

    static void lteModeIsInactive() {
        Object[] o = start(new Params(), false, null);
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("LTE 모드는 쓰기 없음", env.writes, "[]");
        check("INACTIVE", pol.state, Policy.State.INACTIVE);
        check("작동 표시 없음", env.indicatorOn, false);
    }

    static void callClearsWindowAndGraceIgnored() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        pol.call(60 * S, 2);
        drop(pol, 80);
        drop(pol, 90);
        pol.call(100 * S, 0);
        drop(pol, 120); // 통화 종료 후 유예(30초) 안
        drop(pol, 140); // 새 창 1회째
        drop(pol, 150); // 2회째
        pol.advance(200 * S);
        check("통화·유예 중 끊김 제외 → 전환 없음", env.writes, "[]");
    }

    static void screenOffIsObservationGap() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        pol.screen(60 * S, false);
        pol.screen(70 * S, true);
        drop(pol, 90);
        pol.advance(600 * S);
        check("화면 꺼짐 뒤 창 새로 시작", env.writes, "[]");
    }

    static void oosWaitsForServiceThenCools() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        pol.oos(20 * S, 0, true);
        check("서비스 없음 동안 쓰기 없음", env.writes, "[]");
        pol.service(25 * S);
        check("복구 때 다시 판정 → 25초 LTE", env.writes, "[lte@25]");
    }

    static void expiredOosDoesNotTrigger() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        pol.oos(20 * S, 0, true);
        pol.service(500 * S);
        check("창에서 만료된 OOS는 전환 안 함", env.writes, "[]");
    }

    static void probeFailureBacksOffAfterBudgetGap() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.advance(390 * S); // 재시험, 평가 창 402초
        drop(pol, 410);
        drop(pol, 420); // 실패 판정, 최소 간격(390+120=510)까지 대기
        pol.advance(2000 * S);
        check("실패 뒤 510초 LTE, 레벨 1 쿨다운 600초 → 1110초 재시험", env.writes, "[lte@90, nr@390, lte@510, nr@1110]");
        check("레벨 1", pol.level, 1);
    }

    static void cooldownWaitsForScreenOn() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.screen(200 * S, false);
        pol.advance(600 * S);
        check("화면 꺼짐 중 재시험 안 함", env.writes, "[lte@90]");
        pol.screen(700 * S, true);
        check("화면 켜질 때 재시험", env.writes, "[lte@90, nr@700]");
    }

    static void dualSimNeverSwitches() {
        Object[] o = start(new Params(), true, "dual_sim");
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("듀얼 SIM은 OBSERVE", pol.state, Policy.State.OBSERVE);
        check("쓰기 없음", env.writes, "[]");
    }

    static void hourlyBudget() {
        Params p = new Params();
        p.cBase = 60 * S;
        p.bGap = 10 * S;
        Object[] o = start(p, true, null);
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        long t = 10;
        for (int i = 0; i < 4; i++) {
            drop(pol, t);
            drop(pol, t + 20);
            drop(pol, t + 40);
            t += 900;
        }
        pol.advance(7200 * S);
        int inHour = 0;
        for (String w : env.writes) if (Long.parseLong(w.substring(w.indexOf('@') + 1)) < 3600) inHour++;
        check("첫 한 시간 전환 4회 이하", inHour <= 4, true);
    }

    static void probeWindowRestartsAfterScreenOff() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.advance(390 * S);
        drop(pol, 410); // 평가 창(402~) 1회째
        pol.screen(420 * S, false);
        pol.screen(500 * S, true); // 평가 창 500초부터 다시
        drop(pol, 520); // 새 창 1회째
        pol.advance(1000 * S);
        check("재시험 실패 아님", env.writes, "[lte@90, nr@390]");
        check("800초 판정 불가(500 + P_max 300)", env.logged("probe_result result undecided"), true);
    }

    static void holdDuringSettleKeepsSettleEnd() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.advance(390 * S);
        pol.screen(391 * S, false);
        pol.screen(392 * S, true);
        pol.oos(395 * S, 0, true); // 정착 구간 사건 → 판정에 안 씀
        pol.service(396 * S);
        pol.advance(1000 * S);
        check("정착 중 OOS로 실패하지 않음", env.writes, "[lte@90, nr@390]");
    }

    static void externalRestrictionHoldsProbe() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.restriction(200 * S, 1, false);
        pol.advance(500 * S);
        check("제한 중 재시험 안 함", env.writes, "[lte@90]");
        pol.restriction(600 * S, 1, true);
        check("해제 때 재시험", env.writes, "[lte@90, nr@600]");
    }

    // ---------------------------------------------------------------- Phase 2 고유

    static void settleTimeoutSafeStopsAndRestores() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        pol.netOk(89 * S, true);
        drop(pol, 90);
        pol.netOk(91 * S, false); // 전환 뒤 망이 돌아오지 않음
        pol.advance(200 * S);
        check("정착 시간 초과 → SAFE_STOP", pol.state, Policy.State.SAFE_STOP);
        check("원래 모드로 되돌림(120초)", env.writes, "[lte@90, nr@120]");
        check("작동 표시 끔", env.indicatorOn, false);
        pol.advance(2000 * S);
        check("SAFE_STOP 뒤 추가 쓰기 없음", env.writes.size(), 2);
    }

    static void adoptedWriteBecomesUserMode() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        env.results.add(Policy.Result.adopted(false)); // 쓰는 사이 사용자가 LTE를 고름
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("사용자 LTE 선택 채택 → INACTIVE", pol.state, Policy.State.INACTIVE);
        pol.advance(1000 * S);
        check("이후 쓰기 없음", env.writes, "[lte@90]");
    }

    static void failedWriteHoldsUntilResume() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        env.results.add(Policy.Result.of(Policy.Kind.FAILED));
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        drop(pol, 100);
        drop(pol, 110);
        pol.advance(1000 * S);
        check("실패 뒤 재시도 없음", env.writes, "[lte@90]");
        check("실패 보류", pol.failedHold, true);
        pol.resume(1100 * S);
        check("재개 → 활성화 판정 WATCH", pol.state, Policy.State.WATCH);
    }

    static void rolledBackGoesObserve() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        env.results.add(Policy.Result.of(Policy.Kind.ROLLED_BACK));
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("S2 롤백 → OBSERVE", pol.state, Policy.State.OBSERVE);
        pol.advance(1000 * S);
        check("이후 쓰기 없음", env.writes, "[lte@90]");
        check("작동 표시 끔", env.indicatorOn, false);
    }

    static void stopRestoresAndDefersDuringCall() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.call(150 * S, 2);
        pol.stop(160 * S);
        check("통화 중 종료는 미룸", env.stopDone, 0);
        pol.call(170 * S, 0);
        pol.advance(190 * S);
        check("유예 중에도 미룸", env.stopDone, 0);
        pol.advance(201 * S);
        check("유예 끝(200초) 뒤 되돌리고 종료", env.writes, "[lte@90, nr@200]");
        check("종료 완료", env.stopDone, 1);
    }

    static void keepLteMakesLteOriginal() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("LTE로 계속 쓰기 수락", pol.keepLte(150 * S), true);
        check("INACTIVE", pol.state, Policy.State.INACTIVE);
        check("원래 모드 LTE로 확정 요청", env.keepCalls, 1);
        pol.advance(2000 * S);
        check("이후 쓰기 없음", env.writes, "[lte@90]");
    }

    static void blockedDuringCooldownRestoresOnce() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.setBlocked(150 * S, "indicator_lost");
        check("원래 모드로 1회 복원", env.writes, "[lte@90, nr@150]");
        check("OBSERVE", pol.state, Policy.State.OBSERVE);
        pol.advance(2000 * S);
        check("이후 쓰기 없음", env.writes.size(), 2);
    }

    static void userSelectsLteDuringCooldown() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.userSelected(150 * S, false);
        pol.advance(2000 * S);
        check("사용자 LTE 선택 → INACTIVE, 재시험 없음", env.writes, "[lte@90]");
        check("작동 표시 끔", env.indicatorOn, false);
    }

    static void heavyTrafficDefersProbe() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        env.heavy = true;
        pol.advance(395 * S);
        check("대용량 트래픽 중 재시험 미룸", env.writes, "[lte@90]");
        env.heavy = false;
        pol.advance(500 * S);
        check("30초 뒤(420초) 재시험", env.writes, "[lte@90, nr@420]");
    }

    static void indicatorFollowsControl() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        check("5G 우선·제어 가능 → 표시", env.indicatorOn, true);
        pol.userSelected(10 * S, false);
        check("LTE 선택 → 표시 없음", env.indicatorOn, false);
        pol.userSelected(20 * S, true);
        check("다시 5G 우선 → 표시", env.indicatorOn, true);
    }

    static void testCooldownUsesGuardsAndShortRest() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        pol.screen(5 * S, false);
        check("화면 꺼짐이면 시험 쿨다운도 안 함", pol.testCooldown(10 * S, 60 * S), false);
        pol.screen(20 * S, true);
        check("화면 켜짐 → 시험 쿨다운", pol.testCooldown(30 * S, 60 * S), true);
        pol.advance(95 * S);
        check("60초 쉬어도 최소 간격(120초) 전에는 재시험 안 함", env.writes, "[lte@30]");
        pol.advance(200 * S);
        check("최소 간격 뒤 150초 재시험", env.writes, "[lte@30, nr@150]");
        check("쉬는 시간 뒤에는 평소 규칙", pol.level, 0);
    }

    /** 기기 시험(2026-09-28)에서 발견: 사용자가 5G 우선을 고른 직후의 망 재접속 끊김을 불안정으로 세어 곧바로 LTE로 내렸다. */
    static void userModeChangeTransientIsNotCounted() {
        Object[] o = start(new Params(), false, null); // LTE 모드에서 시작
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        pol.userSelected(100 * S, true); // 사용자가 5G 우선 선택
        pol.netOk(100_300, false);
        pol.oos(100_300, 3 * S, true);
        pol.service(102 * S);
        pol.netOk(104 * S, true); // 104초 망 정상 → 114초까지 조용한 구간
        pol.advance(113 * S);
        check("모드 변경 직후 끊김은 세지 않음", env.writes, "[]");
        check("WATCH 유지", pol.state, Policy.State.WATCH);
        pol.oos(120 * S, 0, true); // 조용한 구간 뒤의 끊김은 평소대로
        pol.service(121 * S);
        check("조용한 구간 뒤 OOS는 평소 규칙", env.writes, "[lte@121]");
    }

    /** 기기 시험(2026-09-28)에서 발견: 정착 시계가 판정 시각부터 돌아 쓰기(약 1.7초) 직후의 끊김보다 먼저 정상 확인이 났다. */
    static void settleClockStartsWhenWriteEnds() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        env.writeTakesMs = 1700;
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90); // 판정 90초, 쓰기 끝 91.7초
        pol.netOk(92 * S, false); // 쓰기 직후 끊김
        pol.netOk(93_200, true);
        pol.advance(93_500);
        check("정착 확인은 쓰기 끝 + 2초(93.7초) 뒤에만", pol.settling(), true);
        pol.advance(200 * S);
        check("비용 기록: 끊김 1.2초, 쓰기 끝에서 1.5초 만에 회복",
                env.logged("switch_cost outMs 1200 recoverMs 1500 confirmMs 2000"), true);
    }

    static void quietAfterStartIgnoresTransient() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        pol.startQuiet(0);
        pol.netOk(500, false);
        pol.oos(500, 0, true);
        pol.service(1500);
        pol.netOk(3 * S, true);
        pol.advance(20 * S);
        check("시작 직후 끊김은 세지 않음", env.writes, "[]");
        check("조용한 구간 뒤 WATCH", pol.state, Policy.State.WATCH);
    }

    /** 검증 지적 5: 통화 확인 뒤 대용량 트래픽 1초 측정 중 시작된 통화를 놓치던 문제 → 통화 조회를 쓰기 바로 앞으로. */
    static void callStartingDuringHeavyCheckBlocks() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        env.duringHeavy = () -> env.callNow = "call";
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("측정 중 시작된 통화 → 전환 안 함", env.writes, "[]");
    }

    static void blockedWriteDoesNotSwitch() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        env.results.add(Policy.Result.of(Policy.Kind.BLOCKED)); // 쓰기 직전 통화 재확인에 막힘
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("쓰기 직전 막힘 → 경계 유지", pol.state, Policy.State.WATCH);
        check("막힘은 실패 보류가 아님", pol.failedHold, false);
    }

    /** 검증 지적 6: 쿨다운 중 LTE OOS를 5G 품질 사건으로 처리해 WATCH로 바뀌고 재시험을 잃던 문제. */
    static void lteOosDuringCooldownKeepsCooldown() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.oos(380 * S, 0, true);
        check("쿨다운 유지", pol.state, Policy.State.COOLDOWN);
        pol.advance(500 * S);
        pol.service(600 * S);
        check("서비스 복구 때 재시험", env.writes, "[lte@90, nr@600]");
    }

    /** 검증 지적 7-1: 보류 전 사용 누적이 남아 재시험을 일찍 통과시키던 문제. */
    static void probeUseResetsAfterHold() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.advance(390 * S); // 재시험, 평가 402초~
        pol.dataActivity(402 * S, true);
        pol.screen(441 * S, false); // 38초 누적 뒤 보류
        pol.screen(500 * S, true);  // 새 평가 창 500초~
        pol.advance(559 * S);
        check("559초엔 아직(새 창 누적 59초)", env.logged("probe_result result pass"), false);
        pol.advance(561 * S);
        check("560초 통과", env.logged("probe_result result pass"), true);
    }

    /** 검증 지적 7-2: 3초 이내 멈춤을 사용 시간에서 빼던 문제(기록 규칙은 사용으로 셈). */
    static void probeUseCountsShortGaps() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.advance(390 * S);
        pol.dataActivity(402 * S, true);
        pol.dataActivity(430 * S, false);
        pol.dataActivity(432 * S, true); // 2초 멈춤 = 사용
        pol.advance(461 * S);
        check("461초엔 아직", env.logged("probe_result result pass"), false);
        pol.advance(463 * S);
        check("462초 통과", env.logged("probe_result result pass"), true);
    }

    /** 검증 지적 8: 통화 조회 실패로 막힌 재시험이 지난 시각 예약으로 남아 쉬지 않고 반복되던 문제. */
    static void blockedProbeDoesNotSpin() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        env.callNow = "call_unknown";
        pol.advance(390 * S);
        check("막힌 재시험의 다음 확인은 30초 뒤", pol.nextDeadline(), 420 * S);
        env.callNow = null;
        pol.advance(421 * S);
        check("풀리면 재시험", env.writes, "[lte@90, nr@420]");
    }

    /** 검증 지적 9: 종료 복원 실패에도 정상 종료로 저장하던 문제. */
    static void stopRestoreFailureIsNotClean() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        env.results.add(Policy.Result.of(Policy.Kind.FAILED));
        pol.stop(150 * S);
        check("복원 실패 → 종료는 하되 정상 종료 아님", env.stopDone + "/" + env.lastStopRestored, "1/false");
        Object[] o2 = start();
        Policy pol2 = (Policy) o2[0];
        FakeEnv env2 = (FakeEnv) o2[1];
        pol2.stop(10 * S);
        check("되돌릴 것이 없으면 정상 종료", env2.stopDone + "/" + env2.lastStopRestored, "1/true");
    }

    /** 검증 2회차: 쓰는 사이 사용자 선택은 채택했지만 화면 키를 맞추지 못함 → 사용자 모드를 원래 모드로, 제어는 안전 정지. */
    static void brokenAdoptedStopsWithUserMode() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        env.results.add(Policy.Result.brokenAdopted(false));
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("안전 정지", pol.state, Policy.State.SAFE_STOP);
        check("원래 모드 = 사용자 선택(LTE)", pol.origNr, false);
        check("컨트롤러가 막아 둔 것 없음", pol.nrAllowedNow, true);
        pol.advance(2000 * S);
        check("추가 쓰기 없음", env.writes, "[lte@90]");
    }

    /** 사용자 지적(2026-09-28): 5G 우선 모드에서 Wi-Fi로 인터넷을 쓸 때도 점이 떠 있었다 → Wi-Fi 동안은 쉬고 점을 숨김. */
    static void wifiHidesIndicatorAndHolds() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        check("5G 우선·셀룰러 → 점", env.indicatorOn, true);
        pol.wifi(5 * S, true);
        check("Wi-Fi → 점 숨김", env.indicatorOn, false);
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        check("Wi-Fi 중 끊김은 세지 않음", env.writes, "[]");
        pol.wifi(100 * S, false);
        check("Wi-Fi 끊김 → 점 다시", env.indicatorOn, true);
        drop(pol, 110);
        drop(pol, 150);
        drop(pol, 190);
        check("셀룰러로 돌아오면 평소 규칙", env.writes, "[lte@190]");
    }

    /** 사용자 결정(2026-09-28): LTE로 쉬게 해 둔 채 Wi-Fi에 붙으면 사용자의 원래 모드(5G 우선)로 되돌리고 점을 숨긴다. */
    static void wifiRestoresOriginalFiveG() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90); // 90초 LTE로 쉼
        pol.wifi(150 * S, true);
        check("Wi-Fi 붙음 → 원래 5G 우선으로 되돌림", env.writes, "[lte@90, nr@150]");
        check("되돌린 뒤 경계 상태(Wi-Fi 동안 쉼)", pol.state, Policy.State.WATCH);
        check("Wi-Fi 동안 점 없음", env.indicatorOn, false);
        pol.wifi(400 * S, false);
        check("Wi-Fi 끊김 → 점 다시", env.indicatorOn, true);
        pol.advance(1000 * S);
        check("그 뒤 추가 쓰기 없음", env.writes.size(), 2);
    }

    /** 사용자 강조: 원래 LTE 우선이던 사용자가 Wi-Fi에 붙었다가 모바일로 돌아와도 강제로 5G로 가면 안 된다. */
    static void wifiNeverForcesFiveGWhenUserChoseLte() {
        Object[] o = start(new Params(), false, null); // 원래 LTE 우선
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        pol.wifi(10 * S, true);
        pol.wifi(100 * S, false);
        pol.wifi(200 * S, true);
        pol.advance(1000 * S);
        check("원래 LTE: Wi-Fi 오가도 쓰기 없음", env.writes, "[]");
        check("원래 LTE: 계속 INACTIVE, 점 없음", pol.state + "/" + env.indicatorOn, "INACTIVE/false");
        // 5G 우선에서 LTE로 쉬던 중 사용자가 LTE를 고른 뒤 Wi-Fi에 붙는 경우: 사용자 선택(LTE)을 따른다
        Object[] o2 = start();
        Policy pol2 = (Policy) o2[0];
        FakeEnv env2 = (FakeEnv) o2[1];
        drop(pol2, 10);
        drop(pol2, 50);
        drop(pol2, 90);
        pol2.userSelected(120 * S, false); // 감지된 LTE 선택(예: 5G 우선을 눌렀다가 LTE 우선). 같은 LTE 재선택은 감지 안 됨(DESIGN §5.11 17)
        pol2.wifi(150 * S, true);
        pol2.wifi(300 * S, false);
        pol2.advance(1000 * S);
        check("사용자가 LTE를 고른 뒤 Wi-Fi: 5G로 되돌리지 않음", env2.writes, "[lte@90]");
    }

    static void wifiRestoreWaitsForCall() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.call(140 * S, 2);
        pol.wifi(150 * S, true);
        check("통화 중 Wi-Fi: 되돌리지 않음", env.writes, "[lte@90]");
        check("통화 중 Wi-Fi: 점 숨김", env.indicatorOn, false);
        pol.call(200 * S, 0);
        pol.advance(229 * S);
        check("통화 유예 중: 아직", env.writes, "[lte@90]");
        check("통화 유예 중 Wi-Fi: 점 숨김", env.indicatorOn, false);
        pol.advance(231 * S);
        check("유예 끝나면 되돌림", env.writes, "[lte@90, nr@230]");

        // 일시정지 중에는 Wi-Fi여도 쓰지 않는다. 재개하면 그때 되돌린다
        Object[] o2 = start();
        Policy pol2 = (Policy) o2[0];
        FakeEnv env2 = (FakeEnv) o2[1];
        drop(pol2, 10);
        drop(pol2, 50);
        drop(pol2, 90);
        pol2.pause(100 * S);
        pol2.wifi(150 * S, true);
        check("일시정지 중 Wi-Fi: 쓰지 않음", env2.writes, "[lte@90]");
        pol2.resume(200 * S);
        check("재개하면 되돌림", env2.writes, "[lte@90, nr@200]");

        // Wi-Fi 중 통화가 시작돼도 점은 다시 뜨지 않는다(보류 사유는 통화가 앞서지만 표시는 Wi-Fi 여부)
        Object[] o3 = start();
        Policy pol3 = (Policy) o3[0];
        FakeEnv env3 = (FakeEnv) o3[1];
        pol3.wifi(5 * S, true);
        pol3.call(10 * S, 2);
        check("Wi-Fi 중 통화: 점 숨김", env3.indicatorOn, false);
        pol3.call(20 * S, 0);
        pol3.advance(40 * S);
        check("Wi-Fi 중 통화 유예: 점 숨김", env3.indicatorOn, false);
        pol3.advance(60 * S);
        check("유예 끝, Wi-Fi 그대로: 점 숨김", env3.indicatorOn, false);
        pol3.wifi(70 * S, false);
        check("Wi-Fi 끊김: 점 다시", env3.indicatorOn, true);
    }

    /** 재시험 예약 시각이 지난 뒤 처음 처리되는 사건이 사용자의 LTE 선택이면, 재시험(5G 쓰기) 없이 사용자 선택을 따른다. */
    static void userChoiceBeatsOverdueProbe() {
        // 비교 기준: 사용자 선택이 없으면 쉬는 시간이 끝난 시각에 재시험한다
        Object[] o0 = start();
        Policy pol0 = (Policy) o0[0];
        FakeEnv env0 = (FakeEnv) o0[1];
        drop(pol0, 10);
        drop(pol0, 50);
        drop(pol0, 90);
        pol0.advance(391 * S);
        check("기준: 390초에 재시험", env0.writes, "[lte@90, nr@390]");

        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90);
        pol.userSelected(391 * S, false); // 390초 예약이 아직 처리되지 않은 채 사용자 선택이 먼저 도착
        check("밀린 재시험보다 사용자 LTE 선택이 먼저: 5G 쓰기 없음", env.writes, "[lte@90]");
        check("사용자 LTE 선택 → INACTIVE", pol.state, Policy.State.INACTIVE);
        pol.wifi(400 * S, true);
        pol.wifi(500 * S, false);
        pol.advance(3000 * S);
        check("이후에도 쓰기 없음", env.writes, "[lte@90]");
    }

    /** 쓰기가 BLOCKED이면서 다시 쓸 시각을 알려 주면(사용자 선택 확인 창 끝, §5.12) 30초가 아니라 그 시각에 재시험한다. */
    static void blockedWithRetryHintProbesAtHint() {
        Object[] o = start();
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        drop(pol, 10);
        drop(pol, 50);
        drop(pol, 90); // 390초 재시험 예정
        env.blockUntil = 393 * S; // 확인 창이 393초에 끝남
        pol.advance(390 * S);
        check("확인 창: 재시험 미룸", env.writes, "[lte@90]");
        env.blockUntil = -1;
        pol.advance(394 * S);
        check("확인 창 끝 시각에 재시험(30초 뒤가 아님)", env.writes, "[lte@90, nr@393]");
    }

    /**
     * 검증 지적(§5.12): 사용자가 LTE → 5G를 골랐는데 실제 USER가 아직 LTE(경합)면, 엔진이 "5G 허용 중"으로 착각하지 않고
     * 쉬는 중으로 봐서 조건이 되면 5G를 쓴다(사용자 선택으로 수렴).
     */
    static void userPicks5gWhileActualLteRetests() {
        Object[] o = start(new Params(), false, null); // 원래 LTE 우선
        Policy pol = (Policy) o[0];
        FakeEnv env = (FakeEnv) o[1];
        pol.userSelected(100 * S, true, false); // 5G 선택, 실제는 LTE
        check("선택 5G·실제 LTE: 쉬는 중(재시험 대기)", pol.state, Policy.State.COOLDOWN);
        check("선택 5G·실제 LTE: 점 표시", env.indicatorOn, true);
        pol.advance(200 * S);
        check("정착 뒤 5G 쓰기(사용자 선택으로 수렴)", env.writes.size() == 1 && env.writes.get(0).startsWith("nr@"), true);
        Object[] o2 = start(new Params(), false, null);
        Policy pol2 = (Policy) o2[0];
        pol2.userSelected(100 * S, true, true);
        check("선택 5G·실제 5G: 경계 상태", pol2.state, Policy.State.WATCH);
    }

    // ---------------------------------------------------------------- 도우미

    static void check(String what, Object got, Object want) {
        String g = got instanceof List ? Arrays.toString(((List<?>) got).toArray()) : String.valueOf(got);
        String w = String.valueOf(want);
        if (g.equals(w)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": got " + g + ", want " + w);
        }
    }

    private PolicyTest() {
    }
}
