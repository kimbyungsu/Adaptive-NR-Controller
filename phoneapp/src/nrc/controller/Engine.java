package nrc.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 판단 엔진 연결부(DESIGN §5.13, daemon Controller를 통신사 칸 방식으로 옮김).
 * - 관찰 사건을 판단 규칙(Policy)에 넘기고, 규칙이 정한 쓰기를 통신사 칸(CARRIER)의 NR 비트로만 실행한다.
 * - 사용자 선택 = 사용자 칸(USER, 설정 화면이 보여 주는 값). 읽기만 하고 절대 쓰지 않는다. 그래서 예전 USER 쓰기 방식의
 *   보호 규칙(키 재확인·확인 창 등, §5.12)은 필요 없다.
 * - 안전장치: 엔진이 쉬는 중(쿨다운)이 아닌데 우리 막음이 남아 있으면 곧바로 푼다(시작·사용자 LTE 선택·자동 제어 끄기·
 *   Wi-Fi 되돌리기 뒤 등). 통화 중이면 통화가 끝날 때까지 미룬다. 폰이 꺼지기 직전에도 푼다(liftForShutdown).
 * 모든 메서드는 작업 스레드(worker)에서만 불린다. 밖에서 부를 때는 post로 넘긴다.
 */
final class Engine implements Policy.Env, Watcher.Listener {
    interface Host {
        /** 상태가 바뀌었다(타일·상세 화면 갱신). mode = TileText.MODE_*. */
        void publish(int mode, String phase, String problem);

        /** 자동 제어 끄기(stop)가 끝났다. */
        void stopped();
    }

    static final long TICK_MS = 30_000;
    static final long STUCK_POLL_MS = 5_000;
    static final String STORE = "engine";
    /** 우리가 통신사 칸에서 NR을 뺀다고 쓰기 전에 남기는 기록. 풀기에 성공하면 지운다. */
    static final String OURS = "oursBlocked";

    private final Context ctx;
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
    private boolean stopped;
    private boolean dataIn = true;
    private int dataConn = -1;
    private Boolean netOkLast;
    private boolean liftDeferredLogged;
    private String problem;
    /** 시작 때 건 제어 불가 이유(사용자 칸을 다시 읽게 되면 "user_unreadable"만 푼다). */
    private String blockedWhy;

