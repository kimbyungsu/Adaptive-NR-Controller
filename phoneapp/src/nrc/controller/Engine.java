package nrc.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 판단 엔진 연결부(DESIGN §5.13, daemon Controller를 통신사 칸 방식으로 옮김).
 * - 관찰 사건을 판단 규칙(Policy)에 넘기고, 규칙이 정한 쓰기를 통신사 칸(CARRIER)의 NR 비트로만 실행한다.
 * - 사용자 선택 = 사용자 칸(USER, 설정 화면이 보여 주는 값). 읽기만 하고 절대 쓰지 않는다. 그래서 예전 USER 쓰기 방식의
 *   보호 규칙(키 재확인·확인 창 등, §5.12)은 필요 없다.
 * - 우리 막음 기록: SIM별로, 우리가 실제로 남긴 값까지 저장한다("own.<sub>"). 지금 값이 그 값과 다르면 남의 제한이다(CarrierPlan).
 * - 안전장치: 엔진이 쉬는 중(쿨다운)이 아닌데 우리 막음이 남아 있으면 곧바로 푼다(시작·사용자 LTE 선택·자동 제어 끄기·
 *   Wi-Fi 되돌리기 뒤 등). 통화 중이면 통화가 끝날 때까지 미룬다. 폰 꺼짐·서비스 종료 때는 엔진을 먼저 완전히 멈춘 뒤 푼다(terminate).
 * 모든 메서드는 작업 스레드(worker)에서만 불린다. 밖에서 부를 때는 post로 넘긴다.
 */
final class Engine implements Policy.Env, Watcher.Listener {
    interface Host {
        /** 상태가 바뀌었다(타일·화면 갱신). mode = TileText.MODE_*. */
        void publish(int mode, String phase, String problem);

        /** 자동 제어 끄기(stop)가 끝났다. */
        void stopped();
    }

    static final long TICK_MS = 30_000;
    static final long STUCK_POLL_MS = 5_000;
    static final String STORE = "engine";
    /** 우리 막음 기록 키 앞부분(뒤에 SIM 번호). 값 = 우리가 남긴 통신사 칸 값. */
    static final String OWN_PREFIX = "own.";

    private final Radio radio;
    private final Journal log;
    private final ScheduledExecutorService worker;
    private final Host host;
    private final Params params = new Params();
    private final SharedPreferences store;

    private Policy policy;
    private Watcher watcher;
    private ScheduledFuture<?> timer;
    private ScheduledFuture<?> ticker;
    private long userMask = -2;
    private boolean screenOn;
    /** 엔진이 멈췄다(자동 제어 끄기 완료 또는 종료). 이후 어떤 입력도 판단 규칙에 넘기지 않는다. */
    private boolean stopped;
    private boolean dataIn = true;
    private int dataConn = -1;
    private Boolean netOkLast;
    private boolean liftDeferredLogged;
    private String problem;
    /** 제어 불가 이유를 따로 든다(외부 검증 지적: 하나로 합치면 사용자 칸 회복이 SIM 2개 조건까지 지웠다). */
    private String simBlock;
    private boolean userUnreadable;
    private String appliedBlock;

    // ---------- 관측 화면(2026-09-30): 활동 기록·오늘의 활동·지금 모습·자가 점검 ----------
    static final String SUMMARY_STORE = "summary";
    static final String SUMMARY_KEY = "today";
    static final long SELF_TEST_GAP_MS = 60_000;
    /** 자가 점검이 끝난 뒤 이 시간 동안 생긴 끊김은 판단에 세지 않는다(점검이 쉬기를 일으키지 않게). */
    static final long SELF_TEST_QUIET_MS = 15_000;
    static final long SELF_TEST_WATCH_MS = 6_000;
    static final long SELF_TEST_POLL_MS = 500;

    interface TestSink {
        void line(String text, boolean ok);

        void done(boolean pass);
    }

    private final Timeline tl;
    private final SharedPreferences sumStore;
    private final DaySummary sum;
    private volatile Live live;
    private boolean wifiNow;
    private String stateWhy = "start";
    private long stateSince = now();
    private long stateSinceWall = System.currentTimeMillis();
    private String restWhy;
    private long oosAtT = -1;
    private boolean selfTesting;
    private long quietUntil = -1;
    /** 마지막 자가 점검 시각(부팅 후 경과)을 담는 저장소 키. 엔진을 새로 만들어도 1분 간격이 유지된다(외부 검증 지적). */
    static final String LAST_SELF_TEST = "lastSelfTest";

    Engine(Context ctx, Radio radio, Journal log, Timeline tl, ScheduledExecutorService worker, Host host) {
        this.radio = radio;
        this.log = log;
        this.tl = tl;
        this.worker = worker;
        this.host = host;
        this.store = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        this.sumStore = ctx.getSharedPreferences(SUMMARY_STORE, Context.MODE_PRIVATE);
        this.sum = DaySummary.load(sumStore.getString(SUMMARY_KEY, null));
    }

    /** 관측 화면이 읽는 지금 모습(없으면 null). */
    Live live() {
        return live;
    }

    // ================================================================ 우리 막음 기록(SIM별)

    /** 이 SIM에 우리가 남긴 값(없으면 -1). */
    static long ownMask(Context c, int sub) {
        return c.getSharedPreferences(STORE, Context.MODE_PRIVATE).getLong(OWN_PREFIX + sub, -1);
    }

