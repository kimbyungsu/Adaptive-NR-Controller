package nrc;

import android.content.Context;
import android.os.SystemClock;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 제어 연결부(Phase 2, DESIGN §5.12 "설정 = 사용자 고정값"). 관찰기 사건을 정책 엔진에 넘기고, 엔진이 정한 쓰기를
 * USER 허용 타입으로만 실행한다(삼성 설정 화면 키는 쓰지 않는다). 사용자 선택은 설정 키로 판단하고,
 * 상단바 표시·상태 파일을 맡는다.
 * 모든 메서드는 데몬 작업 스레드에서만 불린다. 엔진 안에서 불린 Env 메서드가 엔진에 다시 입력할 때는 작업 큐로 미룬다.
 */
final class Controller implements Policy.Env, Observer.Listener {
    /** 시작 불가(종료 코드와 이유). */
    static final class StartError extends Exception {
        final int code;

        StartError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static final int EXIT_NO_USER_VALUE = 8;
    /** 시험용 강제 쿨다운의 쉬는 시간(전환 최소 간격 B_gap과 같게: 더 짧아도 재시험은 최소 간격 뒤라서). */
    static final long TEST_COOL_MS = 120_000;
    /**
     * USER 알림 뒤 설정 키를 다시 확인할 때까지 5G를 켜는 쓰기를 미루는 시간(§5.12 보호 규칙 2).
     * 삼성은 USER 변경을 끝낸 뒤 키를 쓴다. 초기값이며, 기기에서 USER 알림~키 변경 틈(`user_mode_key`의 sinceUserCbMs)을 재어 조정한다.
     */
    static final long CONFIRM_MS = 3_000;
    /** 종료 확인에서 키·USER 읽기 실패를 다시 볼 횟수(5초 간격, 약 1분). */
    static final int STOP_READ_RETRIES = 12;

    private final Journal log;
    private final Context ctx;
    private final int sub;
    private final int activeSubs;
    private final ScheduledExecutorService worker;
    private final Params params = new Params();
    private final Policy policy;
    private final Actuator act;
    private final Indicator indicator;
    private final StateStore store;
    private final Saved saved = new Saved();

    /** 사용자 선택(설정 키)에 맞는 USER 마스크 = 원래 모드. */
    private long original;
    private long knownUser;
    /** 사용자가 설정 화면에서 고른 모드 번호(설정 키). 컨트롤러는 이 키를 쓰지 않는다. */
    private int keyMode;
    private boolean keyUnavailable;
    private boolean leftoverLte;
    /** 이 시각까지 5G를 켜는 쓰기를 미룬다(USER 알림 뒤 키 재확인, §5.12 보호 규칙 2). -1 = 없음. */
    private long confirmUntil = -1;
    /** 마지막 USER 알림 시각(키 변경까지의 틈 기록용). */
    private long lastUserCb = -1;
    /** 종료 확인 창(보호 규칙 4) 중: 엔진의 새 쓰기·표시를 받지 않는다. */
    private boolean stopping;
    /** 종료 중 맞춤이 막혀 미룬 것을 기록했는지(한 번만 기록). */
    private boolean stopDeferLogged;
    /** 종료 확인에서 키·USER를 읽지 못한 횟수(STOP_READ_RETRIES를 넘으면 비정상 종료로 끝낸다). */
    private int stopReadFails;
    /** 명령까지 실행했는데 실패한 맞춤의 (키, USER). 같은 상태면 다시 쓰지 않는다(자동 재시도 없음, §5.6.3). */
    private int alignFailKey = -1;
    private long alignFailUser = -1;
    private boolean trafficOk;
    private int dataReg = -1;
    private int dataConn = -1;
    private Boolean netOkLast;
    private ScheduledFuture<?> timer;
    private Runnable onStopDone = () -> { };

