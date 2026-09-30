package nrc.controller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 정책 엔진(DESIGN §5.5). 안드로이드 의존 없음 → PC에서 시험(bash phoneapp/test.sh). daemon/src/nrc/Policy.java에서 옮김(2026-09-30).
 * 모든 입력은 한 스레드(데몬 작업 스레드)에서 시각 t(부팅 후 경과 ms)와 함께 들어온다.
 * 실제 쓰기·표시·기록은 Env가 한다. 엔진은 "언제 무엇을 쓸지"만 정한다.
 *
 * 규칙은 nrctl report의 시뮬레이션(pc/nrctl.py Sim)과 같다. 다른 점:
 * - 정착 구간: 실제 전환 뒤 망 정상(데이터 등록 + 데이터 연결) 확인 시각 + T_settle. T_settle_max 안에 확인이 없으면 SAFE_STOP.
 * - 쓰기 결과: 성공 / 사용자 선택 채택 / 실패(보류, 재개 필요) / S2 롤백(관찰 전용) / 롤백 실패(SAFE_STOP).
 * - 원래 모드로 되돌리는 쓰기(제어 불가·SAFE_STOP·종료)는 통화 가드만 본다(DESIGN §5.5.2 Phase 2 규칙).
 */
final class Policy {
    enum State { INACTIVE, OBSERVE, GOOD, WATCH, COOLDOWN, PROBE, SAFE_STOP }

    /** BLOCKED = 쓰기 직전 통화 재확인에 막혀 쓰지 않았다(가드 막힘과 같게 처리). */
    enum Kind { OK, ADOPTED, FAILED, ROLLED_BACK, BROKEN, BLOCKED }

    /**
     * 쓰기 결과. ADOPTED면 쓰는 중에 관측된 사용자 값의 NR 포함 여부를 함께 준다.
     * doneAt = 쓰기를 끝낸 시각(부팅 후 경과 ms, 모르면 -1). BLOCKED면 다시 쓸 수 있는 시각(모르면 -1). 정착 시계는 이 시각부터 잰다
     * (판정 시각부터 재면 쓰기에 걸린 시간만큼 정착 확인이 앞당겨져, 전환 직후의 끊김보다 먼저 "정상"으로 확인된다 — 기기 시험 실측).
     */
    static final class Result {
        final Kind kind;
        /** 사용자 값을 채택했는지(ADOPTED, 또는 채택은 했지만 화면 키를 맞추지 못한 BROKEN). */
        final boolean adopted;
        final boolean adoptedNr;
        /** 채택 순간 실제 USER에 NR이 있는지(사용자 선택은 5G인데 아직 LTE일 수 있다, §5.12). */
        final boolean adoptedActualNr;
        final long doneAt;

        Result(Kind kind, boolean adopted, boolean adoptedNr, long doneAt) {
            this(kind, adopted, adoptedNr, adoptedNr, doneAt);
        }

        Result(Kind kind, boolean adopted, boolean adoptedNr, boolean adoptedActualNr, long doneAt) {
            this.kind = kind;
            this.adopted = adopted;
            this.adoptedNr = adoptedNr;
            this.adoptedActualNr = adoptedActualNr;
            this.doneAt = doneAt;
        }

        static Result of(Kind k) {
            return new Result(k, false, false, -1);
        }

        static Result of(Kind k, long doneAt) {
            return new Result(k, false, false, doneAt);
        }

        static Result adopted(boolean nr) {
            return new Result(Kind.ADOPTED, true, nr, -1);
        }

        /** 사용자 선택(nr) 채택. 실제 USER의 NR 포함 여부(actualNr)가 선택과 다를 수 있다(설정 키 기준 채택, §5.12). */
        static Result adopted(boolean nr, boolean actualNr) {
            return new Result(Kind.ADOPTED, true, nr, actualNr, -1);
        }

        /** 쓰는 사이 사용자가 모드를 골라 채택했지만, 컨트롤러가 덮은 설정 화면 키를 사용자 값에 맞추지 못했다. */
        static Result brokenAdopted(boolean nr) {
            return new Result(Kind.BROKEN, true, nr, -1);
        }
    }

    interface Env {
        /** 원래 모드 기준으로 NR 비트만 바꾸는 쓰기(S2). 동기 실행. t = 판정 시각. */
        Result write(long t, boolean allowNr, String why);

        /** 쓰기 직전에 다시 보는 통화 가드(모든 SIM 통화·긴급). 문제 없으면 null. */
        String callNow();

        /** 대용량 트래픽 진행 중이면 true(가드 6). */
        boolean heavyNow();

        void indicator(boolean on);

        void log(String ev, Object... kv);

        /** 상태 파일 갱신용. */
        void persist(State s, boolean nrAllowed);

        /** "LTE로 계속 쓰기": 지금 값(LTE)을 원래 모드로 확정한다(쓰기 없음). */
        void keepCurrentAsOriginal();

        /** 종료 요청 처리가 끝났다. restored = 되돌릴 것이 없었거나 되돌리기에 성공(실패면 다음 시작이 남은 LTE로 판정해야 한다). */
        void stopDone(boolean restored);
    }

    static final long HEAVY_RETRY_MS = 30_000;
    /** 시간으로 풀리지 않는 이유(통화 조회 실패·서비스 없음 등)로 막힌 전환을 다시 보는 간격. 사건(화면·서비스·통화 종료)이 오면 그때 바로 본다. */
    static final long RETRY_POLL_MS = 30_000;

