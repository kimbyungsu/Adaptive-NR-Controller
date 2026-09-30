package nrc.controller;

import android.os.SystemClock;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.NetworkRegistrationInfo;
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
 * 전화 상태 관찰기(daemon Observer를 앱용으로 옮김, DESIGN §5.3·§5.4·§5.13). 사실만 기록하고 같은 사건을 Listener(Engine)에 넘긴다.
 * 앱 프로세스에서 쓸 수 있는 공개 API만 쓴다(research §2.15 ⑤):
 * - NR 연결 = 기지국 묶음(PCC)의 NR 보조 칸. 숨은 nrState는 앱에서 막혀 쓰지 않는다(과거 기록 450번 모두 PCC가 먼저이거나 17ms 안에 따라옴).
 * - 데이터 등록 = 공개 NetworkRegistrationInfo(PS 도메인·셀룰러)의 isRegistered().
 * 콜백은 엔진의 단일 작업 스레드에서 불린다(등록할 때 그 실행기를 넘긴다).
 */
final class Watcher extends TelephonyCallback implements
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.PhysicalChannelConfigListener,
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DataActivityListener,
        TelephonyCallback.DataConnectionStateListener,
        TelephonyCallback.CallStateListener,
        TelephonyCallback.DisplayInfoListener {

    interface Listener {
        void onCall(long t, int state);

        void onServiceState(long t, boolean dataInService, boolean roaming);

        void onOos(long t, long sinceDataMs);

        void onService(long t);

        void onNrOff(long t, long sinceDataMs, long dwellMs);

        /** 5G 칸이 붙었다(관측 화면 기록용, 판단에는 쓰지 않는다). */
        void onNrOn(long t, long sinceDataMs);

        void onDataActivity(long t, boolean active);

        void onDataConnection(long t, int state);
    }

    static final long BURST_GAP_MS = 3_000;
    static final long SIG_MIN_INTERVAL_MS = 10_000;

    private final Journal log;
    private final Listener out;
    private final Set<String> seen = new HashSet<>();
    private final UseSegments use;
    private final long registeredAt = now();

    private boolean pccNr;
    private boolean pccLogged;
    private boolean nrConnected;
    private long nrOnAt = -1;
    private Boolean dataIn;
    private boolean roaming;
    private int dataRat = -1;
    private long oosAt = -1;
    private int dataConn = -1;
    private int callState = -1;
    private String sig = "";
    private long lastSigAt = -1;
    private int lastLteBucket = Integer.MIN_VALUE;
    private boolean lastNrSig;
    private int display = -1;
    private int lteRsrpNow = Integer.MAX_VALUE;
    private int nrRsrpNow = Integer.MAX_VALUE;
    private int nrSinrNow = Integer.MAX_VALUE;

    Watcher(Journal log, Listener out) {
        this.log = log;
        this.out = out;
        this.use = new UseSegments(BURST_GAP_MS, (from, ms) -> log.write("data", "from", from, "ms", ms));
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public void onServiceStateChanged(ServiceState ss) {
        safely("ss", () -> {
            long t = now();
            NetworkRegistrationInfo ps = null;
            for (NetworkRegistrationInfo n : ss.getNetworkRegistrationInfoList()) {
                if (n.getDomain() == NetworkRegistrationInfo.DOMAIN_PS
                        && n.getTransportType() == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                    ps = n;
                    break;
                }
            }
            boolean in = ps != null && ps.isRegistered();
            int rat = ps == null ? -1 : ps.getAccessNetworkTechnology();
            boolean roam = ss.getRoaming();
            if (dataIn != null && dataIn != in) {
                if (!in) {
                    oosAt = t;
                    log.write("oos", "sinceDataMs", use.sinceActive(t), "sig", sig);
                    out.onOos(t, use.sinceActive(t));
                } else if (oosAt >= 0) {
                    log.write("service", "outMs", t - oosAt);
                    oosAt = -1;
                    out.onService(t);
                }
            }
            if (dataIn == null || dataIn != in || rat != dataRat || roam != roaming) {
                log.write("ss", "dataIn", in, "dataRat", rat, "voice", ss.getState(), "roaming", roam);
            }
            dataIn = in;
            dataRat = rat;
            roaming = roam;
            out.onServiceState(t, in, roam);
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
            lteRsrpNow = lteRsrp;
            nrRsrpNow = nrRsrp;
            nrSinrNow = nrSinr;
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
        // 표시값은 판단에 쓰지 않는다(P6). 사용자에게 보이는 아이콘과 비교하려고 기록만 한다.
        safely("display", () -> {
            display = info.getOverrideNetworkType();
            log.write("display", "net", info.getNetworkType(), "override", display);
        });
    }

    // ---------- 관측 화면용 지금 값(작업 스레드에서 읽는다) ----------

    /** 실제 연결(기지국 묶음 기준)에 5G 칸이 붙어 있는지. */
    boolean nrConnected() {
        return nrConnected;
    }

    /** 상단바 표시 종류(TelephonyDisplayInfo override: 0 없음·1 LTE+·3 5G 등, 모름 -1). */
    int display() {
        return display;
    }

    int lteRsrp() {
        return lteRsrpNow;
    }

    int nrRsrp() {
        return nrRsrpNow;
    }

    int nrSinr() {
        return nrSinrNow;
    }

    /** 30초 주기: 사용 구간을 지금까지의 조각으로 기록한다. */
    void flush() {
        use.flush(now());
    }

    void shutdown(String by) {
        use.shutdown(now());
        log.write("stopped", "by", by);
    }

    private void updateNr(long t) {
        if (pccNr == nrConnected) return;
        if (pccNr) {
            nrOnAt = t;
            nrConnected = true;
            log.write("nr_on", "by", "pcc", "sinceDataMs", use.sinceActive(t));
            out.onNrOn(t, use.sinceActive(t));
            return;
        }
        long dwell = nrOnAt >= 0 ? t - nrOnAt : -1;
        long since = use.sinceActive(t);
        log.write("nr_off", "sinceDataMs", since, "dwellMs", dwell, "sig", sig);
        nrOnAt = -1;
        nrConnected = false;
        out.onNrOff(t, since, dwell);
    }

    private static String fmt(int v) {
        return v == Integer.MAX_VALUE ? "-" : String.valueOf(v);
    }

    private void safely(String kind, Runnable r) {
        if (seen.add(kind)) log.write("first", "kind", kind, "afterMs", now() - registeredAt);
        try {
            r.run();
        } catch (Throwable e) {
            log.write("error", "where", kind, "msg", String.valueOf(e));
        }
    }
}