    /** 원래 모드 확정(DESIGN §5.12): 설정 키가 가리키는 모드. 예전 옵션(leftover·original)은 쓰지 않는다. */
    Controller(Journal log, Context ctx, int sub, int slot, int activeSubs, ScheduledExecutorService worker,
               Map<String, String> opts, String indicatorSlot, String iconPkg, int iconId) throws StartError {
        this.log = log;
        this.ctx = ctx;
        this.sub = sub;
        this.activeSubs = activeSubs;
        this.worker = worker;
        this.act = new Actuator(log, sub, slot, new AndroidIo(sub));
        this.indicator = new Indicator(log, indicatorSlot, iconPkg, iconId);
        this.store = new StateStore(Nrd.HOME);
        this.policy = new Policy(params, this);
        saved.sub = sub;
        saved.slot = slot;

        long cur = Phone.allowedUser(sub);
        if (cur < 0) throw new StartError(EXIT_NO_USER_VALUE, "cannot read USER allowed network types");
        knownUser = cur;
        keyMode = act.keyMode();
        Saved prev = null;
        try {
            prev = store.read();
        } catch (Exception e) {
            log.write("warn", "what", "state_file_unreadable", "msg", String.valueOf(e)); // 판단에는 쓰지 않는다(기록용)
        }
        UserMode.Start d = UserMode.decide(cur, keyMode, Phone.NR_BIT, AndroidIo::modeOfMask);
        original = d.original;
        leftoverLte = d.leftoverLte;
        keyUnavailable = d.keyUnavailable;
        if (opts.containsKey("leftover") || opts.containsKey("original")) {
            log.write("warn", "what", "option_ignored", "why", "start_decision_uses_settings_key");
        }
        log.write("original", "source", d.source, "mask", original, "key", keyMode, "user", cur,
                "prevOriginal", prev == null ? -1 : prev.original,
                "prevLastWritten", prev == null ? -1 : prev.lastWritten, "prevClean", prev != null && prev.clean);
    }

    void onStopDone(Runnable r) {
        onStopDone = r;
    }

    /** 제어 가능 여부를 확인하고 활성화 판정을 한다(관찰기 등록 뒤). */
    void begin() {
        long t = now();
        indicator.cleanupLeftover();
        trafficOk = Phone.initTraffic(ctx);
        if (!trafficOk) log.write("warn", "what", "traffic_unavailable_heavy_guard_off");
        String blocked = null;
        if (activeSubs != 1) blocked = activeSubs > 1 ? "dual_sim" : "sim_count_unknown";
        if (blocked == null && keyUnavailable) blocked = "key_unavailable";
        if (blocked == null) {
            String k = act.checkKey(original);
            if (k != null) blocked = "key_unusable_" + k;
        }
        if (blocked == null && !indicator.usable()) blocked = "indicator_slot_busy";
        log.write("control_check", "blocked", blocked == null ? "none" : blocked, "original", original,
                "user", knownUser, "key", keyMode, "slot", indicator.slot(), "traffic", trafficOk);
        saved.clean = false;
        saved.original = original;
        saved.originalMode = keyMode;
        persistSaved();
        if (leftoverLte) policy.nrAllowedNow = false; // 사용자 선택은 5G 우선인데 LTE로 남아 있음: 조건이 되면 곧바로 재시험
        if (blocked != null) policy.setBlocked(t, blocked);
        policy.setMode(t, hasNr(original));
        policy.startQuiet(t);
        syncUser(t, "start"); // 사용자 선택(LTE 등)과 다른 USER면 사용자 쪽으로 맞춘다(보호 규칙 3)
        boolean watch = KeyWatch.start(ctx, act.keyName(), () -> worker.execute(() -> {
            try {
                onKeyChange(now());
            } catch (Throwable e) {
                log.write("error", "where", "key_watch", "msg", String.valueOf(e));
            }
        }));
        log.write(watch ? "key_watch" : "warn", "what", watch ? "registered" : "key_watch_unavailable");
        reschedule();
    }

    // ================================================================ 명령(nrctl)

    void command(String verb) {
        long t = now();
        switch (verb) {
            case "pause":
                policy.pause(t);
                break;
            case "resume":
                policy.resume(t);
                break;
            case "keep-lte":
                // §5.12: 사용자 선택은 설정 화면이 정한다. LTE로 계속 쓰려면 설정에서 LTE 우선을 고른다
                log.write("keep_lte_ignored", "why", "choose_lte_in_settings");
                break;
            case "test-cooldown":
                policy.testCooldown(t, TEST_COOL_MS);
                break;
            default:
                log.write("warn", "what", "unknown_command", "verb", verb);
        }
        reschedule();
    }