    Engine(Context ctx, Radio radio, Journal log, ScheduledExecutorService worker, Host host) {
        this.ctx = ctx;
        this.radio = radio;
        this.log = log;
        this.worker = worker;
        this.host = host;
        this.store = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    static boolean oursBlocked(Context c) {
        return c.getSharedPreferences(STORE, Context.MODE_PRIVATE).getBoolean(OURS, false);
    }

    private boolean ours() {
        return store.getBoolean(OURS, false);
    }

    /** 동기 저장(쓰기 전 선기록이 쓰기보다 먼저 확실히 남도록). */
    private boolean setOurs(boolean v) {
        return store.edit().putBoolean(OURS, v).commit();
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    // ================================================================ 수명

    void start(boolean wifi, boolean screen) {
        long t = now();
        screenOn = screen;
        policy = new Policy(params, this);
        reconcile("start", false); // 지난 실행이 남긴 막음을 먼저 푼다(엔진은 아직 쉬는 중이 아니다)
        int sims = radio.activeSims();
        long u = radio.read(Radio.USER);
        userMask = u;
        String blocked = sims == 1 ? null : (sims > 1 ? "dual_sim" : "sim_count_unknown");
        if (u < 0) blocked = "user_unreadable";
        blockedWhy = blocked;
        log.write("engine_start", "sub", radio.sub, "user", u, "carrier", radio.read(Radio.CARRIER),
                "power", radio.read(Radio.POWER), "enable2g", radio.read(Radio.ENABLE_2G), "sims", sims,
                "blocked", blocked == null ? "none" : blocked, "wifi", wifi, "screen", screen);
        policy.screen(t, screen);
        policy.wifi(t, wifi);
        refreshRestrictions(t);
        if (blocked != null) policy.setBlocked(t, blocked);
        policy.setMode(t, CarrierPlan.hasNr(u));
        policy.startQuiet(t);
        watcher = new Watcher(log, this);
        radio.tm().registerTelephonyCallback(worker, watcher);
        ticker = worker.scheduleWithFixedDelay(() -> guarded("tick", this::tick), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        after(t);
    }

    /** 자동 제어 끄기: 판단 규칙의 종료 절차(쉬는 중이면 풀기)를 거친 뒤 stopped()를 부른다. */
    void stop() {
        if (stopped || policy == null) return;
        policy.stop(now());
        after(now());
    }

    /** 폰이 꺼지기 직전(작업 스레드에서): 쉬는 중이어도 우리 막음을 푼다. */
    void liftForShutdown() {
        reconcile("shutdown", true);
        if (watcher != null) watcher.shutdown("shutdown");
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
        policy.oos(t, sinceDataMs, screenOn);
        after(t);
    }

    @Override
    public void onService(long t) {
        if (stopped) return;
        policy.service(t);
        after(t);
    }

    @Override
    public void onNrOff(long t, long sinceDataMs, long dwellMs) {
        if (stopped) return;
        policy.nrOff(t, sinceDataMs, dwellMs, screenOn);
        after(t);
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

    /** 판단 규칙이 정한 쓰기: 통신사 칸의 NR 비트만 바꾼다. 쓰기 직전 통화를 다시 본다(막히면 BLOCKED). */
    @Override
    public Policy.Result write(long t, boolean allowNr, String why) {
        if (!radio.privileged()) {
            problem = "다시 설정 필요";
            log.write("w_carrier", "why", why, "allowNr", allowNr, "result", "no_privilege");
            return Policy.Result.of(Policy.Kind.FAILED);
        }
        String g = radio.callGuard();
        if (g != null) {
            log.write("w_carrier", "why", why, "allowNr", allowNr, "result", "blocked", "guard", g);
            return Policy.Result.of(Policy.Kind.BLOCKED);
        }
        long cur = radio.read(Radio.CARRIER);
        if (cur < 0) {
            log.write("w_carrier", "why", why, "allowNr", allowNr, "result", "unreadable");
            return Policy.Result.of(Policy.Kind.FAILED);
        }
        if (CarrierPlan.hasNr(cur) == allowNr) {
            if (allowNr) setOurs(false);
            log.write("w_carrier", "why", why, "allowNr", allowNr, "result", "already", "carrier", cur);
            return Policy.Result.of(Policy.Kind.OK, now());
        }
        if (allowNr && !ours()) {
            // NR이 빠져 있지만 우리가 뺀 것이 아니다(통신사 앱 등): 건드리지 않는다. 외부 제한 보류가 판단을 멈춘다
            log.write("w_carrier", "why", why, "allowNr", true, "result", "external_left", "carrier", cur);
            return Policy.Result.of(Policy.Kind.OK, now());
        }
        long target = CarrierPlan.target(cur, allowNr);
        if (!allowNr && !setOurs(true)) { // 선기록: 쓰기 전에 "우리가 막음"을 남긴다
            log.write("w_carrier", "why", why, "allowNr", false, "result", "record_failed");
            return Policy.Result.of(Policy.Kind.FAILED);
        }
        boolean called = radio.writeCarrier(target);
        long after = radio.read(Radio.CARRIER);
        boolean ok = called && after >= 0 && CarrierPlan.hasNr(after) == allowNr;
        if (allowNr && ok) setOurs(false);
        if (!allowNr && !ok && CarrierPlan.hasNr(after)) setOurs(false); // 막기 실패: 기록을 되돌린다
        log.write("w_carrier", "why", why, "allowNr", allowNr, "result", ok ? "ok" : "failed", "before", cur,
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
        if (heavy) log.write("heavy_traffic", "bytesPerSec", b - a);
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
        log.write(ev, kv);
    }

    @Override
    public void persist(Policy.State s, boolean nrAllowed) {
        // 상태 파일 대신 타일·화면 갱신(after에서 publish)
    }

    @Override
    public void keepCurrentAsOriginal() {
        log.write("keep_lte_ignored", "why", "choose_lte_in_settings");
    }

    @Override
    public void stopDone(boolean restored) {
        stopped = true;
        if (timer != null) timer.cancel(false);
        if (ticker != null) ticker.cancel(false);
        if (watcher != null) {
            try {
                radio.tm().unregisterTelephonyCallback(watcher);
            } catch (RuntimeException ignored) {
                // 이미 풀렸으면 무시
            }
            watcher.shutdown("auto_off");
        }
        log.write("engine_stop", "restored", restored);
        worker.execute(() -> guarded("stopped", () -> {
            reconcile("stopped", false); // 엔진이 멈췄으니 우리 막음이 남아 있으면 푼다(통화 중이면 서비스의 주기 확인이 다시 부른다)
            publish();
            host.stopped();
        }));
    }

    // ================================================================ 내부

    private void tick() {
        if (stopped) return;
        long t = now();
        checkUser(t);
        refreshRestrictions(t);
        if (watcher != null) watcher.flush();
        policy.advance(t);
        after(t);
    }

    /** 사용자 칸(설정 화면 값)을 읽어 NR 포함 여부가 바뀌었으면 사용자 선택으로 넘긴다. */
    private void checkUser(long t) {
        long u = radio.read(Radio.USER);
        if (u < 0 || u == userMask) return;
        boolean was = CarrierPlan.hasNr(userMask);
        boolean unknownBefore = userMask < 0;
        log.write("user_mode", "from", userMask, "to", u, "nr", CarrierPlan.hasNr(u));
        userMask = u;
        if (unknownBefore && "user_unreadable".equals(blockedWhy)) {
            blockedWhy = null;
            policy.setBlocked(t, null);
        }
        if (unknownBefore || was != CarrierPlan.hasNr(u)) policy.userSelected(t, CarrierPlan.hasNr(u));
    }

    /** 사용자 칸 밖의 제한(가드 8): 절전(POWER)·2G 칸, 그리고 우리가 막지 않았는데 NR이 빠진 통신사 칸. */
    private void refreshRestrictions(long t) {
        long p = radio.read(Radio.POWER);
        long e = radio.read(Radio.ENABLE_2G);
        long c = radio.read(Radio.CARRIER);
        if (p >= 0) policy.restriction(t, Radio.POWER, CarrierPlan.hasNr(p));
        if (e >= 0) policy.restriction(t, Radio.ENABLE_2G, CarrierPlan.hasNr(e));
        CarrierPlan.Carrier k = CarrierPlan.classify(c, ours());
        if (k != CarrierPlan.Carrier.UNKNOWN) policy.restriction(t, Radio.CARRIER, k != CarrierPlan.Carrier.EXTERNAL);
    }

    /**
     * 우리 막음이 남아 있는데 엔진이 쉬는 중이 아니면 푼다(force = 쉬는 중이어도 푼다, 폰 꺼짐 직전).
     * 통화 중이면 미룬다(다음 사건·주기 확인에서 다시).
     */
    private void reconcile(String why, boolean force) {
        if (!ours()) return;
        long c = radio.read(Radio.CARRIER);
        CarrierPlan.Carrier k = CarrierPlan.classify(c, true);
        if (k == CarrierPlan.Carrier.OPEN) {
            setOurs(false);
            log.write("ours_cleared", "why", why, "carrier", c);
            return;
        }
        boolean resting = !force && !stopped && policy != null && policy.state == Policy.State.COOLDOWN && !policy.nrAllowedNow;
        if (!CarrierPlan.mustLift(k, resting)) return;
        String g = radio.callGuard();
        if (g != null) {
            if (!liftDeferredLogged) log.write("lift_deferred", "why", why, "guard", g);
            liftDeferredLogged = true;
            return;
        }
        liftDeferredLogged = false;
        boolean called = radio.writeCarrier(CarrierPlan.target(c, true));
        long after = radio.read(Radio.CARRIER);
        boolean ok = called && CarrierPlan.hasNr(after);
        log.write("lift", "why", why, "ok", ok, "before", c, "after", after);
        if (ok) {
            setOurs(false);
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
        int mode = userMask < 0 ? TileText.MODE_UNKNOWN : (CarrierPlan.hasNr(userMask) ? TileText.MODE_NR : TileText.MODE_LTE);
        String phase = null;
        if (policy != null && !stopped) {
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
        }
        host.publish(mode, phase, problem);
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

    /** 작업 스레드에서 도는 일의 예외가 엔진을 멈추지 않게 기록만 하고 넘긴다. */
    void guarded(String where, Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            log.write("error", "where", where, "msg", String.valueOf(e));
        }
    }
}
