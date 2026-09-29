package nrc;

import android.content.Context;
import android.os.IBinder;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 전화·전원 서비스 **조회** 도우미. 설정을 바꾸는 호출은 여기에 두지 않는다(쓰기는 Actuator만).
 * 숨은 API는 리플렉션으로 부르며, 실패하면 null/-1을 돌려 관찰을 계속한다.
 */
final class Phone {
    static final int NR_BIT = 1 << 19;
    static final int REASON_USER = 0;

    private Phone() {
    }

    static IBinder service(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private static Object asInterface(String stubClass, IBinder binder) throws Exception {
        return Class.forName(stubClass).getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    /** 기본 데이터 SIM의 subId. app_process에선 SubscriptionManager 정적 조회가 -1이라 ISub에 직접 묻는다. */
    static int defaultDataSubId() throws Exception {
        Object isub = asInterface("com.android.internal.telephony.ISub$Stub", service("isub"));
        int sub = (Integer) isub.getClass().getMethod("getDefaultDataSubId").invoke(isub);
        if (sub > 0) return sub;
        Object ids = isub.getClass().getMethod("getSubId", int.class).invoke(isub, 0);
        if (ids instanceof int[] && ((int[]) ids).length > 0) return ((int[]) ids)[0];
        return sub;
    }

    /** 활성 SIM 개수(듀얼 SIM이면 v1은 관찰 전용). 조회 실패 시 -1. */
    static int activeSubCount(Context ctx) {
        try {
            Object isub = asInterface("com.android.internal.telephony.ISub$Stub", service("isub"));
            for (Method m : isub.getClass().getMethods()) {
                if (m.getName().equals("getActiveSubInfoCount")) {
                    Class<?>[] p = m.getParameterTypes();
                    Object[] a = new Object[p.length];
                    for (int i = 0; i < p.length; i++) {
                        a[i] = p[i] == String.class ? ShellContext.PACKAGE : (p[i] == boolean.class ? Boolean.FALSE : null);
                    }
                    return (Integer) m.invoke(isub, a);
                }
            }
        } catch (Throwable ignored) {
            // 아래에서 -1
        }
        return -1;
    }

    /** 컨텍스트가 shell 패키지인 TelephonyManager(숨은 생성자). */
    static TelephonyManager manager(Context ctx, int subId) throws Exception {
        Constructor<TelephonyManager> c = TelephonyManager.class.getDeclaredConstructor(Context.class, int.class);
        c.setAccessible(true);
        return c.newInstance(ctx, subId);
    }

    /** USER 사유 허용 타입. 실패 시 -1. */
    static long allowedUser(int subId) {
        try {
            Object t = asInterface("com.android.internal.telephony.ITelephony$Stub", service("phone"));
            return (Long) t.getClass().getMethod("getAllowedNetworkTypesForReason", int.class, int.class)
                    .invoke(t, subId, REASON_USER);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 화면 켜짐 여부. 실패 시 null. */
    static Boolean interactive() {
        try {
            Object pm = asInterface("android.os.IPowerManager$Stub", service("power"));
            return (Boolean) pm.getClass().getMethod("isInteractive").invoke(pm);
        } catch (Throwable e) {
            return null;
        }
    }

    /** subId의 SIM 슬롯 번호(`cmd phone ... -s <슬롯>`에 쓴다). 실패 시 -1. */
    static int slotIndex(int subId) {
        try {
            Object isub = asInterface("com.android.internal.telephony.ISub$Stub", service("isub"));
            return (Integer) isub.getClass().getMethod("getSlotIndex", int.class).invoke(isub, subId);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * 모든 SIM 기준 통화 가드(DESIGN §5.5.3 1·2번): Telecom 통화 중·긴급 통화·긴급 콜백 모드.
     * 문제 없으면 null, 조회 실패도 안전하게 "call_unknown"으로 막는다.
     */
    static String callGuard(int subId) {
        try {
            Object tc = asInterface("com.android.internal.telecom.ITelecomService$Stub", service("telecom"));
            if ((Boolean) tc.getClass().getMethod("isInCall", String.class, String.class)
                    .invoke(tc, ShellContext.PACKAGE, null)) return "call";
            if ((Boolean) tc.getClass().getMethod("isInEmergencyCall").invoke(tc)) return "emergency";
            Object ph = asInterface("com.android.internal.telephony.ITelephony$Stub", service("phone"));
            if ((Boolean) ph.getClass().getMethod("getEmergencyCallbackMode", int.class).invoke(ph, subId)) {
                return "emergency_callback";
            }
            return null;
        } catch (Throwable e) {
            return "call_unknown";
        }
    }

    private static boolean trafficReady;

    /** app_process에서는 TrafficStats가 초기화돼 있지 않다(실측) → 앱처럼 컨텍스트로 초기화한다. */
    static boolean initTraffic(Context ctx) {
        try {
            android.net.TrafficStats.class.getMethod("init", Context.class).invoke(null, ctx);
            trafficReady = android.net.TrafficStats.getMobileRxBytes() >= 0;
        } catch (Throwable e) {
            trafficReady = false;
        }
        return trafficReady;
    }

    /** 셀룰러 누적 송수신 바이트. 쓸 수 없으면 -1. */
    static long mobileBytes() {
        if (!trafficReady) return -1;
        try {
            long rx = android.net.TrafficStats.getMobileRxBytes();
            long tx = android.net.TrafficStats.getMobileTxBytes();
            return rx < 0 || tx < 0 ? -1 : rx + tx;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 발열 단계(PowerManager.THERMAL_STATUS_*: 0 없음 ~ 6 종료). 삼성의 발열 시 4G 전환과 구분하는 참고값. 실패 시 -1. */
    static int thermalStatus() {
        try {
            Object ts = asInterface("android.os.IThermalService$Stub", service("thermalservice"));
            return (Integer) ts.getClass().getMethod("getCurrentThermalStatus").invoke(ts);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** ServiceState의 숨은 getter(nrState 등). 실패 시 -1. */
    static int hiddenInt(ServiceState ss, String getter) {
        try {
            return (Integer) ss.getClass().getMethod(getter).invoke(ss);
        } catch (Throwable e) {
            return -1;
        }
    }
}
