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
            boolean threw = false;
            Throwable regEx = null;
            try {
                t.registerTelephonyCallback(ex, w); // shell 신분·com.android.shell로 등록 — §5.15 관문 d
            } catch (Throwable reg) {
                // 호스트(앱 Application 설치)에선 등록 직후 noted-op 보고 경로가 shell/위장 신분에서 프레임워크 NPE를 던질 수 있다.
                // 다만 '예외 스택이 AppOps다'만으로 성공을 단정하지 않는다(실패 응답도 AppOps 헤더를 달 수 있어 가릴 수 있음).
                threw = true;
                regEx = reg;
            }
            // 독립 성공 근거: 실제 콜백(PCC/서비스상태/표시)이 오는지 잠깐 기다려 확인한다. 추론이 아니라 수신으로 확정.
            boolean gotEvent = w.awaitFirst(2500);
            if (gotEvent) {
                this.tm = t;
                this.cb = w;
                this.exec = ex;
                String s = "watch 등록 성공(이벤트 수신 확인): pkg=" + ctx.getPackageName() + " uid=" + android.os.Process.myUid()
                        + " sub=" + sub + (threw ? " (등록 호출에서 예외가 있었으나 이벤트 수신으로 등록 확정)" : "");
                android.util.Log.i(T, s);
                return s;
            }
            // 이벤트 미수신 → 성공으로 보지 않는다. 등록이 부분적으로 됐을 수도 있으니 해제는 공통으로 먼저 시도.
            ex.shutdownNow();
            try {
                t.unregisterTelephonyCallback(w);
            } catch (Throwable ignore) {
            }
            if (threw && !isAppOpsNotingGlitch(regEx)) {
                Throwable c = regEx;
                while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) c = c.getCause();
                String s = "watch 등록 실패: " + c.getClass().getSimpleName() + ": " + c.getMessage();
                android.util.Log.w(T, "watch 등록 실패(이벤트도 없음)", regEx);
                return s;
            }
            String s = "watch 등록 미확인: 2.5초 안에 이벤트가 안 왔어요 — 등록 실패 가능. 다시 시도해 주세요"
                    + (threw ? " (등록 호출 예외 있었음)" : "");
            android.util.Log.w(T, s);
            return s;
        } catch (Throwable e) {
            Throwable c = e;
            while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) c = c.getCause();
            // 원인 줄을 찾기 위해 전체 스택을 남긴다(관측은 등록됐는데 등록 '뒤' 어딘가에서 던지는 경우 추적).
            android.util.Log.w(T, "watch 등록 실패 상세", c);
            StackTraceElement[] st = c.getStackTrace();
            String where = (st != null && st.length > 0) ? (" @ " + st[0]) : "";
            String s = "watch 등록 실패: " + c.getClass().getSimpleName() + ": " + c.getMessage() + where;
            android.util.Log.w(T, s);
            return s;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * 예외(및 원인 사슬)의 스택에 AppOps noted-op 보고 흔적이 있으면 true — '그 종류의 예외'인지만 분류한다.
     * 등록 성공을 증명하지는 않는다(실패 응답에도 AppOps 헤더가 붙을 수 있음). 성공은 awaitFirst(실수신)로만 확정한다.
     * 용도: 미수신일 때 '진짜 실패(비-AppOps 예외)'와 'AppOps 보고 잡음'을 구분해 보고 문구를 고르는 데만 쓴다.
     */
    private static boolean isAppOpsNotingGlitch(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            StackTraceElement[] st = c.getStackTrace();
            if (st == null) continue;
            for (StackTraceElement e : st) {
                String cls = e.getClassName();
                String m = e.getMethodName();
                if (cls.contains("AppOpsManager")
                        || "readAndLogNotedAppops".equals(m)
                        || cls.contains("SyncNotedAppOp")) {
                    return true;
                }
            }
        }
        return false;
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

        // 첫 콜백 수신 신호(등록이 실제로 됐는지 독립 확인용).
        private final java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);

        boolean awaitFirst(long ms) {
            try {
                return first.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> configs) {
            first.countDown();
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
            first.countDown();
            android.util.Log.i(T, "ss state=" + (ss == null ? -1 : ss.getState())
                    + " roaming=" + (ss != null && ss.getRoaming()));
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo di) {
            first.countDown();
            android.util.Log.i(T, "display override=" + (di == null ? -1 : di.getOverrideNetworkType()));
        }
    }
}
