package nrc;

import android.os.SystemClock;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.PhysicalChannelConfig;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 관찰기. 전화 상태 알림을 받아 **사실만** 기록하고(DESIGN §5.3·§5.4), 같은 사건을 Listener(제어 모드의 Controller)에 넘긴다.
 * 관찰 전용 실행에서는 Listener가 아무것도 하지 않는다. 이 클래스에는 네트워크 설정을 바꾸는 코드가 없다.
 * 기록은 판정 결과가 아니라 원값이라, PC 리포트가 기준값을 바꿔 가며 다시 계산할 수 있다.
 * 모든 메서드는 Nrd의 단일 작업 스레드에서만 불린다(생성자는 등록 전에 메인 스레드에서 한 번).
 */
class Observer extends TelephonyCallback implements
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.PhysicalChannelConfigListener,
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DataActivityListener,
        TelephonyCallback.DataConnectionStateListener,
        TelephonyCallback.CallStateListener,
        TelephonyCallback.DisplayInfoListener {

    /** 관찰 사건 수신자(제어 모드). t = 부팅 후 경과 ms. */
    interface Listener {
        void onScreen(long t, boolean on);

        void onCall(long t, int state);

        void onServiceState(long t, int dataReg, boolean roaming);

        void onOos(long t, long sinceDataMs, boolean screenOn);

        void onService(long t);

        void onNrOff(long t, long sinceDataMs, long dwellMs, boolean screenOn);

        void onDataActivity(long t, boolean active);

        void onDataConnection(long t, int state);

        void onAllowed(long t, int reason, long mask);

        void onTick(long t);
    }

    /** 관찰 전용 실행용: 아무것도 하지 않는다. */
    static final Listener NONE = new Listener() {
        public void onScreen(long t, boolean on) { }

        public void onCall(long t, int state) { }

        public void onServiceState(long t, int dataReg, boolean roaming) { }

        public void onOos(long t, long sinceDataMs, boolean screenOn) { }

        public void onService(long t) { }

        public void onNrOff(long t, long sinceDataMs, long dwellMs, boolean screenOn) { }

        public void onDataActivity(long t, boolean active) { }

        public void onDataConnection(long t, int state) { }

        public void onAllowed(long t, int reason, long mask) { }

        public void onTick(long t) { }
    };

    /** 데이터 활동이 이 시간 넘게 멈추면 사용 구간 하나가 끝난 것으로 본다. */
    static final long BURST_GAP_MS = 3_000;
    /** 신호 기록 최소 간격. NR 신호가 생기거나 사라지면 간격과 무관하게 기록한다. */
    static final long SIG_MIN_INTERVAL_MS = 10_000;
    /** 깨어 있는 동안의 생존 기록 간격(리포트의 관찰 범위 계산용). */
    static final long ALIVE_INTERVAL_MS = 10 * 60_000;

    private static final int NR_STATE_CONNECTED = 3; // NetworkRegistrationInfo.NR_STATE_CONNECTED

    private final Journal log;
    private final int subId;
    private final Listener out;
    private final Set<String> seen = new HashSet<>();
    private long registeredAt;
    private int events;
    private long lastAliveAt;

    // NR 연결(PCC 보조셀 ∪ nrState=CONNECTED)
    private int nrState = -1;
    private boolean pccNr;
    private boolean pccLogged;
    private boolean nrConnected;
    private long nrOnAt = -1;

    // 서비스 상태
    private int dataReg = -1;
    private int voiceReg = -1;
    private int dataRat = -1;
    private boolean roaming;
    private long oosAt = -1;

    // 데이터 활동(사용 구간). 조각은 JSONL `data`로 내보낸다
    private final UseSegments use;

    // 기타
    private int dataConn = -1;
    private int callState = -1;
    private Boolean screenOn;
    private long userMask = -2;
    private int thermal = -2;
    private String sig = "";
    private long lastSigAt = -1;
    private int lastLteBucket = Integer.MIN_VALUE;
    private boolean lastNrSig;

    Observer(Journal log, int subId, Listener out) {
        this.log = log;
        this.subId = subId;
        this.out = out;
        this.use = new UseSegments(BURST_GAP_MS, (from, ms) -> log.write("data", "from", from, "ms", ms));
        refreshMode("start");
        refreshScreen();
        refreshThermal();
    }

    /** 등록 직전 시각. 첫 알림까지 걸린 시간을 재는 기준(Phase 0 잔여 4·5번 확인용). */
    final void markRegistered() {
        registeredAt = now();
        lastAliveAt = registeredAt;
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    // ---------- 알림 ----------

    @Override
    public void onServiceStateChanged(ServiceState ss) {
        safely("ss", () -> {
            long t = now();
            int nr = Phone.hiddenInt(ss, "getNrState");
            int dReg = Phone.hiddenInt(ss, "getDataRegistrationState");
            int rat = Phone.hiddenInt(ss, "getRilDataRadioTechnology");
            int vReg = ss.getState();
            boolean roam = ss.getRoaming();
            refreshScreen();
            if (dReg != dataReg) {
                if (dataReg == ServiceState.STATE_IN_SERVICE) {
                    oosAt = t;
                    log.write("oos", "reg", dReg, "sinceDataMs", sinceData(t), "screen", screenOn, "sig", sig);
                    out.onOos(t, sinceData(t), Boolean.TRUE.equals(screenOn));
                } else if (dReg == ServiceState.STATE_IN_SERVICE && oosAt >= 0) {
                    log.write("service", "outMs", t - oosAt, "screen", screenOn);
                    oosAt = -1;
                    out.onService(t);
                }
            }
            if (nr != nrState || dReg != dataReg || vReg != voiceReg || rat != dataRat || roam != roaming) {
                log.write("ss", "nrState", nr, "dataReg", dReg, "voiceReg", vReg, "dataRat", rat, "roaming", roam);
            }
            nrState = nr;
            dataReg = dReg;
            voiceReg = vReg;
            dataRat = rat;
            roaming = roam;
            out.onServiceState(t, dReg, roam);
            updateNr(t);
        });
    }

    @Override
    public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> configs) {
        safely("pcc", () -> {
            boolean nr = false;
            StringBuilder cells = new StringBuilder();
            for (PhysicalChannelConfig c : configs) {
                boolean secondary = c.getConnectionStatus() == PhysicalChannelConfig.CONNECTION_SECONDARY_SERVING;
                if (c.getNetworkType() == TelephonyManager.NETWORK_TYPE_NR && secondary) nr = true;
                if (cells.length() > 0) cells.append(',');
                cells.append(c.getNetworkType()).append(secondary ? 's' : 'p')
                        .append(":b").append(c.getBand())
                        .append(':').append(c.getCellBandwidthDownlinkKhz() / 1000);
            }
            if (nr != pccNr || !pccLogged) log.write("pcc", "nr", nr, "n", configs.size(), "cells", cells.toString());
            pccNr = nr;
            pccLogged = true;
            refreshScreen();
            updateNr(now());
        });
    }

    @Override
    public void onSignalStrengthsChanged(SignalStrength s) {
        safely("sig", () -> {
            int lteRsrp = Integer.MAX_VALUE;
            int nrRsrp = Integer.MAX_VALUE;
            int nrSinr = Integer.MAX_VALUE;
            for (CellSignalStrength c : s.getCellSignalStrengths()) {
                if (c instanceof CellSignalStrengthLte) lteRsrp = ((CellSignalStrengthLte) c).getRsrp();
                if (c instanceof CellSignalStrengthNr) {
                    nrRsrp = ((CellSignalStrengthNr) c).getSsRsrp();
                    nrSinr = ((CellSignalStrengthNr) c).getSsSinr();
                }
            }
            sig = "lte=" + fmt(lteRsrp) + ",nr=" + fmt(nrRsrp) + "/" + fmt(nrSinr);
            long t = now();
            boolean nrSig = nrRsrp != Integer.MAX_VALUE;
            int bucket = lteRsrp == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.floorDiv(lteRsrp, 5);
            boolean due = lastSigAt < 0 || t - lastSigAt >= SIG_MIN_INTERVAL_MS;
            if (nrSig != lastNrSig || (due && bucket != lastLteBucket)) {
                log.write("sig", "lteRsrp", lteRsrp, "nrRsrp", nrRsrp, "nrSinr", nrSinr);
                lastSigAt = t;
                lastLteBucket = bucket;
                lastNrSig = nrSig;
            }
        });
    }

    @Override
    public void onDataActivity(int direction) {
        safely("data", () -> {
            long t = now();
            boolean active = direction == TelephonyManager.DATA_ACTIVITY_IN
                    || direction == TelephonyManager.DATA_ACTIVITY_OUT
                    || direction == TelephonyManager.DATA_ACTIVITY_INOUT;
            use.activity(t, System.currentTimeMillis(), active);
            out.onDataActivity(t, active);
        });
    }

    @Override
    public void onDataConnectionStateChanged(int state, int networkType) {
        safely("dconn", () -> {
            if (state != dataConn) log.write("dconn", "state", state, "net", networkType);
            dataConn = state;
            out.onDataConnection(now(), state);
        });
    }

    @Override
    public void onCallStateChanged(int state) {
        safely("call", () -> {
            if (state == callState) return;
            log.write("call", "state", state);
            callState = state;
            out.onCall(now(), state);
        });
    }

    @Override
    public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
        // 표시값은 판단에 쓰지 않는다(P6 🔒). 사용자에게 보이는 아이콘과 비교하려고 기록만 한다.
        safely("display", () -> log.write("display", "net", info.getNetworkType(),
                "override", info.getOverrideNetworkType()));
    }

    /** 허용 타입 변경 알림(숨은 인터페이스, AllowedObserver가 연결). 사유별 값을 그대로 남긴다(§5.6.6). */
    final void onAllowed(int reason, long mask) {
        // 알림을 받기 시작한 시각을 넘긴다. 아래 USER 조회가 늦어져도 자기 쓰기 알림이 "늦게 온 알림"으로 보이지 않게
        // (같은 값 재선택 판정, DESIGN §5.11 결정 18)
        long arrived = now();
        safely("allowed", () -> {
            log.write("allowed", "reason", reason, "mask", mask, "nr", (mask & Phone.NR_BIT) != 0);
            if (reason == Phone.REASON_USER) refreshMode("allowed");
            out.onAllowed(arrived, reason, mask);
        });
    }

    /**
     * 30초마다(깨어 있을 때만) 불린다. 알림이 없는 값(화면·사용자 모드·발열)을 확인하고,
     * 사용 구간을 지금까지의 조각으로 기록한다. 정상 주기라면 데몬이 갑자기 끝나도 잃는 사용 시간은 약 한 주기다
     * (작업이 늦어지면 그만큼 늘 수 있다).
     */
    final void tick() {
        long t = now();
        refreshScreen();
        refreshMode("tick");
        refreshThermal();
        use.flush(t);
        if (t - lastAliveAt >= ALIVE_INTERVAL_MS) {
            log.write("alive", "events", events);
            events = 0;
            lastAliveAt = t;
        }
        out.onTick(t);
    }

    // ---------- 파생 ----------

    /** PCC의 NR 보조셀 또는 nrState=CONNECTED면 NR 연결로 본다(DESIGN §5.4). 끊기는 순간의 데이터 활동 간격을 남긴다. */
    private void updateNr(long t) {
        boolean connected = pccNr || nrState == NR_STATE_CONNECTED;
        if (connected == nrConnected) return;
        if (connected) {
            nrOnAt = t;
            String by = pccNr && nrState == NR_STATE_CONNECTED ? "both" : (pccNr ? "pcc" : "ss");
            log.write("nr_on", "by", by, "sinceDataMs", sinceData(t), "screen", screenOn);
        } else {
            long dwell = nrOnAt >= 0 ? t - nrOnAt : -1;
            long since = sinceData(t);
            log.write("nr_off", "sinceDataMs", since, "dwellMs", dwell, "screen", screenOn, "sig", sig);
            nrOnAt = -1;
            nrConnected = false;
            out.onNrOff(t, since, dwell, Boolean.TRUE.equals(screenOn));
            return;
        }
        nrConnected = connected;
    }

    /** 마지막 데이터 활동 이후 경과(ms). 지금 활동 중이면 0, 기록이 없으면 -1. */
    private long sinceData(long t) {
        return use.sinceActive(t);
    }

    /** 종료 요청 처리: 열린 사용 구간을 닫고 종료 사실을 남긴다(pid로 어느 실행의 종료인지 표시). */
    final void shutdown(String by) {
        use.shutdown(now());
        log.write("stopped", "by", by, "pid", android.os.Process.myPid());
    }

    private void refreshMode(String from) {
        long m = Phone.allowedUser(subId);
        if (m != userMask) {
            log.write("mode", "mask", m, "nr", m >= 0 && (m & Phone.NR_BIT) != 0, "from", from);
            userMask = m;
        }
    }

    private void refreshScreen() {
        Boolean s = Phone.interactive();
        if (s == null || s.equals(screenOn)) return;
        log.write("screen", "on", s);
        screenOn = s;
        out.onScreen(now(), s);
    }

    private void refreshThermal() {
        int th = Phone.thermalStatus();
        if (th != thermal) log.write("thermal", "status", th);
        thermal = th;
    }

    private static String fmt(int v) {
        return v == Integer.MAX_VALUE ? "-" : String.valueOf(v);
    }

    /** 첫 수신을 한 번 기록하고, 처리 중 예외로 관찰이 멈추지 않게 기록만 하고 넘긴다. */
    private void safely(String kind, Runnable r) {
        events++;
        if (seen.add(kind)) log.write("first", "kind", kind, "afterMs", now() - registeredAt);
        try {
            r.run();
        } catch (Throwable e) {
            log.write("error", "where", kind, "msg", String.valueOf(e));
        }
    }
}