    /** 종료 요청. 되돌리기가 끝나면 onStopDone이 불린다(통화 중이면 통화 뒤). */
    void requestStop() {
        if (stopping) {
            log.write("stop_request_ignored", "why", "already_stopping");
            return;
        }
        policy.stop(now());
        reschedule();
    }

    // ================================================================ Observer.Listener

    @Override
    public void onScreen(long t, boolean on) {
        policy.screen(t, on);
        reschedule();
    }

    @Override
    public void onCall(long t, int state) {
        policy.call(t, state);
        if (state == 0 && !stopping) syncUser(t, "call_end"); // 통화로 막혔던 맞춤을 다시
        reschedule();
    }

    @Override
    public void onServiceState(long t, int reg, boolean roaming) {
        dataReg = reg;
        policy.roaming(t, roaming);
        updateNetOk(t);
        reschedule();
    }

    @Override
    public void onOos(long t, long sinceDataMs, boolean screenOn) {
        policy.oos(t, sinceDataMs, screenOn);
        reschedule();
    }

    @Override
    public void onService(long t) {
        policy.service(t);
        reschedule();
    }

    @Override
    public void onNrOff(long t, long sinceDataMs, long dwellMs, boolean screenOn) {
        policy.nrOff(t, sinceDataMs, dwellMs, screenOn);
        reschedule();
    }

    @Override
    public void onDataActivity(long t, boolean active) {
        policy.dataActivity(t, active);
        reschedule();
    }

    @Override
    public void onDataConnection(long t, int state) {
        dataConn = state;
        updateNetOk(t);
        reschedule();
    }

    /**
     * USER 허용 타입 알림. 사용자가 설정에서 모드를 고르면 삼성이 USER를 바꾼 뒤 설정 키를 쓴다(§5.12).
     * 자기 쓰기 알림인지 가리지 않고 키 재확인 창을 연다(자기 알림이면 5G 쓰기가 몇 초 늦을 뿐, 오판은 없다).
     */
    @Override
    public void onAllowed(long t, int reason, long mask) {
        if (reason == Phone.REASON_USER) {
            lastUserCb = t;
            if (!stopping) {
                openConfirm(t);
                if (!syncUser(t, "user_cb")) checkUser(t);
            }
        } else {
            policy.restriction(t, reason, hasNr(mask));
        }
        reschedule();
    }

    /** 설정 키 변경 감시(KeyWatch)가 알린 변경. */
    void onKeyChange(long t) {
        if (stopping) return; // 종료 확인 창은 stopCheck가 본다
        syncUser(t, "key_watch");
        reschedule();
    }

    /** 기본 인터넷 경로 감시를 붙이지 못함: Wi-Fi 여부를 모르므로 제어 불가(관찰만). Nrd가 작업 스레드에서 부른다. */
    void wifiWatchUnavailable() {
        policy.setBlocked(now(), "wifi_watch_unavailable");
        reschedule();
    }

    /** 기본 인터넷 경로(Wi-Fi/셀룰러) 변화. Nrd가 작업 스레드에서 부른다. */
    void onDefaultNetwork(long t, boolean wifi) {
        policy.wifi(t, wifi);
        reschedule();
    }

    @Override
    public void onTick(long t) {
        if (!stopping && !syncUser(t, "tick")) checkUser(t);
        if (indicator.lost()) policy.setBlocked(t, "indicator_lost");
        policy.advance(t);
        reschedule();
    }

    // ================================================================ Policy.Env

