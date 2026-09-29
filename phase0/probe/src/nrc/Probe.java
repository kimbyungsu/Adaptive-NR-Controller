package nrc;

import android.os.IBinder;
import android.os.SystemClock;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Phase 0 점검 도구 (읽기 전용). adb shell에서 app_process로 실행한다.
 *
 *   info       : 권한·hidden API 접근·주요 ITelephony 메서드 시그니처·현재 상태 출력
 *   state      : 현재 상태 한 줄 출력 (nrState 원값, 데이터 RAT, 사유별 허용 타입)
 *   heartbeat N: N초마다 상태를 alive.log에 추가 (케이블 분리·잠금·도즈 후 생존 확인용)
 *
 * 네트워크 설정을 바꾸는 호출은 하지 않는다.
 */
public final class Probe {
    private static final String LOG = "/data/local/tmp/nrc/alive.log";
    private static final String PID_FILE = "/data/local/tmp/nrc/heartbeat.pid";
    private static final int MIN_INTERVAL_SEC = 10;
    private static final String SHELL_PKG = "com.android.shell";
    private static final int NR_BIT = 1 << 19;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "info";
        switch (mode) {
            case "info":
                info();
                break;
            case "state":
                System.out.println(state());
                break;
            case "heartbeat": {
                int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 60;
                if (seconds < MIN_INTERVAL_SEC) {
                    // 0 같은 값이면 쉬지 않고 조회·기록을 반복해 폰에 부담을 준다
                    System.err.println("heartbeat interval must be >= " + MIN_INTERVAL_SEC + "s");
                    System.exit(2);
                }
                heartbeat(seconds);
                break;
            }
            default:
                System.err.println("usage: info | state | heartbeat [seconds]");
                System.exit(2);
        }
    }

    private static Object telephony() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "phone");
        Class<?> stub = Class.forName("com.android.internal.telephony.ITelephony$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    /**
     * 레퍼런스 기기의 app_process 실행에서 SubscriptionManager 정적 조회가 -1을 돌려줬다(원인 미확인)
     * → SIM 관리 서비스(ISub)에 직접 묻는다: 기본 데이터 sub → 없으면 슬롯 0의 sub.
     */
    private static int defaultDataSubId() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "isub");
        Object isub = Class.forName("com.android.internal.telephony.ISub$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        int sub = (Integer) isub.getClass().getMethod("getDefaultDataSubId").invoke(isub);
        if (sub > 0) return sub;
        Object ids = isub.getClass().getMethod("getSubId", int.class).invoke(isub, 0);
        if (ids instanceof int[] && ((int[]) ids).length > 0) return ((int[]) ids)[0];
        return sub;
    }

    private static void info() throws Exception {
        System.out.println("sdk=" + android.os.Build.VERSION.SDK_INT
                + " uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
        Object t = telephony();
        System.out.println("ITelephony=" + (t != null));
        for (Method m : t.getClass().getMethods()) {
            String n = m.getName();
            if (n.equals("getServiceStateForSubscriber") || n.equals("getAllowedNetworkTypesForReason")
                    || n.equals("setAllowedNetworkTypesForReason") || n.equals("isRadioInterfaceCapabilitySupported")
                    || n.equals("setNrDualConnectivityState")) {
                System.out.println("  " + n + Arrays.toString(m.getParameterTypes()));
            }
        }
        System.out.println(state());
    }

    /** 현재 상태 한 줄. 실패한 항목은 err=... 로 남기고 계속한다. */
    static String state() {
        StringBuilder sb = new StringBuilder();
        try {
            Object t = telephony();
            int sub = defaultDataSubId();
            sb.append("sub=").append(sub);
            Object ss = serviceState(t, sub);
            if (ss == null) {
                sb.append(" serviceState=null");
            } else {
                sb.append(" nrState=").append(call(ss, "getNrState"));
                sb.append(" nrFreq=").append(call(ss, "getNrFrequencyRange"));
                sb.append(" dataRat=").append(call(ss, "getRilDataRadioTechnology"));
                sb.append(" dataReg=").append(call(ss, "getDataRegistrationState"));
            }
            for (int reason = 0; reason <= 3; reason++) {
                sb.append(" allowed[").append(reason).append("]=").append(allowed(t, sub, reason));
            }
        } catch (Throwable e) {
            sb.append(" err=").append(describe(e));
        }
        return sb.toString();
    }

    /** 리플렉션 예외는 원인을 꺼내서 보여준다. */
    private static String describe(Throwable e) {
        Throwable c = e;
        while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) {
            c = c.getCause();
        }
        StringBuilder sb = new StringBuilder(c.getClass().getSimpleName()).append(':').append(c.getMessage());
        StackTraceElement[] st = c.getStackTrace();
        for (int i = 0; i < Math.min(3, st.length); i++) {
            sb.append(" @").append(st[i].getClassName()).append('.').append(st[i].getMethodName())
                    .append(':').append(st[i].getLineNumber());
        }
        return sb.toString();
    }

    /**
     * 위치 정보 포기(renounce=true)로 조회한다. 레퍼런스 기기에서 false/false 호출은 원격 NPE로 실패했고
     * (원인 미확인) true/true 호출은 성공했다. nrState는 위치 정보를 지운 사본에도 남는다
     * (AOSP ServiceState.createLocationInfoSanitizedCopy).
     */
    private static Object serviceState(Object t, int sub) throws Exception {
        for (Method m : t.getClass().getMethods()) {
            if (!m.getName().equals("getServiceStateForSubscriber")) continue;
            return m.invoke(t, serviceStateArgs(m.getParameterTypes(), sub, null));
        }
        return null;
    }

    private static Object[] serviceStateArgs(Class<?>[] p, int sub, String featureId) {
        Object[] a = new Object[p.length];
        a[0] = sub;
        boolean pkgSet = false;
        for (int i = 1; i < p.length; i++) {
            if (p[i] == boolean.class) {
                a[i] = true;
            } else if (p[i] == String.class) {
                a[i] = pkgSet ? featureId : SHELL_PKG;
                pkgSet = true;
            }
        }
        return a;
    }

    private static String allowed(Object t, int sub, int reason) {
        try {
            Method m = t.getClass().getMethod("getAllowedNetworkTypesForReason", int.class, int.class);
            long v = (Long) m.invoke(t, sub, reason);
            return v + ((v & NR_BIT) != 0 ? "(NR)" : "(noNR)");
        } catch (Throwable e) {
            return "err:" + describe(e);
        }
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable e) {
            return "err:" + describe(e);
        }
    }

    private static void heartbeat(int seconds) throws InterruptedException {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        int pid = android.os.Process.myPid();
        writePidFile(pid);
        append("START pid=" + pid + " interval=" + seconds + "s");
        while (true) {
            // elapsedRealtime은 잠자는 동안에도 흐르고 uptimeMillis는 멈춘다 → 둘의 차이로 깊은 잠 여부를 본다
            append(fmt.format(new Date()) + " elapsed=" + SystemClock.elapsedRealtime()
                    + " uptime=" + SystemClock.uptimeMillis() + " " + state());
            Thread.sleep(seconds * 1000L);
        }
    }

    /**
     * 현재 실행의 pid를 식별용으로 남긴다(매 기동마다 덮어씀). README의 중지 명령은
     * 실행 명령 문자열 일치 방식이라 이 파일에 의존하지 않는다.
     */
    private static void writePidFile(int pid) {
        try (PrintWriter w = new PrintWriter(new FileWriter(PID_FILE, false))) {
            w.println(pid);
        } catch (IOException ignored) {
            // 식별용 파일이므로 쓰기 실패는 기록 동작에 영향을 주지 않는다
        }
    }

    private static void append(String line) {
        try (PrintWriter w = new PrintWriter(new FileWriter(LOG, true))) {
            w.println(line);
        } catch (IOException ignored) {
            // 로그를 못 써도 다음 주기에 다시 시도한다
        }
    }

    private Probe() {
    }
}