    // 예약 종류(같은 시각이면 번호가 작은 것부터)
    private static final int D_SETTLE_CHECK = 0, D_SETTLE_TIMEOUT = 1, D_SETTLE_END = 2, D_HOLD_CHECK = 3,
            D_CLEAR = 4, D_STABLE = 5, D_PROBE = 6, D_RETRY = 7, D_PMAX = 8, D_PASS = 9;

    private final Params p;
    private final Env env;

    State state = State.INACTIVE;
    boolean origNr;
    /** 제어 불가 조건(null이면 제어 가능). */
    String blocked;
    /** 컨트롤러가 NR을 막아 둔 상태가 아니면 true. */
    boolean nrAllowedNow = true;
    boolean failedHold;
    boolean paused;
    boolean pendingRestore;
    boolean stopPending;
    private boolean stopDeferredLogged;
    int level;
    String hold;

    // 가드 입력
    private Boolean screen;
    private int call;
    private long callEnd = -1;
    private boolean inService = true;
    private boolean roaming;
    private boolean wifi;
    private Boolean indicatorShown;
    private final Map<Integer, Boolean> restrict = new HashMap<>();

    // 특징
    private final ArrayDeque<long[]> drops = new ArrayDeque<>(); // {t, dwellMs}
    private final ArrayDeque<Long> oos = new ArrayDeque<>();
    private long lastBad = -1;
    private long watchSince = -1;
    private long stableSince = -1;

    // 쿨다운·재시험
    private long coolUntil = -1;
    private long evalStart = -1;
    private int probeDrops;
    private int probeOos;
    private String pendingCause;
    private int pendingInc;
    private long retryAt = -1;
    private boolean dataActive;
    private long useAcc;
    private long useFrom = -1;
    /** 평가 중 데이터가 멈춘 시각(3초 안에 재개되면 그 틈도 사용으로 센다 — 기록·시뮬레이션의 사용 구간 규칙과 같게). */
    private long useGapFrom = -1;

    // 정착(컨트롤러 쓰기 뒤, 또는 사용자 모드 선택 뒤)
    private boolean settling;
    /** true = 컨트롤러가 쓴 뒤의 정착(시간 초과 시 SAFE_STOP), false = 사용자 모드 선택 뒤의 조용한 구간(시간 초과 시 그냥 끝). */
    private boolean settleByController;
    private long settleStart = -1;
    private boolean settleChecked;
    private long settleConfirmed = -1;
    private boolean netOk = true;
    private long settleOosFrom = -1;
    private long settleOosMs;
    private long settleRecoveredAt = -1;

    private final ArrayDeque<Long> switches = new ArrayDeque<>();
    private long testCoolMs = -1;
    /** 마지막으로 처리한 시각. 판정은 이보다 과거로 가지 않는다. */
    private long processed = Long.MIN_VALUE;

    Policy(Params p, Env env) {
        this.p = p;
        this.env = env;
    }

    // ================================================================ 입력(공개)

    /** 활성화 판정(DESIGN §5.5.2): 시작·재개 때. nr = 사용자가 고른 원래 모드에 NR 포함. */
    void setMode(long t, boolean nr) {
        t = touch(t);
        applyMode(t, nr, "start");
        after(t);
    }

    /** 시작 직후 조용한 구간: 직전에 모드가 바뀌었을 수 있어(예: 종료 때 되돌리기 직후 재시작) 망이 안정될 때까지 세지 않는다. */
    void startQuiet(long t) {
        t = touch(t);
        if (controlled() && !settling) startSettle(t, false);
        after(t);
    }

    /** 사용자가 설정에서 모드를 골랐다. 실제 USER도 그 선택과 같다고 본다. */
    void userSelected(long t, boolean nr) {
        userSelected(t, nr, nr);
    }

    /**
     * 사용자가 설정에서 모드를 골랐다(외부 USER 변경, DESIGN §5.6.6 2번). actualNr = 지금 실제 USER에 NR이 있는지.
     * 사용자 선택이 5G인데 실제가 아직 LTE면(설정 키 기준 채택, §5.12) 쉬는 중으로 보고 조건이 되면 곧바로 재시험한다.
     * 사용자 선택은 밀린 예약(예: 쉬는 시간이 끝난 재시험)보다 앞선다. 예약을 먼저 돌리면 사용자가 방금 고른 LTE 위에
     * 5G를 쓸 수 있다(검증 재현). 그래서 먼저 적용하고, 새 상태에서 남은 예약만 처리한다.
     */
    void userSelected(long t, boolean nr, boolean actualNr) {
        if (t < processed) t = processed;
        processed = t;
        applyUser(t, nr, actualNr);
        runDeadlines(t);
        after(t);
    }

    /** 제어 불가 조건 변화(DESIGN §5.5.1 OBSERVE 행). null = 제어 가능. */
    void setBlocked(long t, String reason) {
        t = touch(t);
        applyBlocked(t, reason);
        after(t);
    }

    void screen(long t, boolean on) {
        t = touch(t);
        screen = on;
        syncHold(t);
        after(t);
    }

    void call(long t, int st) {
        t = touch(t);
        if (st == 0 && call != 0) callEnd = t;
        call = st;
        syncHold(t);
        after(t);
    }

    /** 기본 인터넷 경로가 Wi-Fi인지(셀룰러 아님). Wi-Fi 동안은 5G 품질을 판정할 대상이 없어 쉰다. */
    void wifi(long t, boolean on) {
        t = touch(t);
        wifi = on;
        syncHold(t);
        updateIndicator();
        after(t);
    }