    @Override
    public Policy.Result write(long t, boolean allowNr, String why) {
        if (stopping) {
            log.write("write_deferred", "why", why, "reason", "stopping");
            return Policy.Result.of(Policy.Kind.BLOCKED);
        }
        // 보호 규칙 1: 쓰기 직전 설정 키 확인. 사용자가 모드를 바꿨으면 쓰지 않고 채택한다(엔진이 applyUser로 반영).
        // 엔진 안에서 불렸으므로 엔진에 다시 입력하지 않는다(맞춤 쓰기는 작업 큐로 미룬다).
        int k = act.keyMode();
        if (k >= 0 && k != keyMode) return adoptInsideEngine(k, Phone.allowedUser(sub), "before_write");
        // 보호 규칙 2: USER 알림 뒤 키 재확인 창 동안 5G를 켜는 쓰기는 미룬다(창이 끝나는 시각에 다시)
        if (allowNr && confirmUntil >= 0 && t < confirmUntil) {
            log.write("write_deferred", "why", why, "reason", "confirm", "untilMs", confirmUntil - t);
            return Policy.Result.of(Policy.Kind.BLOCKED, confirmUntil + 100);
        }
        long cur = Phone.allowedUser(sub);
        if (cur >= 0 && cur != knownUser) {
            adoptExternal(cur, "before_write");
            return Policy.Result.adopted(hasNr(cur));
        }
        long target = allowNr ? (original | Phone.NR_BIT) : (original & ~Phone.NR_BIT);
        if (target == knownUser) {
            log.write("write_skipped", "why", why, "reason", "already_target", "mask", target);
            return Policy.Result.of(Policy.Kind.OK);
        }
        int targetMode = act.modeOf(target);
        // 통화·설정 키 재확인은 Actuator가 선기록을 마친 뒤 USER 쓰기 명령 바로 앞에서 한다(막히면 BLOCKED)
        Actuator.Outcome o = act.write(target, keyMode, why, traceWriter());
        if (o.user >= 0) knownUser = o.user;
        saved.endWrite(o.kind, target, targetMode, o.user, o.adopted); // 컨트롤러가 실제로 남긴 값만 lastWritten
        persistSaved();
        if (o.keyChanged) return adoptInsideEngine(o.key, o.user, "at_write");
        if (o.adopted) {
            adoptExternal(o.user, "after_write");
            return Policy.Result.adopted(hasNr(o.user));
        }
        return Policy.Result.of(o.kind, now());
    }

    @Override
    public String callNow() {
        return Phone.callGuard(sub);
    }

    @Override
    public boolean heavyNow() {
        if (!trafficOk) return false;
        long a = Phone.mobileBytes();
        SystemClock.sleep(1000);
        long b = Phone.mobileBytes();
        if (a < 0 || b < 0) return false;
        boolean heavy = b - a >= params.rHeavy;
        if (heavy) log.write("heavy_traffic", "bytesPerSec", b - a);
        return heavy;
    }

    @Override
    public void indicator(boolean on) {
        if (!on) {
            indicator.hide();
            return;
        }
        if (stopping) return; // 종료 확인 창에서는 다시 띄우지 않는다
        if (!indicator.show()) {
            // 표시 없이는 제어하지 않는다(DESIGN §5.8) → 엔진 밖에서 제어 불가 조건으로 알린다
            worker.execute(() -> {
                policy.setBlocked(now(), "indicator_unavailable");
                reschedule();
            });
        }
    }

    @Override
    public void log(String ev, Object... kv) {
        log.write(ev, kv);
    }

    @Override
    public void persist(Policy.State s, boolean nrAllowed) {
        saved.state = s.name() + (nrAllowed ? "" : "/LTE");
        persistSaved();
    }

    @Override
    public void keepCurrentAsOriginal() {
        // §5.12부터 쓰지 않는다(command("keep-lte")가 엔진을 부르지 않음). 엔진 인터페이스 호환용.
        log.write("keep_lte_ignored", "why", "choose_lte_in_settings");
    }

    /**
     * 엔진의 종료 처리 끝. 바로 끝내지 않고 확인 창(CONFIRM_MS)을 거친다(§5.12 보호 규칙 4):
     * 사용자가 LTE를 누른 직후 종료나 재시험이 겹쳐 삼성이 키를 늦게 쓰면, 그 선택으로 USER를 맞춘 뒤 끝낸다.
     * 맞춤이 막히면(통화 등) 끝내지 않고 다시 확인한다. 한 번만 처리한다.
     */
    @Override
    public void stopDone(boolean restored) {
        indicator.hide();
        if (stopping) return;
        stopping = true;
        if (timer != null) timer.cancel(false);
        log.write("stop_confirm", "waitMs", CONFIRM_MS);
        worker.schedule(() -> stopCheck(restored), CONFIRM_MS, TimeUnit.MILLISECONDS);
    }

