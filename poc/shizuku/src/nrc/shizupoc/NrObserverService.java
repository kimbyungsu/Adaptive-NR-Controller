package nrc.shizupoc;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.telephony.PhysicalChannelConfig;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shizuku UserService(관측 프로세스) — Shizuku가 shell 신분(uid 2000) 별도 프로세스로 띄운다(§5.15 방향 A).
 * Step 2: 이 호스트 프로세스에서 TelephonyCallback(PCC·ServiceState·DisplayInfo)을 등록해 NR 상태 변화를 '지켜본다'.
 * 핵심:
 *   - 전화 서비스가 "호출 패키지=호출 uid 소유"를 요구하므로, TelephonyManager를 **숨은 생성자에 FakeShellContext
 *     (com.android.shell)를 직접 넘겨** 만든다(getSystemService는 wrapper를 안 거쳐 위장이 안 먹힘 — 데몬 Phone.manager와 동일).
 *   - startWatch는 앱이 binder로 부르므로 Binder.clearCallingIdentity로 호출자(앱) 신분을 비우고 호스트(shell)로 등록한다.
 * 관측 결과는 android.util.Log(NRSHIZUOBS)로 남긴다(앱 콜백 스트리밍은 이 PoC 범위 아님 — logcat 확인). 전화 설정은 쓰지 않는다.
 */
public final class NrObserverService extends INrObserver.Stub {

    private static final String T = "NRSHIZUOBS";

    private TelephonyManager tm;
    private TelephonyCallback cb;
    private ExecutorService exec;

    public NrObserverService() {
    }

    @SuppressWarnings("unused")
    public NrObserverService(Context context) {
    }

    @Override
    public void destroy() {
        android.util.Log.i(T, "destroy");
        try {
            if (tm != null && cb != null) tm.unregisterTelephonyCallback(cb);
        } catch (Throwable ignored) {
        }
        try {
            if (exec != null) exec.shutdownNow();
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }

    @Override
    public String snapshot() {
        String s = "hosted uid=" + android.os.Process.myUid() + " (2000=shell) pid=" + android.os.Process.myPid()
                + " model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT;
        android.util.Log.i(T, "snapshot: " + s);
        return s;
    }

    @Override
    public String startWatch() {
        if (Build.VERSION.SDK_INT < 31) {
            return "이 PoC의 관측(TelephonyCallback)은 Android 12(API 31)+만 — 이 기기 SDK=" + Build.VERSION.SDK_INT;
        }
        if (cb != null) {
            return "이미 관측 중(재등록 생략)"; // 반복 호출 시 이전 등록 누수 방지
        }
        // startWatch는 앱의 binder 거래로 들어오므로 호출자(앱) 신분을 비우고 호스트(shell) 신분으로 등록한다.
        final long token = Binder.clearCallingIdentity();
        try {
            Context ctx = FakeShellContext.create();
            int sub = SubscriptionManager.getDefaultDataSubscriptionId();
            // getSystemService는 ContextWrapper가 내부 Context에 위임해 위장이 안 먹힌다 → 숨은 생성자에 ctx를 직접 전달.
            TelephonyManager t = telephonyManager(ctx, sub);
            if (t == null) {
                String s = "TelephonyManager 생성 실패(호스트 컨텍스트)";
                android.util.Log.w(T, s);
                return s;
            }
            Watch w = new Watch();
            ExecutorService ex = Executors.newSingleThreadExecutor();
            t.registerTelephonyCallback(ex, w); // shell 신분·com.android.shell로 등록 — §5.15 관문 d
            this.tm = t;
            this.cb = w;
            this.exec = ex;
            String s = "watch 등록 성공: pkg=" + ctx.getPackageName() + " uid=" + android.os.Process.myUid()
                    + " sub=" + sub + " — 이제 PCC/서비스상태 변화를 로그(NRSHIZUOBS)로 남긴다";
            android.util.Log.i(T, s);
            return s;
        } catch (Throwable e) {
            Throwable c = e;
            while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) c = c.getCause();
            String s = "watch 등록 실패: " + c.getClass().getSimpleName() + ": " + c.getMessage();
            android.util.Log.w(T, s);
            return s;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** 컨텍스트가 com.android.shell인 TelephonyManager(숨은 생성자, 데몬 Phone.manager와 동일). */
    private static TelephonyManager telephonyManager(Context ctx, int subId) throws Exception {
        Constructor<TelephonyManager> c = TelephonyManager.class.getDeclaredConstructor(Context.class, int.class);
        c.setAccessible(true);
        return c.newInstance(ctx, subId);
    }

    /** 최소 관찰기: PCC로 NR '보조셀' 유무, 서비스 상태, 표시 네트워크 타입을 로그로 남긴다. */
    private static final class Watch extends TelephonyCallback implements
            TelephonyCallback.PhysicalChannelConfigListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.DisplayInfoListener {

        @Override
        public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> configs) {
            int n = configs == null ? 0 : configs.size();
            boolean nrSecondary = false;
            if (configs != null) {
                for (PhysicalChannelConfig c : configs) {
                    // NR 연결 = NR 타입이면서 보조셀(CONNECTION_SECONDARY_SERVING) — 데몬 Observer와 같은 판정.
                    if (c.getNetworkType() == TelephonyManager.NETWORK_TYPE_NR
                            && c.getConnectionStatus() == PhysicalChannelConfig.CONNECTION_SECONDARY_SERVING) {
                        nrSecondary = true;
                        break;
                    }
                }
            }
            android.util.Log.i(T, "pcc n=" + n + " nrSecondary=" + nrSecondary);
        }

        @Override
        public void onServiceStateChanged(ServiceState ss) {
            android.util.Log.i(T, "ss state=" + (ss == null ? -1 : ss.getState())
                    + " roaming=" + (ss != null && ss.getRoaming()));
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo di) {
            android.util.Log.i(T, "display override=" + (di == null ? -1 : di.getOverrideNetworkType()));
        }
    }
}