    void roaming(long t, boolean r) {
        t = touch(t);
        roaming = r;
        syncHold(t);
        after(t);
    }

    /** USER 외 사유의 허용 타입(가드 8). 알림이 온 사유의 마지막 값만 쓴다. */
    void restriction(long t, int reason, boolean nr) {
        t = touch(t);
        if (reason != 0) restrict.put(reason, nr);
        syncHold(t);
        after(t);
    }

    void dataActivity(long t, boolean active) {
        t = touch(t);
        refreshUse(t);
        if (!active && dataActive && useFrom >= 0) {
            useGapFrom = t; // 3초 안에 재개되면 이 틈을 사용으로 더한다
        } else if (active && !dataActive && useGapFrom >= 0) {
            if (t - useGapFrom <= p.useGap && useCounts(t)) useAcc += t - useGapFrom;
            useGapFrom = -1;
        }
        dataActive = active;
        after(t);
    }

    /** 망 정상 여부(데이터 등록 IN_SERVICE ∧ 기본 데이터 연결). 정착 확인에 쓴다. */
    void netOk(long t, boolean ok) {
        t = touch(t);
        netOk = ok;
        if (settling) {
            if (!ok && settleOosFrom < 0) settleOosFrom = t;
            if (ok && settleOosFrom >= 0) {
                settleOosMs += t - settleOosFrom;
                settleOosFrom = -1;
                settleRecoveredAt = t;
            }
            if (settleChecked) confirmSettle(t);
        }
        after(t);
    }

    /** 데이터 서비스 끊김(데이터 등록이 IN_SERVICE를 벗어남). */
    void oos(long t, long sinceDataMs, boolean screenOn) {
        t = touch(t);
        inService = false;
        if (counting(screenOn) && state != State.COOLDOWN) { // 쿨다운 중(LTE)의 끊김은 5G 품질 사건이 아니다
            if (state == State.PROBE) {
                if (evalOpen(t) && pendingCause == null) {
                    probeOos++;
                    countedOos++;
                    trySwitchLte(t, "probe_oos", 1);
                }
            } else {
                oos.addLast(t);
                countedOos++;
                lastBad = t;
                to(t, State.WATCH, "oos");
                String c = cause(t);
                if (c != null) trySwitchLte(t, c, 0); // 서비스 없음으로 막힘 → 복구 때 다시 판정(해석 1)
            }
        }
        after(t);
    }

    void service(long t) {
        t = touch(t);
        inService = true;
        if (hold == null && !settling && writable()) {
            if (pendingCause != null || state == State.COOLDOWN) {
                retryAfterEvent(t);
            } else if (state == State.WATCH) {
                String c = cause(t); // 창에서 만료된 사건은 여기서 빠진다
                if (c != null) trySwitchLte(t, c, 0);
            }
        }
        after(t);
    }

    /** NR 연결이 끊김. sinceDataMs = 마지막 데이터 활동 뒤 경과(-1 = 기록 없음). */
    void nrOff(long t, long sinceDataMs, long dwellMs, boolean screenOn) {
        t = touch(t);
        boolean active = sinceDataMs >= 0 && sinceDataMs <= p.tActive;
        if (active && counting(screenOn) && state != State.COOLDOWN) {
            if (state == State.PROBE) {
                if (evalOpen(t) && pendingCause == null) {
                    probeDrops++;
                    countedNr++;
                    if (probeDrops >= p.nProbe) trySwitchLte(t, "probe_drops", 1);
                }
            } else {
                drops.addLast(new long[]{t, dwellMs});
                countedNr++;
                lastBad = t;
                to(t, State.WATCH, "active_drop");
                String c = cause(t);
                if (c != null) trySwitchLte(t, c, 0);
            }
        }
        after(t);
    }

    /** 시간 흐름(주기 확인·예약 시각). */
    void advance(long t) {
        t = touch(t);
        after(t);
    }

    void pause(long t) {
        t = touch(t);
        paused = true;
        env.log("paused", "state", state.name());
        after(t);
    }

    /** 재개: 일시정지는 현재 상태에서 이어가고, 명령 실패 보류·SAFE_STOP은 원래 모드로 활성화 판정을 다시 한다. */
    void resume(long t) {
        t = touch(t);
        boolean rejudge = failedHold || state == State.SAFE_STOP;
        paused = false;
        env.log("resumed", "rejudge", rejudge);
        if (rejudge) applyMode(t, origNr, "resume");
        else retryAfterEvent(t);
        after(t);
    }

    /** "LTE로 계속 쓰기": 컨트롤러가 막아 둔 LTE일 때만. 쓰기 없이 원래 모드를 LTE로 확정한다. */
    boolean keepLte(long t) {
        t = touch(t);
        if (nrAllowedNow || !origNr) {
            env.log("keep_lte_ignored", "state", state.name());
            after(t);
            return false;
        }
        env.keepCurrentAsOriginal();
        origNr = false;
        nrAllowedNow = true;
        pendingRestore = false;
        clearProbe();
        settling = false;
        to(t, State.INACTIVE, "keep_lte");
        after(t);
        return true;
    }