    /** 종료 확인: 설정 키(사용자 선택)를 새로 읽고, 선택이 5G를 포함하지 않는데 USER에 NR이 있으면 뺀 뒤 끝낸다. */
    private void stopCheck(boolean restored) {
        try {
            int k = act.keyMode();
            long u = Phone.allowedUser(sub);
            if (k < 0 || u < 0) {
                // 일시적인 읽기 실패: 바로 끝내지 않고 다시 확인한다(끝나면 바로잡을 주체가 없다). 오래 실패하면 비정상 종료로 끝낸다
                if (++stopReadFails <= STOP_READ_RETRIES) {
                    if (!stopDeferLogged) log.write("stop_deferred", "why", "read_failed");
                    stopDeferLogged = true;
                    worker.schedule(() -> stopCheck(restored), STUCK_POLL_MS, TimeUnit.MILLISECONDS);
                    return;
                }
                finishStop(false); // 다음 시작의 키 우선 판단·nrctl restore가 맞춘다
                return;
            }
            if (k != keyMode) adoptKey(k, u, "stop_confirm");
            long want = UserMode.maskForKey(k, u, Phone.NR_BIT, AndroidIo::modeOfMask);
            if (want < 0 || hasNr(want) || !hasNr(u)) {
                finishStop(restored);
                return;
            }
            Actuator.Outcome o = act.write(want, k, "align_stop", traceWriter());
            if (o.user >= 0) knownUser = o.user;
            log.write("align_result", "why", "stop", "result", o.kind.name(), "user", o.user, "target", want);
            if (o.kind == Policy.Kind.OK) {
                saved.userChose(want, k);
                finishStop(true);
                return;
            }
            if (o.kind == Policy.Kind.BLOCKED || o.keyChanged) {
                // 통화·키 읽기 실패·그 사이 새 선택: 끝내지 않고 다시 확인한다(데몬이 끝나면 바로잡을 주체가 없다)
                if (!stopDeferLogged) log.write("stop_deferred", "why", "align_blocked");
                stopDeferLogged = true;
                worker.schedule(() -> stopCheck(restored), STUCK_POLL_MS, TimeUnit.MILLISECONDS);
                return;
            }
            finishStop(false);
        } catch (Throwable e) {
            log.write("error", "where", "stop_confirm", "msg", String.valueOf(e));
            finishStop(false);
        }
    }

    private void finishStop(boolean restored) {
        // 되돌리기·맞춤에 실패했으면 정상 종료로 저장하지 않는다(다음 시작의 키 우선 판단이 다시 맞춘다)
        saved.clean = restored;
        persistSaved();
        if (!restored) log.write("stop_unclean", "why", "restore_or_align_failed", "lastWritten", saved.lastWritten);
        onStopDone.run();
    }

    // ================================================================ 내부: 사용자 선택(설정 키)

    /**
     * 설정 키 변경을 사용자 선택으로 채택(컨트롤러 쪽 기록만 바꾼다, 엔진에는 알리지 않는다). u = 지금 USER(읽기 성공 값).
     * 반환 = 사용자 선택에 맞는 USER 마스크. USER를 읽지 못했으면 부르지 않는다(키 변경을 처리 완료로 기록하지 않기 위해).
     */
    private long adoptKey(int k, long u, String why) {
        long mask = UserMode.maskForKey(k, u, Phone.NR_BIT, AndroidIo::modeOfMask);
        log.write("user_mode_key", "why", why, "from", keyMode, "to", k, "user", u, "mask", mask,
                "sinceUserCbMs", lastUserCb >= 0 ? now() - lastUserCb : -1);
        if (mask < 0) mask = u; // 짝을 지을 수 없으면 지금 USER 값을 사용자 선택으로(예전 규칙)
        clearAlignFailure(); // 새 사용자 선택: 예전 맞춤 실패 기억은 이 선택과 무관하다
        keyMode = k;
        knownUser = u;
        original = mask;
        saved.userChose(mask, k); // 사용자 선택이 컨트롤러가 남긴 값을 대신한다
        persistSaved();
        return mask;
    }