    /** 어느 SIM에든 우리 막음 기록이 있는지. */
    static boolean anyOwn(Context c) {
        for (Map.Entry<String, ?> e : c.getSharedPreferences(STORE, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (e.getKey().startsWith(OWN_PREFIX)) return true;
        }
        return false;
    }

    /** 동기 저장(쓰기 전 선기록이 쓰기보다 먼저 확실히 남도록). mask < 0이면 지운다. */
    static boolean setOwn(Context c, int sub, long mask) {
        SharedPreferences.Editor e = c.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit();
        if (mask < 0) e.remove(OWN_PREFIX + sub);
        else e.putLong(OWN_PREFIX + sub, mask);
        return e.commit();
    }

    private long own() {
        return store.getLong(OWN_PREFIX + radio.sub, -1);
    }

    private boolean setOwn(long mask) {
        SharedPreferences.Editor e = store.edit();
        if (mask < 0) e.remove(OWN_PREFIX + radio.sub);
        else e.putLong(OWN_PREFIX + radio.sub, mask);
        return e.commit();
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    // ================================================================ 수명

    void start(boolean wifi, boolean screen) {
        long t = now();
        screenOn = screen;
        wifiNow = wifi;
        rollSummary();
        sum.closeRest(System.currentTimeMillis()); // 지난 실행이 쉬는 중에 죽었으면 여기서 닫는다(그 사이 시간도 들어갈 수 있음)
        saveSummary();
        policy = new Policy(params, this);
        reconcile("start", false); // 지난 실행이 남긴 막음을 먼저 푼다(엔진은 아직 쉬는 중이 아니다)
        long u = radio.read(Radio.USER);
        userMask = u;
        userUnreadable = u < 0;
        simBlock = simBlockNow();
        rec("engine_start", "sub", radio.sub, "user", u, "carrier", radio.read(Radio.CARRIER),
                "power", radio.read(Radio.POWER), "enable2g", radio.read(Radio.ENABLE_2G), "own", own(),
                "simBlock", simBlock == null ? "none" : simBlock, "wifi", wifi, "screen", screen);
        policy.screen(t, screen);
        policy.wifi(t, wifi);
        refreshRestrictions(t);
        applyBlocked(t);
        policy.setMode(t, CarrierPlan.hasNr(u));
        policy.startQuiet(t);
        watcher = new Watcher(log, this);
        radio.tm().registerTelephonyCallback(worker, watcher);
        ticker = worker.scheduleWithFixedDelay(() -> guarded("tick", this::tick), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        after(t);
    }

    /** 개발 시험(PC 명령): 끊김 조건 없이 쉬기(쉬는 시간 TEST_COOL_MS). 5G 양호·경계 상태에서만 받는다. */
    void testCooldown() {
        if (stopped || policy == null) return;
        long t = now();
        policy.testCooldown(t, TEST_COOL_MS);
        after(t);
    }

    /** 시험용 쉬는 시간(전환 최소 간격과 같게, daemon과 같음). */
    static final long TEST_COOL_MS = 120_000;

    /** 자동 제어 끄기: 판단 규칙의 종료 절차(쉬는 중이면 풀기, 통화 중이면 통화 뒤)를 거친 뒤 stopDone → Host.stopped(). */
    void stop() {
        if (stopped || policy == null) return;
        policy.stop(now());
        after(now());
    }

    /**
     * 폰 꺼짐·서비스 종료: 먼저 엔진을 완전히 멈추고(입력·예약·전화 알림 해제, 이후 쓰기 없음) 쉬는 중이어도 우리 막음을 푼다.
     * 외부 검증 지적: 멈추지 않고 풀기만 하면 줄 서 있던 끊김 사건이 다시 막을 수 있었다.
     */
    void terminate(String why) {
        halt(why);
        reconcile(why, true);
    }

    private void halt(String why) {
        if (stopped) return;
        stopped = true;
        selfTesting = false; // 멈추면 자가 점검도 끝낸다(남은 막음은 곧이은 정리가 푼다)
        rollSummary();
        sum.closeRest(System.currentTimeMillis()); // 상태 사건 없이 끝나도 쉰 시간을 닫는다(외부 검증 지적)
        saveSummary();
        if (timer != null) timer.cancel(false);
        if (ticker != null) ticker.cancel(false);
        if (watcher != null) {
            try {
                radio.tm().unregisterTelephonyCallback(watcher);
            } catch (RuntimeException ignored) {
                // 이미 풀렸으면 무시
            }
            watcher.shutdown(why);
        }
        rec("engine_halt", "why", why);
    }

    // ================================================================ 밖에서 오는 사건(서비스가 작업 스레드로 넘긴다)

    void onScreen(boolean on) {
        if (stopped) return;
        long t = now();
        screenOn = on;
        policy.screen(t, on);
        after(t);
    }

    void onWifi(boolean on) {
        if (stopped) return;
        long t = now();
        if (on != wifiNow) tl.add(Timeline.Cat.OBS, on ? "Wi-Fi 연결" : "Wi-Fi 끊김 → 모바일 데이터로");
        wifiNow = on;
        policy.wifi(t, on);
        after(t);
    }

    /** 삼성 설정 키가 바뀌었다(사용자가 네트워크 모드를 골랐을 수 있다). */
    void onUserSignal() {
        if (stopped) return;
        long t = now();
        checkUser(t);
        after(t);
    }

    // ================================================================ Watcher.Listener

    @Override
    public void onCall(long t, int state) {
        if (stopped) return;
        policy.call(t, state);
        after(t); // 통화가 끝나면 미뤄 둔 풀기도 여기서 다시 본다
    }

    @Override
    public void onServiceState(long t, boolean in, boolean roaming) {
        if (stopped) return;
        dataIn = in;
        policy.roaming(t, roaming);
        updateNetOk(t);
        after(t);
    }

    @Override
    public void onOos(long t, long sinceDataMs) {
        if (stopped) return;
        oosAtT = t;
        if (quiet(t)) {
            if (screenOn) tl.add(Timeline.Cat.OBS, "데이터 끊김(자가 점검 중 · 셈에 안 넣음)");
            after(t);
            return;
        }
        long oosBefore = policy.countedOos();
        policy.oos(t, sinceDataMs, screenOn);
        if (screenOn) {
            tl.add(Timeline.Cat.OBS, "데이터 끊김" + (policy.countedOos() > oosBefore ? "(쉬기 기준에 넣음)" : "(셈에 안 넣음)"));
        }
        after(t);
    }

    @Override
    public void onService(long t) {
        if (stopped) return;
        if (screenOn && oosAtT >= 0) tl.add(Timeline.Cat.OBS, "데이터 다시 연결(" + secs(t - oosAtT) + "초 끊김)");
        oosAtT = -1;
        policy.service(t);
        after(t);
    }

    @Override
    public void onNrOff(long t, long sinceDataMs, long dwellMs) {
        if (stopped) return;
        boolean active = sinceDataMs >= 0 && sinceDataMs <= params.tActive;
        if (quiet(t)) {
            if (screenOn) tl.add(Timeline.Cat.OBS, "5G 끊김(자가 점검 중 · 셈에 안 넣음)");
            after(t);
            return;
        }
        // 어느 쪽(감시/재시험)으로 셌는지는 호출 뒤 계기판으로 가린다. 호출 전 상태로 정하면, 입력을 처리하기 전에
        // 밀린 재시험 통과 예약이 먼저 돌아 감시 끊김으로 센 경우를 "재시험"으로 잘못 적는다(외부 검증 지적)
        long watch0 = policy.countedWatchNr();
        long probe0 = policy.countedProbeNr();
        policy.nrOff(t, sinceDataMs, dwellMs, screenOn);
        boolean byProbe = policy.countedProbeNr() > probe0;
        boolean counted = byProbe || policy.countedWatchNr() > watch0;
        if (counted) {
            rollSummary();
            sum.onCountedDrop();
            saveSummary();
        }
        if (screenOn) {
            String how = !active ? "폰 안 쓰는 중 · 셈에 안 넣음"
                    : counted ? "폰 쓰는 중 · " + countText(t, byProbe)
                    : "폰 쓰는 중 · 지금은 셈에 안 넣음";
            tl.add(Timeline.Cat.OBS, "5G 끊김(" + how + ")");
        }
        after(t);
    }

    @Override
    public void onNrOn(long t, long sinceDataMs) {
        if (stopped) return;
        if (screenOn) tl.add(Timeline.Cat.OBS, "5G 붙음");
        publish(); // 화면의 "실제 연결"이 다음 사건까지 늦지 않게(외부 검증 보완)
    }

    /** 센 끊김 뒤 표시. 쉬기로 넘어갔으면 판단 창이 비워지므로 "쉬기 결정"으로 적는다. */
    private String countText(long t, boolean probeCounted) {
        if (policy.state == Policy.State.COOLDOWN) return probeCounted ? "다시 확인 중 " + params.nProbe + "번째 끊김 → 다시 쉬기" : "쉬기 기준 " + params.nDrop + "번째 → LTE로 잠깐 쉬기로 함";
        if (probeCounted) return "다시 확인 중 끊김 " + policy.probeDropCount() + "/" + params.nProbe + "번째";
        return "쉬기 기준 " + policy.dropsInWindow(t) + "/" + params.nDrop + "번째";
    }

    private boolean quiet(long t) {
        return selfTesting || (quietUntil >= 0 && t < quietUntil);
    }

    private static String secs(long ms) {
        return String.format(Locale.US, "%.1f", ms / 1000.0);
    }

    @Override
    public void onDataActivity(long t, boolean active) {
        if (stopped) return;
        policy.dataActivity(t, active);
        after(t);
    }

    @Override
    public void onDataConnection(long t, int state) {
        if (stopped) return;
        dataConn = state;
        updateNetOk(t);
        after(t);
    }

    // ================================================================ Policy.Env

    /**
     * 판단 규칙이 정한 쓰기: 통신사 칸의 NR 비트만 바꾼다.
     * 순서: 읽기 → 우리/남의 판정 → (막기면) 선기록 → **통화 확인(쓰기 바로 앞)** → 쓰기 → 다시 읽어 기록 확정.
     * 외부 검증 지적: 통화 확인을 읽기·선기록보다 먼저 하면 그 사이 시작된 통화 중에도 썼다.
     */
    @Override
    public Policy.Result write(long t, boolean allowNr, String why) {
        if (stopped) {
            rec("w_carrier", "why", why, "allowNr", allowNr, "result", "stopped");
            return Policy.Result.of(Policy.Kind.BLOCKED);
        }
        if (selfTesting) {
            rec("w_carrier", "why", why, "allowNr", allowNr, "result", "blocked", "guard", "self_test");
            return Policy.Result.of(Policy.Kind.BLOCKED);
        }
        String cond = blockReason();
        if (!allowNr && cond != null) {
            // 엔진이 이미 아는 제어 불가 조건(SIM 2개 등): 새로 막지 않는다(풀기는 허용). 외부 검증 지적: 조건을 판단 규칙에
            // 알리는 도중 밀린 LTE 전환 예약이 먼저 실행돼 불필요한 전환이 두 번 일어났다
            rec("w_carrier", "why", why, "allowNr", false, "result", "blocked", "cond", cond);
            return Policy.Result.of(Policy.Kind.BLOCKED);
        }
        if (!radio.privileged()) {
            problem = "다시 설정 필요";
            rec("w_carrier", "why", why, "allowNr", allowNr, "result", "no_privilege");
            return Policy.Result.of(Policy.Kind.FAILED);
        }
        long cur = radio.read(Radio.CARRIER);
        if (cur < 0) {
            rec("w_carrier", "why", why, "allowNr", allowNr, "result", "unreadable");
            return Policy.Result.of(Policy.Kind.FAILED);
        }
        long own = own();
        CarrierPlan.Carrier k = CarrierPlan.classify(cur, own);
        if (CarrierPlan.hasNr(cur) == allowNr) {
            if (allowNr && own >= 0) setOwn(-1);
            rec("w_carrier", "why", why, "allowNr", allowNr, "result", "already", "carrier", cur);
            return Policy.Result.of(Policy.Kind.OK, now());
        }
        if (allowNr && k != CarrierPlan.Carrier.OURS) {
            // NR이 빠져 있지만 우리가 남긴 값이 아니다(통신사 앱 등): 건드리지 않는다. 외부 제한 보류가 판단을 멈춘다
            if (own >= 0) setOwn(-1); // 우리 기록은 남이 덮어 더는 유효하지 않다
            rec("w_carrier", "why", why, "allowNr", true, "result", "external_left", "carrier", cur, "own", own);
            return Policy.Result.of(Policy.Kind.OK, now());
        }
        long target = CarrierPlan.target(cur, allowNr);
        if (!allowNr && !setOwn(target)) { // 선기록: 쓰기 전에 "이 SIM에 이 값을 남김"을 기록한다
            rec("w_carrier", "why", why, "allowNr", false, "result", "record_failed");
            return Policy.Result.of(Policy.Kind.FAILED);
        }
        String g = radio.callGuard(); // 쓰기 바로 앞에서 통화를 다시 본다
        if (g != null) {
            if (!allowNr) setOwn(own); // 쓰지 않았으니 선기록을 되돌린다
            rec("w_carrier", "why", why, "allowNr", allowNr, "result", "blocked", "guard", g);
            return Policy.Result.of(Policy.Kind.BLOCKED);
        }
        boolean called = radio.writeCarrier(target);
        long after = radio.read(Radio.CARRIER);
        boolean ok = called && after >= 0 && CarrierPlan.hasNr(after) == allowNr;
        if (ok && !allowNr && !CarrierPlan.matchesOurs(after, target)) {
            // NR은 빠졌지만 남은 값이 우리가 쓴 값(또는 LTE_CA만 빠진 값)이 아니다: 쓴 직후 남이 바꿨거나 이 폰이 모르는 방식으로
            // 값을 고쳤다. 우리 것으로 기억하지 않는다(외부 검증 지적: 남의 값을 우리 것으로 적으면 나중에 남의 제한을 푼다).
            // 조용히 LTE에 묶이지 않게 문제로 알린다.
            setOwn(own);
            problem = "5G 설정값 확인 필요";
            rec("w_carrier", "why", why, "allowNr", false, "result", "after_not_ours", "before", cur,
                    "target", target, "after", after);
            return Policy.Result.of(Policy.Kind.FAILED);
        }
        if (ok) {
            setOwn(allowNr ? -1 : after); // 막았으면 실제로 남은 값(우리 값 또는 LTE_CA만 빠진 값)을 기억한다
        } else if (!allowNr && CarrierPlan.hasNr(after)) {
            setOwn(own); // 막기 실패: 선기록을 되돌린다
        }
        rec("w_carrier", "why", why, "allowNr", allowNr, "result", ok ? "ok" : "failed", "before", cur,
                "target", target, "after", after);
        return Policy.Result.of(ok ? Policy.Kind.OK : Policy.Kind.FAILED, now());
    }

    @Override
    public String callNow() {
        return radio.callGuard();
    }

    @Override
    public boolean heavyNow() {
        long a = mobileBytes();
        SystemClock.sleep(1000);
        long b = mobileBytes();
        if (a < 0 || b < 0) return false;
        boolean heavy = b - a >= params.rHeavy;
        if (heavy) rec("heavy_traffic", "bytesPerSec", b - a);
        return heavy;
    }

    private static long mobileBytes() {
        long rx = TrafficStats.getMobileRxBytes();
        long tx = TrafficStats.getMobileTxBytes();
        return rx < 0 || tx < 0 ? -1 : rx + tx;
    }

    @Override
    public void indicator(boolean on) {
        // 상단바 표시는 없앴다(DESIGN §5.13). 상태는 타일이 보여 준다(publish)
    }

    @Override
    public void log(String ev, Object... kv) {
        rec(ev, kv);
    }

    @Override
    public void persist(Policy.State s, boolean nrAllowed) {
        // 상태 파일 대신 타일·화면 갱신(after에서 publish)
    }

    @Override
    public void keepCurrentAsOriginal() {
        rec("keep_lte_ignored", "why", "choose_lte_in_settings");
    }

    @Override
    public void stopDone(boolean restored) {
        rec("engine_stop", "restored", restored);
        halt("auto_off");
        worker.execute(() -> guarded("stopped", () -> {
            reconcile("stopped", false); // 엔진이 멈췄으니 우리 막음이 남아 있으면 푼다(통화 중이면 서비스의 재시도가 다시 부른다)
            host.stopped();
        }));
    }

    // ================================================================ 내부

    private void tick() {
        if (stopped) return;
        long t = now();
        checkUser(t);
        String sb = simBlockNow();
        if (!eq(sb, simBlock)) {
            rec("sim_block", "from", simBlock == null ? "none" : simBlock, "to", sb == null ? "none" : sb);
            simBlock = sb;
        }
        applyBlocked(t);
        refreshRestrictions(t);
        if (watcher != null) watcher.flush();
        policy.advance(t);
        after(t);
    }

    private String simBlockNow() {
        int sims = radio.activeSims();
        return sims == 1 ? null : (sims > 1 ? "dual_sim" : "sim_count_unknown");
    }

    /** 엔진이 아는 제어 불가 이유(SIM 조건이 먼저). 없으면 null. */
    private String blockReason() {
        return simBlock != null ? simBlock : (userUnreadable ? "user_unreadable" : null);
    }

    /** 제어 불가 이유를 바뀌었을 때만 판단 규칙에 알린다. */
    private void applyBlocked(long t) {
        String b = blockReason();
        if (eq(b, appliedBlock)) return;
        appliedBlock = b;
        policy.setBlocked(t, b);
    }

    /** 사용자 칸(설정 화면 값)을 읽어 NR 포함 여부가 바뀌었으면 사용자 선택으로 넘긴다. */
    private void checkUser(long t) {
        long u = radio.read(Radio.USER);
        if (u < 0 || u == userMask) return;
        boolean was = CarrierPlan.hasNr(userMask);
        boolean unknownBefore = userMask < 0;
        rec("user_mode", "from", userMask, "to", u, "nr", CarrierPlan.hasNr(u));
        userMask = u;
        if (unknownBefore || was != CarrierPlan.hasNr(u)) policy.userSelected(t, CarrierPlan.hasNr(u));
        if (userUnreadable) {
            userUnreadable = false;
            applyBlocked(t); // 다른 이유(SIM 2개 등)가 남아 있으면 그대로 제어 불가
        }
    }

    /** 사용자 칸 밖의 제한(가드 8): 절전(POWER)·2G 칸, 그리고 우리가 남긴 값이 아닌데 NR이 빠진 통신사 칸. */
    private void refreshRestrictions(long t) {
        long p = radio.read(Radio.POWER);
        long e = radio.read(Radio.ENABLE_2G);
        long c = radio.read(Radio.CARRIER);
        if (p >= 0) policy.restriction(t, Radio.POWER, CarrierPlan.hasNr(p));
        if (e >= 0) policy.restriction(t, Radio.ENABLE_2G, CarrierPlan.hasNr(e));
        CarrierPlan.Carrier k = CarrierPlan.classify(c, own());
        if (k != CarrierPlan.Carrier.UNKNOWN) policy.restriction(t, Radio.CARRIER, k != CarrierPlan.Carrier.EXTERNAL);
    }

    /**
     * 우리 막음이 남아 있는데 엔진이 쉬는 중이 아니면 푼다(force = 쉬는 중이어도 푼다, 폰 꺼짐·종료).
     * 지금 값이 우리가 남긴 값과 다르면 남이 바꾼 것이므로 풀지 않고 우리 기록만 지운다. 통화 중이면 미룬다.
     */
    private void reconcile(String why, boolean force) {
        if (selfTesting && !force) return; // 자가 점검이 막고 되돌리는 중(점검이 스스로 정리한다)
        long own = own();
        if (own < 0) return;
        long c = radio.read(Radio.CARRIER);
        CarrierPlan.Carrier k = CarrierPlan.classify(c, own);
        if (k == CarrierPlan.Carrier.OPEN || k == CarrierPlan.Carrier.EXTERNAL) {
            setOwn(-1);
            rec("own_cleared", "why", why, "carrier", c, "own", own, "as", k.name());
            return;
        }
        boolean resting = !force && !stopped && policy != null && policy.state == Policy.State.COOLDOWN && !policy.nrAllowedNow;
        if (!CarrierPlan.mustLift(k, resting)) return;
        String g = radio.callGuard();
        if (g != null) {
            if (!liftDeferredLogged) rec("lift_deferred", "why", why, "guard", g);
            liftDeferredLogged = true;
            return;
        }
        liftDeferredLogged = false;
        boolean called = radio.writeCarrier(CarrierPlan.target(c, true));
        long after = radio.read(Radio.CARRIER);
        boolean ok = called && CarrierPlan.hasNr(after);
        rec("lift", "why", why, "ok", ok, "before", c, "after", after);
        if (ok) {
            setOwn(-1);
            if ("다시 설정 필요".equals(problem)) problem = null;
        } else if (!radio.privileged()) {
            problem = "5G 막힘 · 다시 설정 필요";
        }
    }

    private void updateNetOk(long t) {
        boolean ok = dataIn && dataConn == TelephonyManager.DATA_CONNECTED;
        if (netOkLast == null || netOkLast != ok) {
            netOkLast = ok;
            policy.netOk(t, ok);
        }
    }

    /** 사건 처리 끝: 우리 막음 정리, 다음 확인 시각 예약, 타일·화면 갱신. */
    private void after(long t) {
        reconcile("event", false);
        reschedule();
        publish();
    }

    private void publish() {
        if (stopped) return;
        int mode = userMask < 0 ? TileText.MODE_UNKNOWN : (CarrierPlan.hasNr(userMask) ? TileText.MODE_NR : TileText.MODE_LTE);
        String phase = null;
        switch (policy.state) {
            case COOLDOWN:
                phase = TileText.PHASE_RESTING;
                break;
            case PROBE:
                phase = TileText.PHASE_PROBING;
                break;
            case OBSERVE:
            case SAFE_STOP:
                phase = TileText.PHASE_OBSERVE;
                break;
            default:
                break;
        }
        snapshot();
        host.publish(mode, phase, problem);
    }

    private void snapshot() {
        long t = now();
        Params p = policy.params();
        Live l = new Live();
        l.at = t;
        l.state = policy.state.name();
        l.stateSince = stateSince;
        l.stateSinceWall = stateSinceWall;
        l.stateWhy = stateWhy;
        l.hold = policy.holdWhy();
        l.blocked = policy.blocked;
        l.userNr = CarrierPlan.hasNr(userMask);
        l.wifi = wifiNow;
        l.screen = screenOn;
        l.dataIn = dataIn;
        l.dataConnected = dataConn == TelephonyManager.DATA_CONNECTED;
        if (watcher != null) {
            l.pccKnown = watcher.pccKnown();
            l.nrActual = watcher.nrConnected();
            l.display = watcher.display();
            l.lteRsrp = watcher.lteRsrp();
            l.nrRsrp = watcher.nrRsrp();
            l.nrSinr = watcher.nrSinr();
        }
        l.drops = policy.dropsInWindow(t);
        l.nDrop = p.nDrop;
        l.windowMs = p.w;
        l.oldestDrop = policy.oldestDropInWindow(t);
        l.coolUntil = policy.coolUntilAt();
        l.restWhy = restWhy;
        l.evalStart = policy.evalStartAt();
        l.useMs = policy.useMsAt(t);
        l.pActive = p.pActive;
        l.pMax = p.pMax;
        l.probeDrops = policy.probeDropCount();
        l.nProbe = p.nProbe;
        l.switchesHour = policy.switchesInHour(t);
        l.bHour = p.bHour;
        long ls = policy.lastSwitchAt();
        l.nextSwitchAt = ls < 0 ? -1 : ls + p.bGap;
        l.level = policy.level;
        l.problem = problem;
        l.selfTesting = selfTesting;
        live = l;
    }

    private void reschedule() {
        if (timer != null) timer.cancel(false);
        if (stopped || policy == null) return;
        long d = policy.nextDeadline();
        if (d < 0) return;
        long delay = d - now();
        if (delay <= 0) delay = STUCK_POLL_MS; // 방금 처리했는데도 지난 예약 = 막혀 있다
        timer = worker.schedule(() -> guarded("deadline", () -> {
            if (stopped) return;
            policy.advance(now());
            after(now());
        }), delay, TimeUnit.MILLISECONDS);
    }

    // ================================================================ 활동 기록(사람이 읽는 말)

    /** 원본 사건 기록에 쓰고, 보여 줄 만한 사건은 활동 기록에 문장으로 남긴다. */
    private void rec(String ev, Object... kv) {
        log.write(ev, kv);
        try {
            narrate(ev, kv);
        } catch (RuntimeException e) {
            log.write("error", "where", "narrate", "msg", String.valueOf(e));
        }
    }

    private static Object kv(Object[] kv, String key) {
        for (int i = 0; i + 1 < kv.length; i += 2) if (key.equals(kv[i])) return kv[i + 1];
        return null;
    }

    private static String str(Object[] kv, String key) {
        Object o = kv(kv, key);
        return o == null ? null : String.valueOf(o);
    }

    private void narrate(String ev, Object[] kv) {
        switch (ev) {
            case "state": {
                String from = str(kv, "from");
                String to = str(kv, "to");
                String why = str(kv, "why");
                long wall = System.currentTimeMillis();
                rollSummary();
                sum.onState(wall, from, to, why);
                saveSummary();
                stateSince = now();
                stateSinceWall = wall;
                stateWhy = why;
                if ("COOLDOWN".equals(to)) restWhy = why;
                String text;
                if ("COOLDOWN".equals(to)) {
                    text = ("manual_test".equals(why) ? "시험 명령으로 " : "") + "LTE로 잠깐 쉬기로 함: " + Words.why(why);
                } else if ("PROBE".equals(to)) {
                    text = "쉬는 시간 끝 → 5G 다시 확인 시작";
                } else if ("PROBE".equals(from) && "WATCH".equals(to)) {
                    text = "5G 다시 확인 결과: " + Words.why(why);
                } else {
                    text = Words.state(to) + ": " + Words.why(why);
                }
                tl.add(Timeline.Cat.JUDGE, text);
                break;
            }
            case "hold": {
                String why = str(kv, "why");
                if (!"screen_off".equals(why)) tl.add(Timeline.Cat.JUDGE, "지금은 지켜보지 않음: " + Words.hold(why));
                break;
            }
            case "hold_end": {
                String was = str(kv, "was");
                if (!"screen_off".equals(was)) tl.add(Timeline.Cat.JUDGE, "다시 지켜봄(" + Words.hold(was) + " 끝남)");
                break;
            }
            case "suppressed":
                tl.add(Timeline.Cat.JUDGE, "LTE로 쉬려다 미룸: " + Words.guard(str(kv, "why")));
                break;
            case "user_mode":
                if (kv(kv, "from") != null) {
                    tl.add(Timeline.Cat.OBS, "사용자가 " + (Boolean.TRUE.equals(kv(kv, "nr")) ? "5G 우선" : "LTE 우선") + "을 고름");
                }
                break;
            case "w_carrier": {
                String result = str(kv, "result");
                boolean allow = Boolean.TRUE.equals(kv(kv, "allowNr"));
                String why = str(kv, "why");
                if ("ok".equals(result)) {
                    String text = allow ? "5G 다시 허용(" + actionWhy(why) + ")" : "5G 잠깐 막음 → LTE로 쉬기";
                    tl.add(Timeline.Cat.ACT, text);
                    rollSummary();
                    sum.onAction(System.currentTimeMillis(), text);
                    saveSummary();
                } else if ("blocked".equals(result)) {
                    String g = str(kv, "guard");
                    String c = str(kv, "cond");
                    if (!"self_test".equals(g)) {
                        tl.add(Timeline.Cat.JUDGE, "5G/LTE 바꾸기를 미룸: " + (c != null ? Words.blocked(c) : Words.guard(g)));
                    }
                } else if ("external_left".equals(result)) {
                    tl.add(Timeline.Cat.JUDGE, "다른 쪽이 건 5G 막음은 건드리지 않음");
                } else if (!"already".equals(result) && !"stopped".equals(result)) {
                    tl.add(Timeline.Cat.ACT, "5G/LTE 바꾸기 실패(" + result + ")");
                }
                break;
            }
            case "lift": {
                boolean ok = Boolean.TRUE.equals(kv(kv, "ok"));
                String text = ok ? "남아 있던 5G 막음을 풂(" + actionWhy(str(kv, "why")) + ")" : "5G 막음 풀기 실패";
                tl.add(Timeline.Cat.ACT, text);
                if (ok) {
                    rollSummary();
                    sum.onAction(System.currentTimeMillis(), text);
                    saveSummary();
                }
                break;
            }
            case "own_cleared":
                if ("EXTERNAL".equals(str(kv, "as"))) tl.add(Timeline.Cat.JUDGE, "다른 쪽이 값을 바꿔 앱의 막음 기록을 지움");
                break;
            case "lift_deferred":
                tl.add(Timeline.Cat.JUDGE, "5G 막음 풀기를 통화 뒤로 미룸");
                break;
            case "engine_start":
                tl.add(Timeline.Cat.JUDGE, "자동 제어 시작");
                break;
            case "engine_halt": {
                String why = str(kv, "why");
                tl.add(Timeline.Cat.JUDGE, "auto_off".equals(why) ? "자동 제어 멈춤(사용자가 끔)" : "자동 제어 멈춤(폰 꺼짐·앱 종료)");
                break;
            }
            case "sim_block": {
                String to = str(kv, "to");
                tl.add(Timeline.Cat.JUDGE, "none".equals(to) ? "SIM 조건이 풀려 다시 바꿀 수 있음" : "바꿀 수 없음: " + Words.blocked(to));
                break;
            }
            case "write_failed_hold":
                tl.add(Timeline.Cat.JUDGE, "바꾸기가 실패해 자동 제어를 멈춤(타일을 껐다 켜면 다시 시작)");
                break;
            case "stop_deferred":
                tl.add(Timeline.Cat.JUDGE, "자동 제어 끄기를 미룸: " + Words.guard(str(kv, "why")));
                break;
            default:
                break;
        }
    }

    private static String actionWhy(String why) {
        if (why == null) return "";
        switch (why) {
            case "probe":
                return "다시 확인";
            case "stop":
            case "stopped":
                return "자동 제어 끔";
            case "wifi_restore":
                return "Wi-Fi 연결";
            case "restore":
                return "바꿀 수 없게 되어 되돌림";
            case "shutdown":
                return "폰 꺼짐·앱 종료";
            case "start":
                return "앱 시작 때 정리";
            case "event":
                return "쉬는 중이 아님";
            default:
                return why;
        }
    }

    /** 앱이 실제로 망을 바꾼 일을 오늘의 활동 "마지막 개입"에 남긴다. */
    private void noteAction(String text) {
        rollSummary();
        sum.onAction(System.currentTimeMillis(), text);
        saveSummary();
    }

    private void rollSummary() {
        Calendar c = Calendar.getInstance();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(c.getTimeInMillis()));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        sum.roll(day, c.getTimeInMillis());
    }

    private void saveSummary() {
        sumStore.edit().putString(SUMMARY_KEY, sum.save()).apply();
    }

    // ================================================================ 자가 점검

    /**
     * 자가 점검(사용자가 앱에서 누름): 통신사 칸에서 5G를 잠깐 막았다가 되돌린다. 합격 = 확실히 확인되는 것만:
     * 자격·사용자 모드·막기(다시 읽어 확인)·되돌리기(다시 읽어 확인, 우리 기록 지움). 실제 연결에서 5G 칸이 빠지는 모습은 보이면 덤으로 알린다
     * (5G 칸은 데이터를 쓸 때만 붙으므로). 엔진 안전 규칙을 그대로 쓴다: 통화 중·쉬는 중·재시험 중·제어 불가면 하지 않고, 1분 간격.
     * 점검 중과 끝난 뒤 15초는 끊김을 판단에 세지 않는다(점검이 쉬기를 일으키지 않게). 판단 규칙의 1시간 전환 한도는 쓰지 않는다.
     */
    void selfTest(TestSink s) {
        long t = now();
        String no = selfTestBlocker(t);
        if (no != null) {
            s.line(no, false);
            s.done(false);
            return;
        }
        s.line("5G/LTE 전환 권한: 있음", true);
        s.line("고른 모드: 5G 우선", true);
        long cur = radio.read(Radio.CARRIER);
        if (CarrierPlan.classify(cur, own()) != CarrierPlan.Carrier.OPEN) {
            s.line("지금 5G가 허용된 상태가 아님(다른 쪽이 막고 있을 수 있음)", false);
            s.done(false);
            return;
        }
        s.line("지금 5G 허용 상태", true);
        if (wifiNow) s.line("참고: Wi-Fi를 쓰는 중이라 실제 연결 변화는 안 보일 수 있음", true);
        selfTesting = true;
        store.edit().putLong(LAST_SELF_TEST, t).commit();
        rec("self_test", "step", "start", "carrier", cur);
        tl.add(Timeline.Cat.ACT, "자가 점검 시작(사용자가 누름)");
        boolean nrBefore = watcher != null && watcher.nrConnected();
        long target = CarrierPlan.target(cur, false);
        if (!setOwn(target)) {
            finishSelfTest(s, false, "기록 저장 실패로 점검 중단");
            return;
        }
        String g = radio.callGuard();
        if (g != null) {
            setOwn(-1);
            finishSelfTest(s, false, "통화 중이라 점검 중단");
            return;
        }
        boolean called = radio.writeCarrier(target);
        long after = radio.read(Radio.CARRIER);
        boolean ok = called && after >= 0 && !CarrierPlan.hasNr(after) && CarrierPlan.matchesOurs(after, target);
        rec("self_test", "step", "block", "ok", ok, "after", after);
        if (!ok) {
            if (after < 0) {
                // 읽지 못함: 막혔는지 모른다. 선기록(목표값)을 그대로 두어 되돌리기·종료·다음 시작이 풀 수 있게 한다(외부 검증 지적)
            } else if (!CarrierPlan.hasNr(after) && CarrierPlan.matchesOurs(after, target)) {
                setOwn(after);
            } else {
                setOwn(-1); // 막히지 않았거나(5G 그대로) 남의 값: 우리 것으로 기억하지 않는다
            }
            s.line("5G 막기 실패(값 " + after + ")", false);
            restoreSelfTest(s, false);
            return;
        }
        setOwn(after);
        s.line("5G 막기: 5G가 빠진 것을 다시 읽어 확인", true);
        tl.add(Timeline.Cat.ACT, "자가 점검: 5G 잠깐 막음");
        noteAction("자가 점검: 5G 잠깐 막음");
        watchSelfTest(s, nrBefore, now() + SELF_TEST_WATCH_MS, now());
    }

    private String selfTestBlocker(long t) {
        if (stopped || policy == null) return "자동 제어가 꺼져 있어 점검할 수 없음";
        if (selfTesting) return "이미 점검 중";
        long last = store.getLong(LAST_SELF_TEST, -1);
        if (last > t) last = -1; // 재부팅 뒤(부팅 후 경과가 다시 0부터)
        if (last >= 0 && t - last < SELF_TEST_GAP_MS) {
            return "방금 점검했음(" + Words.mmss(SELF_TEST_GAP_MS - (t - last)) + " 뒤 다시)";
        }
        if (!radio.privileged()) return "5G/LTE 전환 권한이 없음(처음 설정 필요)";
        if (!CarrierPlan.hasNr(userMask)) return "LTE 우선이라 점검할 5G가 없음(5G 우선에서 해 주세요)";
        if (policy.state == Policy.State.COOLDOWN) return "지금 LTE로 쉬는 중이라 점검하지 않음";
        if (policy.state == Policy.State.PROBE) return "지금 5G 다시 확인 중이라 점검하지 않음";
        if (policy.state == Policy.State.OBSERVE || policy.state == Policy.State.SAFE_STOP) {
            return "지금 바꿀 수 없는 상태라 점검하지 않음";
        }
        String b = blockReason();
        if (b != null) return "바꿀 수 없음: " + Words.blocked(b);
        if (policy.settling()) return "방금 바꿔서 연결이 자리 잡는 중(잠시 뒤 다시)";
        if (radio.callGuard() != null) return "통화 중이라 점검하지 않음";
        if (own() >= 0) return "이전 막음을 정리하는 중(잠시 뒤 다시)";
        return null;
    }

    /** 막은 뒤 실제 연결에서 5G 칸이 빠지는지 잠깐 지켜본다(작업 스레드를 막지 않게 예약으로). */
    private void watchSelfTest(TestSink s, boolean nrBefore, long deadline, long blockedAt) {
        worker.schedule(() -> guarded("self_test_watch", () -> {
            if (stopped) {
                s.line("자동 제어가 멈춰 점검 중단(막음은 자동으로 풀림)", false);
                s.done(false);
                return;
            }
            if (!nrBefore) {
                s.line("폰을 쓰지 않아 원래 5G가 붙어 있지 않았음 → 연결 변화 확인은 건너뜀", true);
                restoreSelfTest(s, true);
            } else if (watcher != null && !watcher.nrConnected()) {
                s.line("실제로 5G가 떨어짐(" + secs(now() - blockedAt) + "초)", true);
                restoreSelfTest(s, true);
            } else if (now() >= deadline) {
                s.line("6초 안에 연결 변화는 안 보였음(5G는 막혀 있었음)", true);
                restoreSelfTest(s, true);
            } else {
                watchSelfTest(s, nrBefore, deadline, blockedAt);
            }
        }), SELF_TEST_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void restoreSelfTest(TestSink s, boolean passSoFar) {
        long own = own();
        long cur = radio.read(Radio.CARRIER);
        CarrierPlan.Carrier k = CarrierPlan.classify(cur, own);
        boolean ok;
        if (k == CarrierPlan.Carrier.UNKNOWN) {
            // 읽지 못함은 "남이 바꿈"이 아니다: 기록을 두고 미룬다(읽히면 정리가 되돌린다, 외부 검증 지적)
            finishSelfTest(s, false, "값을 읽지 못해 되돌리기를 미룸(다시 읽히면 자동으로 되돌림)");
            return;
        }
        if (k == CarrierPlan.Carrier.OPEN) {
            setOwn(-1);
            ok = true;
        } else if (k == CarrierPlan.Carrier.OURS) {
            String g = radio.callGuard(); // 쓰기 바로 앞에서 통화를 다시 본다(외부 검증 지적: 읽는 사이 시작된 통화)
            if (g != null) {
                finishSelfTest(s, false, "통화가 시작돼 5G 되돌리기를 통화 뒤로 미룸(끝나면 자동으로 되돌림)");
                return;
            }
            boolean called = radio.writeCarrier(CarrierPlan.target(cur, true));
            long after = radio.read(Radio.CARRIER);
            ok = called && CarrierPlan.hasNr(after);
            if (ok) setOwn(-1);
            rec("self_test", "step", "restore", "ok", ok, "after", after);
        } else {
            setOwn(-1);
            s.line("다른 쪽이 값을 바꿔서 되돌리지 않음", false);
            finishSelfTest(s, false, null);
            return;
        }
        s.line("5G 되돌리기: 5G가 다시 허용된 것을 확인, 막음 기록 지움", ok);
        tl.add(Timeline.Cat.ACT, ok ? "자가 점검: 5G 되돌림" : "자가 점검: 5G 되돌리기 실패");
        if (ok) noteAction("자가 점검: 5G 되돌림");
        finishSelfTest(s, passSoFar && ok, null);
    }

    private void finishSelfTest(TestSink s, boolean pass, String why) {
        selfTesting = false;
        quietUntil = now() + SELF_TEST_QUIET_MS;
        if (why != null) s.line(why, false);
        rec("self_test", "step", "done", "pass", pass);
        tl.add(Timeline.Cat.JUDGE, pass ? "자가 점검 결과: 정상(5G를 막고 되돌릴 수 있음)" : "자가 점검 결과: 문제 있음");
        s.line(pass ? "결과: 컨트롤러 정상(5G를 막고 되돌릴 수 있음)" : "결과: 문제 있음(위 내용 확인)", pass);
        s.done(pass);
        after(now()); // 남은 막음이 있으면 정리(통화 중이면 통화 뒤)
    }

    /** 작업 스레드에서 도는 일의 예외가 엔진을 멈추지 않게 기록만 하고 넘긴다. */
    void guarded(String where, Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            rec("error", "where", where, "msg", String.valueOf(e));
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