    /**
     * 시험용 강제 쿨다운(기기 시험에서 불안정 지역 없이 전환 경로를 확인하기 위함). 끊김 조건만 건너뛰고
     * 가드·예산·정착 규칙은 평소와 같다. coolMs 동안 쉬고 재시험한다. 5G 양호·경계 상태에서만 받는다.
     */
    boolean testCooldown(long t, long coolMs) {
        t = touch(t);
        if (state != State.GOOD && state != State.WATCH) {
            env.log("test_cooldown_ignored", "state", state.name());
            after(t);
            return false;
        }
        testCoolMs = coolMs;
        trySwitchLte(t, "manual_test", 0);
        testCoolMs = -1;
        after(t);
        return state == State.COOLDOWN;
    }

    /** 종료 요청. 컨트롤러가 막아 둔 LTE면 원래 모드로 되돌린 뒤 끝낸다. 통화 중이면 통화가 끝날 때까지 미룬다. */
    void stop(long t) {
        t = touch(t);
        stopPending = true;
        after(t);
    }

    // ================================================================ 조회

    boolean controlled() {
        return state == State.GOOD || state == State.WATCH || state == State.COOLDOWN || state == State.PROBE;
    }

    /** 다음에 스스로 확인해야 할 시각(없으면 -1). */
    long nextDeadline() {
        long best = -1;
        for (long[] d : deadlines()) {
            if (best < 0 || d[0] < best) best = d[0];
        }
        return best;
    }

    int switchCount() {
        return switches.size();
    }

    boolean settling() {
        return settling;
    }

    // ================================================================ 관측 화면용 읽기(판단에 영향 없음, 2026-09-30)

    /**
     * 판단에 센 사건의 누적 수(계기판, 판단에는 쓰지 않는다). 쉬기를 결정하면 판단 창을 비우므로 창 크기 변화로는
     * "이 사건을 셌는지" 알 수 없다(외부 검증 지적) → 셀 때마다 늘어나는 이 값으로 가린다.
     */
    private long countedNr;
    private long countedOos;

    long countedNr() {
        return countedNr;
    }

    long countedOos() {
        return countedOos;
    }

    Params params() {
        return p;
    }

    /** 판단 창(p.w) 안의 센 끊김 수. */
    int dropsInWindow(long t) {
        int n = 0;
        for (long[] d : drops) if (t - d[0] <= p.w) n++;
        return n;
    }

    /** 판단 창 안에서 가장 오래된 센 끊김 시각(없으면 -1). 이 시각 + p.w에 창에서 빠진다. */
    long oldestDropInWindow(long t) {
        for (long[] d : drops) if (t - d[0] <= p.w) return d[0];
        return -1;
    }

    int probeDropCount() {
        return probeDrops;
    }

    long coolUntilAt() {
        return coolUntil;
    }

    long evalStartAt() {
        return evalStart;
    }

    /** 재시험 평가의 데이터 사용 누적(지금 쓰는 중이면 지금까지 포함). */
    long useMsAt(long t) {
        return useAcc + (useFrom >= 0 ? Math.max(0, t - useFrom) : 0);
    }

    String holdWhy() {
        return hold;
    }

    int switchesInHour(long t) {
        int n = 0;
        for (long s : switches) if (t - s < 3_600_000) n++;
        return n;
    }

    long lastSwitchAt() {
        return switches.isEmpty() ? -1 : switches.peekLast();
    }

    // ================================================================ 내부: 흐름

    private long touch(long t) {
        if (t < processed) t = processed; // 시간 역행 방지
        runDeadlines(t);
        processed = t;
        return t;
    }

    /** 사건 처리 끝에서: 사용 누적 갱신, 복원·미뤄진 종료 처리. */
    private void after(long t) {
        refreshUse(t);
        if (wifiRestoreWanted()) tryWifiRestore(t);
        if (pendingRestore) tryRestore(t);
        if (stopPending) finishStop(t);
        refreshUse(t);
    }

    private void applyMode(long t, boolean nr, String why) {
        origNr = nr;
        failedHold = false;
        paused = false;
        pendingRestore = false;
        clearProbe();
        clear();
        lastBad = -1;
        if (!nr) {
            nrAllowedNow = true; // 사용자 선택 값이 곧 현재 값
            settling = false;
            to(t, State.INACTIVE, "mode_lte");
        } else if (blocked != null) {
            to(t, State.OBSERVE, blocked);
            if (!nrAllowedNow) pendingRestore = true;
        } else if (!nrAllowedNow) {
            // 컨트롤러가 막아 둔 LTE가 남은 채 재개: 쿨다운이 끝난 것으로 보고 조건이 되면 곧바로 재시험한다
            coolUntil = t;
            to(t, State.COOLDOWN, why + "_from_lte");
        } else {
            level = 0;
            to(t, State.WATCH, why);
        }
        syncHold(t);
        env.persist(state, nrAllowedNow);
    }

    private void applyUser(long t, boolean nr, boolean actualNr) {
        // 사용자가 쓴 값이 현재 값. 단 사용자 선택은 5G인데 실제에 NR이 없으면 "막아 둔 LTE"로 둔다(→ applyMode가 재시험 준비)
        nrAllowedNow = !nr || actualNr;
        settling = false;
        env.log("user_mode", "nr", nr);
        applyMode(t, nr, "user_mode");
        // 사용자가 모드를 바꾸면 폰이 망에 다시 붙으며 데이터가 잠깐 끊긴다(실측: 5G 우선 선택 직후 OOS 1.6초).
        // 컨트롤러 쓰기 뒤와 똑같이 정착할 때까지 끊김을 세지 않는다. 이 쓰기는 컨트롤러 것이 아니므로 시간 초과는 SAFE_STOP이 아니다.
        if (controlled()) startSettle(t, false);
    }