    /**
     * 엔진 밖(사건 처리)에서: 설정 키·USER를 새로 읽어 (1) 키가 바뀌었으면 사용자 선택으로 채택해 엔진에 알리고
     * (2) 사용자 선택이 5G를 포함하지 않는데 USER에 NR이 있으면 뺀다(보호 규칙 3). 맞출 목표를 기억해 두지 않고 매번
     * 폰에서 새로 읽어 판단하므로, 막힌 맞춤이 뒤의 새 선택을 덮는 일이 없다. 키가 바뀌었으면 true.
     */
    private boolean syncUser(long t, String why) {
        int k = act.keyMode();
        long u = Phone.allowedUser(sub);
        if (k < 0) return false;
        if (u < 0) {
            // USER를 못 읽음: 키가 바뀌었어도 처리 완료로 기록하지 않는다. 5G를 켜는 쓰기는 Actuator의 키 확인(예전 키 기대)이 막는다
            if (k != keyMode) log.write("user_mode_key_deferred", "why", why, "to", k, "reason", "user_unreadable");
            return false;
        }
        boolean changed = false;
        if (k != keyMode) {
            long mask = adoptKey(k, u, why);
            policy.userSelected(t, hasNr(mask), hasNr(u));
            changed = true;
        }
        // 맞춤 실패 기억은 읽기에 성공한 상태로 언제나 갱신한다(확인 창 안이라도): 다른 상태를 한 번이라도 보면 지운다
        forgetAlignFailureUnless(k, u);
        // USER 알림 직후(확인 창 안)에는 키가 아직 예전 값일 수 있다(삼성은 USER 뒤에 키를 쓴다). 예: LTE → 5G 선택에서
        // USER는 5G인데 키는 아직 LTE → 여기서 맞추면 사용자 선택을 되돌린다. 그래서 창이 끝난 뒤(창 끝 syncUser)에만 맞춘다
        if (confirmUntil < 0 || t >= confirmUntil) alignToChoice(k, u, why);
        return changed;
    }

    /** 사용자 선택(키 k)이 5G를 포함하지 않는데 USER(u)에 NR이 있으면 뺀다. 통화 가드만 받는다. 막히면 다음 확인 때 다시. */
    private void alignToChoice(int k, long u, String why) {
        forgetAlignFailureUnless(k, u);
        long want = UserMode.maskForKey(k, u, Phone.NR_BIT, AndroidIo::modeOfMask);
        if (want < 0 || hasNr(want) || !hasNr(u)) return;
        if (k == alignFailKey && u == alignFailUser) return; // 같은 상태에서 실패한 명령을 되풀이하지 않는다
        Actuator.Outcome o = act.write(want, k, "align_" + why, traceWriter());
        if (o.user >= 0) knownUser = o.user;
        log.write("align_result", "why", why, "result", o.kind.name(), "user", o.user, "target", want);
        if (o.kind == Policy.Kind.OK) {
            clearAlignFailure();
            saved.userChose(want, k); // 맞춘 값은 사용자 선택이다(컨트롤러 흔적 아님)
            persistSaved();
        } else if (o.kind == Policy.Kind.FAILED) {
            // 명령을 실행했는데 실패: 자동 재시도하지 않는다(§5.6.3). 키나 USER가 바뀌면(사용자 조작·restore) 다시 판단한다
            alignFailKey = k;
            alignFailUser = u;
            log.write("align_failed_hold", "key", k, "user", u);
        }
        // BLOCKED(쓰지 않음)·키 다시 바뀜: 기억해 두지 않는다. 통화 끝·키 변경·확인 창 끝·30초 주기 확인에서 새로 판단한다
    }

    /** 실패 기억은 그 (키, USER) 상태가 이어지는 동안만 유효하다. 다른 상태를 보면 지운다. */
    private void forgetAlignFailureUnless(int k, long u) {
        if (alignFailKey >= 0 && (k != alignFailKey || u != alignFailUser)) clearAlignFailure();
    }

    private void clearAlignFailure() {
        alignFailKey = -1;
        alignFailUser = -1;
    }

