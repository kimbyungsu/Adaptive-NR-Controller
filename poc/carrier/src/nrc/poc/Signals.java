package nrc.poc;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.PhysicalChannelConfig;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * 관문 ⑤: 앱 프로세스(통신사 권한만, 셸 권한 없음)가 컨트롤러의 판단 재료를 받을 수 있는지 본다.
 * 데몬 Observer가 쓰는 알림을 하나씩 **따로** 등록한다(하나가 거절돼도 나머지는 등록되게).
 * 등록 성공/거절과 종류별 첫 사건을 기록하고, 이후는 개수만 센다.
 */
final class Signals {
    private static final Map<String, Integer> counts = new TreeMap<>();
    private static Context app;

    private Signals() {
    }

    static synchronized String summary() {
        return counts.toString();
    }

    static void start(Context ctx) {
        app = ctx.getApplicationContext();
        Executor ex = ctx.getMainExecutor();
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        TelephonyManager tm = ctx.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);
        reg(tm, ex, "ss", new Ss());
        reg(tm, ex, "pcc", new Pcc());
        reg(tm, ex, "sig", new Sig());
        reg(tm, ex, "act", new Act());
        reg(tm, ex, "dconn", new DConn());
        reg(tm, ex, "call", new Call());
        reg(tm, ex, "display", new Display());
        reg(tm, ex, "allowed", new Allowed());
        key(sub);
    }

    private static void reg(TelephonyManager tm, Executor ex, String name, TelephonyCallback cb) {
        try {
            tm.registerTelephonyCallback(ex, cb);
            Probe.note(app, "sig_reg " + name + " ok");
        } catch (Throwable e) {
            Probe.note(app, "sig_reg " + name + " failed=" + e);
        }
    }

    /** 설정 키(삼성 네트워크 모드 저장값) 읽기와 변경 감시. */
    private static void key(int sub) {
        String name = "preferred_network_mode" + sub;
        try {
            Probe.note(app, "key_read " + name + "=" + Settings.Global.getString(app.getContentResolver(), name));
        } catch (Throwable e) {
            Probe.note(app, "key_read " + name + " failed=" + e);
        }
        try {
            Uri uri = Settings.Global.getUriFor(name);
            app.getContentResolver().registerContentObserver(uri, false, new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    String v;
                    try {
                        v = Settings.Global.getString(app.getContentResolver(), name);
                    } catch (Throwable e) {
                        v = "err:" + e.getClass().getSimpleName();
                    }
                    event("key", name + "=" + v, true);
                }
            });
            Probe.note(app, "key_watch ok");
        } catch (Throwable e) {
            Probe.note(app, "key_watch failed=" + e);
        }
    }

    /** 종류별 첫 사건은 내용까지 기록하고, always면 매번 기록한다. */
    private static void event(String name, String detail, boolean always) {
        int n;
        synchronized (Signals.class) {
            n = counts.containsKey(name) ? counts.get(name) + 1 : 1;
            counts.put(name, n);
        }
        if (n == 1 || always) Probe.note(app, "sig " + name + "#" + n + " " + detail);
    }

    static final class Ss extends TelephonyCallback implements TelephonyCallback.ServiceStateListener {
        @Override
        public void onServiceStateChanged(ServiceState ss) {
            String nr;
            try {
                nr = String.valueOf(ss.getClass().getMethod("getNrState").invoke(ss));
            } catch (Throwable e) {
                nr = "err:" + e.getClass().getSimpleName();
            }
            event("ss", "state=" + ss.getState() + " nrState=" + nr, false);
        }
    }

    static final class Pcc extends TelephonyCallback implements TelephonyCallback.PhysicalChannelConfigListener {
        @Override
        public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> list) {
            int nr = 0;
            for (PhysicalChannelConfig c : list) {
                if (c.getNetworkType() == TelephonyManager.NETWORK_TYPE_NR) nr++;
            }
            event("pcc", "n=" + list.size() + " nr=" + nr, false);
        }
    }

    static final class Sig extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener {
        @Override
        public void onSignalStrengthsChanged(SignalStrength s) {
            event("sig", "cells=" + s.getCellSignalStrengths().size(), false);
        }
    }

    static final class Act extends TelephonyCallback implements TelephonyCallback.DataActivityListener {
        @Override
        public void onDataActivity(int direction) {
            event("act", "dir=" + direction, false);
        }
    }

    static final class DConn extends TelephonyCallback implements TelephonyCallback.DataConnectionStateListener {
        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            event("dconn", "state=" + state + " net=" + networkType, false);
        }
    }

    static final class Call extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            event("call", "state=" + state, false);
        }
    }

    static final class Display extends TelephonyCallback implements TelephonyCallback.DisplayInfoListener {
        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            event("display", "net=" + info.getNetworkType() + " override=" + info.getOverrideNetworkType(), false);
        }
    }

    /** 숨은 API(@SystemApi). 선언은 daemon/stubs에서 컴파일 때만 가져온다. */
    static final class Allowed extends TelephonyCallback
            implements android.telephony.TelephonyCallback$AllowedNetworkTypesListener {
        @Override
        public void onAllowedNetworkTypesChanged(int reason, long allowedNetworkType) {
            event("allowed", "reason=" + reason + " mask=" + allowedNetworkType, true);
        }
    }
}