    private void applyBlocked(long t, String reason) {
        String before = blocked;
        blocked = reason;
        env.log("blocked", "why", reason == null ? "none" : reason);
        if (reason != null && before == null && controlled()) {
            if (!nrAllowedNow) {
                pendingRestore = true; // 가드가 허락하면 원래 모드로 1회 복원한 뒤 OBSERVE
            } else {
                clearProbe();
                settling = false;
                to(t, State.OBSERVE, reason);
            }
        } else if (reason == null && before != null && state == State.OBSERVE) {
            applyMode(t, origNr, "unblocked");
        }
    }

    private boolean writable() {
        return !paused && !failedHold && !stopPending && blocked == null && state != State.SAFE_STOP;
    }

    /** 특징·재시험 판정에 쓸 사건인지(화면 켜짐, 제어 중, 보류·정착·일시정지 아님). */
    private boolean counting(boolean screenOn) {
        return screenOn && controlled() && hold == null && !settling && writable();
    }

    private boolean evalOpen(long t) {
        return evalStart >= 0 && t >= evalStart;
    }

    private void to(long t, State s, String why) {
        if (s == state) return;
        env.log("state", "from", state.name(), "to", s.name(), "why", why, "level", level);
        state = s;
        if (s == State.GOOD || s == State.WATCH) stableSince = t;
        if (s == State.WATCH) watchSince = t;
        updateIndicator();
        env.persist(state, nrAllowedNow);
    }

    /**
     * 상단바 작동 표시(DESIGN §5.8): 컨트롤러가 5G 셀룰러를 관리하는 동안만. Wi-Fi로 인터넷을 쓰는 동안은 숨긴다
     * (사용자 지적 2026-09-28: Wi-Fi에서도 점이 떠 있었다). 보류 사유는 통화가 Wi-Fi보다 앞서지만 표시는 Wi-Fi 여부만 본다
     * (Wi-Fi 중 통화가 와도 점이 다시 뜨지 않게). 바뀔 때만 Env에 알린다.
     */
    private void updateIndicator() {
        boolean want = controlled() && !stopPending && !wifi;
        if (indicatorShown == null || indicatorShown != want) {
            indicatorShown = want;
            env.indicator(want);
        }
    }

    private void clear() {
        drops.clear();
        oos.clear();
    }

    private void clearProbe() {
        probeDrops = probeOos = 0;
        pendingCause = null;
        retryAt = -1;
        evalStart = -1;
        useAcc = 0;
        useFrom = -1;
        useGapFrom = -1;
    }

    // ================================================================ 내부: 가드

    private boolean inCallOrGrace(long t) {
        return call != 0 || (callEnd >= 0 && t - callEnd < p.tCallGrace);
    }

    private String holdReason(long t) {
        if (!controlled()) return null;
        if (inCallOrGrace(t)) return "call";
        if (wifi) return "wifi"; // 인터넷이 Wi-Fi로 나가는 동안은 셀룰러 데이터를 쓰지 않는다
        if (!Boolean.TRUE.equals(screen)) return "screen_off";
        if (roaming) return "roaming";
        for (Boolean v : restrict.values()) if (Boolean.FALSE.equals(v)) return "restricted";
        return null;
    }

    private void syncHold(long t) {
        String why = holdReason(t);
        if (why != null && hold == null) {
            hold = why;
            env.log("hold", "why", why);
            clear();
            if (state == State.PROBE) {
                probeDrops = probeOos = 0;
                pendingCause = null;
                retryAt = -1;
                evalStart = -1; // 평가 창 멈춤: 풀리면 새 창으로(누적도 새로)
                useFrom = -1;
                useAcc = 0;
                useGapFrom = -1;
            }
        } else if (why == null && hold != null) {
            env.log("hold_end", "was", hold);
            hold = null;
            clear();
            if (state == State.PROBE && evalStart < 0 && !settling) evalStart = t; // 정착이 끝났을 때만 바로 연다
            retryAfterEvent(t);
        } else if (why != null && !why.equals(hold)) {
            hold = why;
            env.log("hold", "why", why);
        }
        updateIndicator();
    }

    /**
     * 새 전환 가드(DESIGN §5.5.2 판단 순서: 가드 → 예산 → 정착). 통화 조회는 시간이 걸리는 확인(대용량 트래픽 1초 측정)
     * 뒤, 쓰기 바로 앞에서 한다. Controller도 쓰기 직전에 한 번 더 확인한다(막히면 BLOCKED).
     */
    private String guard(long t) {
        String h = holdReason(t);
        if (h != null) return h;
        if (!inService) return "no_service";
        pruneSwitches(t);
        if (switches.size() >= p.bHour) return "budget_hour";
        if (!switches.isEmpty() && t - switches.peekLast() < p.bGap) return "budget_gap";
        if (settling) return "settling";
        if (env.heavyNow()) return "heavy_traffic";
        String c = env.callNow();
        if (c != null) return c;
        return null;
    }

    /** 원래 모드로 되돌리는 쓰기의 가드: 통화만 막는다(사용자 선택으로 돌아가는 일을 예산·화면으로 늦추지 않음). */
    private String restoreGuard(long t) {
        if (inCallOrGrace(t)) return "call";
        return env.callNow();
    }

    private void pruneSwitches(long t) {
        while (!switches.isEmpty() && t - switches.peekFirst() >= 3_600_000) switches.pollFirst();
    }