    /** 엔진 안(write)에서 키 변경을 만났을 때: 채택 결과를 돌려주고, 맞춤은 엔진 처리가 끝난 뒤 새로 판단한다. */
    private Policy.Result adoptInsideEngine(int k, long u, String why) {
        if (u < 0) {
            log.write("user_mode_key_deferred", "why", why, "to", k, "reason", "user_unreadable");
            return Policy.Result.of(Policy.Kind.BLOCKED); // 사용자 선택을 확정 못 함: 쓰지 않고 나중에 다시
        }
        long mask = adoptKey(k, u, why);
        worker.execute(() -> {
            try {
                if (!stopping) syncUser(now(), "after_adopt");
            } catch (Throwable e) {
                log.write("error", "where", "align", "msg", String.valueOf(e));
            }
        });
        return Policy.Result.adopted(hasNr(mask), hasNr(u));
    }

    /** 보호 규칙 2: 키 재확인 창을 열고, 창 끝에 키를 다시 본 뒤 미뤄진 예약을 처리한다. */
    private void openConfirm(long t) {
        confirmUntil = Math.max(confirmUntil, t + CONFIRM_MS);
        worker.schedule(() -> {
            try {
                long n = now();
                if (stopping) return;
                syncUser(n, "confirm");
                if (confirmUntil >= 0 && n >= confirmUntil) confirmUntil = -1;
                policy.advance(n);
                reschedule();
            } catch (Throwable e) {
                log.write("error", "where", "confirm", "msg", String.valueOf(e));
            }
        }, CONFIRM_MS + 50, TimeUnit.MILLISECONDS);
    }

    private Actuator.BeforeWrite traceWriter() {
        return new Actuator.BeforeWrite() {
            @Override
            public boolean record(long mask, int mode) {
                saved.beginWrite(mask, mode); // 선기록: 시도 목표
                return persistSaved();
            }

            @Override
            public void applied(long user, int mode) {
                saved.applied(user, mode); // USER 적용 확인 즉시: 컨트롤러가 남긴 값
                if (!persistSaved()) log.write("warn", "what", "state_file_write_failed_after_apply");
            }
        };
    }

    // ================================================================ 내부

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    private static boolean hasNr(long mask) {
        return mask >= 0 && (mask & Phone.NR_BIT) != 0;
    }

    private boolean persistSaved() {
        return store.write(saved);
    }

    /**
     * USER 값만 바뀐 외부 변경(설정 키는 그대로, 예: 다른 도구의 명령). 예전 규칙대로 사용자 선택으로 채택한다.
     * 설정 화면으로 고른 경우는 키가 함께 바뀌므로 syncUser가 먼저 처리한다.
     */
    private boolean checkUser(long t) {
        long v = Phone.allowedUser(sub);
        if (v < 0 || v == knownUser) return false;
        adoptExternal(v, "observed");
        policy.userSelected(t, hasNr(v));
        return true;
    }

    private void adoptExternal(long v, String how) {
        log.write("external_user_change", "how", how, "from", knownUser, "to", v, "key", keyMode);
        knownUser = v;
        original = v;
        saved.userChose(v, AndroidIo.modeOfMask(v));
        persistSaved();
    }

    private void updateNetOk(long t) {
        boolean ok = dataReg == ServiceState.STATE_IN_SERVICE && dataConn == TelephonyManager.DATA_CONNECTED;
        if (netOkLast == null || netOkLast != ok) {
            netOkLast = ok;
            policy.netOk(t, ok);
        }
    }

    /** 처리하고도 남은(가드에 막힌) 지난 예약은 이 간격으로만 다시 본다(쉬지 않는 반복 방지). */
    static final long STUCK_POLL_MS = 5_000;

    /** 엔진의 다음 확인 시각에 맞춰 한 번 깨운다(깨어 있을 때만 흐르는 시계라 자는 폰을 깨우지 않는다). */
    private void reschedule() {
        if (timer != null) timer.cancel(false);
        if (stopping) return;
        long d = policy.nextDeadline();
        if (d < 0) return;
        long delay = d - now();
        if (delay <= 0) delay = STUCK_POLL_MS; // 방금 처리했는데도 지난 예약 = 막혀 있다
        timer = worker.schedule(() -> {
            try {
                policy.advance(now());
            } catch (Throwable e) {
                log.write("error", "where", "deadline", "msg", String.valueOf(e));
            }
            reschedule();
        }, delay, TimeUnit.MILLISECONDS);
    }
}