    private long retryTime(String why, long t) {
        if ("call".equals(why) && call == 0 && callEnd >= 0) return callEnd + p.tCallGrace;
        if ("heavy_traffic".equals(why)) return t + HEAVY_RETRY_MS;
        if ("budget_gap".equals(why)) return switches.peekLast() + p.bGap;
        if ("budget_hour".equals(why)) {
            List<Long> r = new ArrayList<>(switches);
            Collections.sort(r);
            return r.get(r.size() - p.bHour) + 3_600_000;
        }
        return t + RETRY_POLL_MS; // 시간으로 풀리지 않는 이유: 쉬지 않고 다시 보지 않도록 간격을 둔다
    }

    // ================================================================ 내부: 판정

    private void prune(long t) {
        while (!drops.isEmpty() && t - drops.peekFirst()[0] > p.w) drops.pollFirst();
        while (!oos.isEmpty() && t - oos.peekFirst() > p.w) oos.pollFirst();
    }

    private String cause(long t) {
        prune(t);
        if (drops.size() >= p.nDrop) return "drops";
        if (!oos.isEmpty()) return "oos";
        List<Long> dw = new ArrayList<>();
        for (long[] d : drops) if (d[1] >= 0) dw.add(d[1]);
        if (dw.size() >= p.k) {
            Collections.sort(dw);
            int n = dw.size();
            double med = n % 2 == 1 ? dw.get(n / 2) : (dw.get(n / 2 - 1) + dw.get(n / 2)) / 2.0;
            if (med < p.dMin) return "dwell";
        }
        return null;
    }

    // ================================================================ 내부: 쓰기

    private void trySwitchLte(long t, String cause, int levelInc) {
        if (!writable()) return;
        String why = guard(t);
        if (why != null) {
            blockedSwitch(t, cause, levelInc, why);
            return;
        }
        Result r = env.write(t, false, cause);
        if (r.kind == Kind.BLOCKED) {
            blockedSwitch(t, cause, levelInc, "call");
            return;
        }
        if (r.kind != Kind.OK) {
            handleWriteProblem(t, r);
            return;
        }
        pendingCause = null;
        retryAt = -1;
        level += levelInc;
        switches.addLast(t);
        nrAllowedNow = false;
        coolUntil = t + (testCoolMs > 0 ? testCoolMs : Math.min(p.cBase << Math.min(level, 20), p.cMax));
        clear();
        clearProbe();
        to(t, State.COOLDOWN, cause);
        startSettle(Math.max(t, r.doneAt), true);
    }

    /** LTE로 내리려다 가드에 막힘: 재시험 실패 판정은 해석 2대로 유지하거나 버리고, 경계 상태면 창을 비운다(서비스 없음 제외). */
    private void blockedSwitch(long t, String cause, int levelInc, String why) {
        env.log("suppressed", "cause", cause, "why", why);
        if (state == State.PROBE && ("no_service".equals(why) || why.startsWith("budget")
                || "heavy_traffic".equals(why) || "settling".equals(why))) {
            pendingCause = cause; // 해석 2: 재시험 실패 판정 유지, 풀리면 실행
            pendingInc = levelInc;
            retryAt = retryTime(why, t);
        } else if (state == State.PROBE) {
            pendingCause = null;
            retryAt = -1;
            probeDrops = probeOos = 0;
        } else if (!"no_service".equals(why)) {
            clear(); // 보류 후 특징 창을 비우고 새로 시작(DESIGN §5.5.2). 서비스 없음은 해석 1
        }
    }

    private void tryProbe(long t) {
        if (!writable()) return;
        String why = guard(t);
        if (why != null) {
            retryAt = retryTime(why, t);
            return;
        }
        retryAt = -1;
        Result r = env.write(t, true, "probe");
        if (r.kind == Kind.BLOCKED) {
            // 쓰기가 다시 가능해질 시각을 알려 줬으면(예: 사용자 선택 확인 창 끝, §5.12) 그때, 아니면 통화 기준
            retryAt = r.doneAt > t ? r.doneAt : retryTime("call", t);
            return;
        }
        if (r.kind != Kind.OK) {
            handleWriteProblem(t, r);
            return;
        }
        switches.addLast(t);
        nrAllowedNow = true;
        clearProbe();
        to(t, State.PROBE, "cooldown_end");
        startSettle(Math.max(t, r.doneAt), true); // 평가 창은 정착이 끝나면 연다
    }

    private void tryRestore(long t) {
        if (!pendingRestore || stopPending) return;
        if (nrAllowedNow || !origNr) {
            pendingRestore = false;
            return;
        }
        if (restoreGuard(t) != null) return;
        Result r = env.write(t, true, "restore");
        if (r.kind == Kind.BLOCKED) return; // 통화: 다음 사건에서 다시(복원 대기 유지)
        pendingRestore = false;
        if (r.kind != Kind.OK) {
            handleWriteProblem(t, r);
            return;
        }
        switches.addLast(t);
        nrAllowedNow = true;
        settling = false;
        clearProbe();
        State target = state == State.SAFE_STOP ? State.SAFE_STOP : State.OBSERVE;
        env.persist(state, nrAllowedNow);
        to(t, target, blocked != null ? blocked : "restored");
    }

    /**
     * Wi-Fi로 인터넷을 쓰는 동안 컨트롤러가 LTE로 쉬게 해 둔 상태면 사용자의 원래 모드로 되돌린다(사용자 결정 2026-09-28).
     * 되돌리는 값은 언제나 사용자가 고른 원래 모드다: 원래 LTE 우선이면 origNr=false라 이 쓰기는 일어나지 않는다.
     * 일시정지·명령 실패 보류·관찰 전용(롤백)·종료 중에는 쓰지 않는다(writable).
     */
    private boolean wifiRestoreWanted() {
        return "wifi".equals(hold) && state == State.COOLDOWN && !nrAllowedNow && origNr && writable();
    }

    /** Wi-Fi 연결 중 되돌리기: 통화만 막는다(되돌리기 가드). 되돌리면 경계 상태로 쉬다가 Wi-Fi가 끊기면 새로 판단한다. */
    private void tryWifiRestore(long t) {
        if (restoreGuard(t) != null) return; // 통화 중에는 보류 사유가 "call"이라 여기 오지 않는다(이중 확인)
        Result r = env.write(t, true, "wifi_restore");
        if (r.kind == Kind.BLOCKED) return; // 쓰기 직전 통화 감지: 통화 사건→유예 끝 예약에서 다시
        if (r.kind != Kind.OK) {
            handleWriteProblem(t, r);
            return;
        }
        switches.addLast(t);
        nrAllowedNow = true;
        settling = false;
        clearProbe();
        clear();
        lastBad = -1;
        to(t, State.WATCH, "wifi_restore");
    }

    private void finishStop(long t) {
        if (nrAllowedNow || !origNr) {
            done(true);
            return;
        }
        String why = restoreGuard(t);
        Result r = why == null ? env.write(t, true, "stop") : null;
        if (r == null || r.kind == Kind.BLOCKED) {
            if (!stopDeferredLogged) env.log("stop_deferred", "why", why == null ? "write_blocked" : why);
            stopDeferredLogged = true;
            return;
        }
        // OK·ADOPTED(쓰는 사이 사용자가 모드를 고름)는 되돌릴 것이 더 없다. 그 밖(실패·롤백)은 컨트롤러가 남긴 LTE가 그대로다.
        boolean restored = r.kind == Kind.OK || r.kind == Kind.ADOPTED;
        if (restored) nrAllowedNow = true;
        env.log("stop_restore", "result", r.kind.name());
        done(restored);
    }

    private void done(boolean restored) {
        stopPending = false;
        indicatorShown = false;
        env.indicator(false);
        env.persist(state, nrAllowedNow);
        env.stopDone(restored);
    }

    private void handleWriteProblem(long t, Result r) {
        switch (r.kind) {
            case ADOPTED:
                applyUser(t, r.adoptedNr, r.adoptedActualNr); // 출력 completed ∧ 재확인 ≠ 목표 → 사용자 모드 선택으로 채택(§5.6.3)
                break;
            case FAILED:
                failedHold = true; // 재시도 없음. 사용자가 재개해야 한다
                env.log("write_failed_hold", "state", state.name());
                env.persist(state, nrAllowedNow);
                break;
            case ROLLED_BACK:
                blocked = "s2_rollback"; // S2 부분 실패 → 되돌린 뒤 관찰만(쓴 것이 되돌려졌으므로 현재 값은 쓰기 전과 같다)
                clearProbe();
                settling = false;
                to(t, State.OBSERVE, "s2_rollback");
                break;
            case BROKEN:
                if (r.adopted) {
                    // 사용자 선택은 채택하되(원래 모드가 된다) 화면 키가 어긋난 채라 제어를 멈춘다. 추가 쓰기는 없다.
                    origNr = r.adoptedNr;
                    nrAllowedNow = true;
                    env.log("user_mode", "nr", r.adoptedNr, "display", "mismatch");
                    safeStop(t, "adopt_display_fix_failed", false);
                } else {
                    safeStop(t, "s2_rollback_failed", false); // 롤백을 이미 시도했으므로 추가 복원 쓰기는 없다
                }
                break;
            default:
                break;
        }
    }

    /** SAFE_STOP: (restore면) 원래 모드로 되돌리고 제어를 멈춘다(재개 전까지). */
    private void safeStop(long t, String why, boolean restore) {
        clearProbe();
        settling = false;
        to(t, State.SAFE_STOP, why);
        if (restore && !nrAllowedNow && origNr) pendingRestore = true;
    }

    private void startSettle(long t, boolean byController) {
        settling = true;
        settleByController = byController;
        settleStart = t;
        settleChecked = false;
        settleConfirmed = -1;
        settleOosFrom = netOk ? -1 : t;
        settleOosMs = 0;
        settleRecoveredAt = -1;
    }

    private void confirmSettle(long t) {
        if (settling && settleConfirmed < 0 && netOk) settleConfirmed = t;
    }

    /** 정착이 끝날 때 전환(또는 사용자 모드 변경) 비용을 남긴다: 끊긴 시간, 다시 정상이 된 시각, 정상 확인 시각(쓰기 끝 기준 ms). */
    private void logSettleCost(long t) {
        long out = settleOosMs + (settleOosFrom >= 0 ? t - settleOosFrom : 0);
        env.log(settleByController ? "switch_cost" : "mode_change_cost", "outMs", out,
                "recoverMs", settleRecoveredAt < 0 ? 0 : settleRecoveredAt - settleStart,
                "confirmMs", settleConfirmed < 0 ? -1 : settleConfirmed - settleStart);
    }

    private void endSettle(long t) {
        logSettleCost(t);
        settling = false;
        if (state == State.PROBE && evalStart < 0 && hold == null) evalStart = t;
        retryAfterEvent(t);
    }

    private void retryAfterEvent(long t) {
        if (!writable() || settling || hold != null) return;
        if (state == State.COOLDOWN && t >= coolUntil) {
            tryProbe(t);
        } else if (pendingCause != null) {
            trySwitchLte(t, pendingCause, pendingInc);
        }
    }

    /** 재시험 평가의 데이터 사용 시간으로 세는 조건(데이터 활동 여부 제외). */
    private boolean useCounts(long t) {
        return state == State.PROBE && Boolean.TRUE.equals(screen) && hold == null && evalOpen(t)
                && pendingCause == null && writable();
    }

    private void refreshUse(long t) {
        boolean counts = dataActive && useCounts(t);
        if (useFrom >= 0) {
            useAcc += t - useFrom;
            useFrom = counts ? t : -1;
        } else if (counts) {
            useFrom = t;
        }
    }

    // ================================================================ 내부: 예약 시각

    private List<long[]> deadlines() {
        List<long[]> c = new ArrayList<>();
        if (settling) {
            if (!settleChecked) c.add(new long[]{settleStart + p.settleMinWait, D_SETTLE_CHECK});
            if (settleConfirmed < 0) c.add(new long[]{settleStart + p.tSettleMax, D_SETTLE_TIMEOUT});
            else c.add(new long[]{settleConfirmed + p.tSettle, D_SETTLE_END});
        }
        // 통화 유예가 끝나는 시각: 보류 해제, 미뤄진 종료·복원 처리
        if (("call".equals(hold) || stopPending || pendingRestore) && call == 0 && callEnd >= 0
                && callEnd + p.tCallGrace >= processed) {
            c.add(new long[]{callEnd + p.tCallGrace, D_HOLD_CHECK});
        }
        if (!writable() || settling) return c;
        if (state == State.WATCH) c.add(new long[]{Math.max(watchSince, lastBad) + p.tClear, D_CLEAR});
        if ((state == State.GOOD || state == State.WATCH) && level > 0) {
            c.add(new long[]{Math.max(stableSince, lastBad) + p.tStable, D_STABLE});
        }
        if (state == State.COOLDOWN && hold == null) c.add(new long[]{Math.max(coolUntil, retryAt), D_PROBE});
        if (state == State.PROBE) {
            if (pendingCause != null) {
                if (retryAt >= 0) c.add(new long[]{retryAt, D_RETRY});
            } else if (evalStart >= 0) {
                c.add(new long[]{evalStart + p.pMax, D_PMAX});
                if (useFrom >= 0) c.add(new long[]{useFrom + (p.pActive - useAcc), D_PASS});
            }
        }
        return c;
    }

    /** t까지 도래한 예약을 시각 순서로 처리한다. 진전이 없는 예약(가드로 막힘)은 이번 차례에서 건너뛴다. */
    private void runDeadlines(long t) {
        Set<Integer> stuck = new HashSet<>();
        for (int loop = 0; loop < 1000; loop++) {
            long[] next = null;
            for (long[] d : deadlines()) {
                if (d[0] > t || stuck.contains((int) d[1])) continue;
                if (next == null || d[0] < next[0] || (d[0] == next[0] && d[1] < next[1])) next = d;
            }
            if (next == null) return;
            long when = Math.max(next[0], processed);
            processed = when;
            refreshUse(when);
            String before = snapshot();
            fire((int) next[1], when);
            refreshUse(when);
            if (wifiRestoreWanted()) tryWifiRestore(when);
            if (pendingRestore) tryRestore(when);
            if (stopPending) finishStop(when);
            if (before.equals(snapshot())) stuck.add((int) next[1]);
        }
    }

    private void fire(int kind, long when) {
        switch (kind) {
            case D_SETTLE_CHECK:
                settleChecked = true;
                confirmSettle(when);
                break;
            case D_SETTLE_TIMEOUT:
                if (settleByController) {
                    env.log("settle_timeout", "sinceWriteMs", when - settleStart);
                    logSettleCost(when);
                    safeStop(when, "settle_timeout", true);
                } else {
                    env.log("mode_change_quiet_timeout", "sinceMs", when - settleStart);
                    endSettle(when); // 사용자 모드 변경 뒤 망이 늦게 돌아와도 컨트롤러가 쓴 게 아니므로 멈추지 않는다
                }
                break;
            case D_SETTLE_END:
                endSettle(when);
                break;
            case D_HOLD_CHECK:
                syncHold(when);
                break;
            case D_CLEAR:
                to(when, State.GOOD, "t_clear");
                lastBad = -1;
                break;
            case D_STABLE:
                level = 0;
                stableSince = when;
                env.log("backoff_reset");
                break;
            case D_PROBE:
                tryProbe(when);
                break;
            case D_RETRY:
                trySwitchLte(when, pendingCause, pendingInc);
                break;
            case D_PMAX:
                env.log("probe_result", "result", "undecided", "useMs", useAcc);
                clearProbe();
                to(when, State.WATCH, "probe_undecided");
                lastBad = -1;
                break;
            case D_PASS:
                env.log("probe_result", "result", "pass", "useMs", useAcc);
                clearProbe();
                to(when, State.WATCH, "probe_pass");
                lastBad = -1;
                break;
            default:
                break;
        }
    }

    private String snapshot() {
        return state + "|" + level + "|" + retryAt + "|" + pendingCause + "|" + coolUntil + "|" + hold + "|" + evalStart
                + "|" + settling + "|" + settleChecked + "|" + settleConfirmed + "|" + failedHold + "|" + pendingRestore
                + "|" + nrAllowedNow + "|" + stableSince + "|" + stopPending;
    }
}
