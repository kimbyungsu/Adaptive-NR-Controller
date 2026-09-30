package nrc.controller;

import android.os.IBinder;
import android.os.PersistableBundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 처음 설정에서 shell 권한으로 실행되는 도구(앱이 무선 디버깅으로 `CLASSPATH=<이 앱 APK> app_process /system/bin
 * nrc.controller.SetupTool …`을 부른다). 앱 화면·서비스와 무관한 별도 프로세스다(PoC의 CcTool과 같은 방식, research §2.15).
 * - add <sub> <항목>: 통신사 설정의 인증서 목록(carrier_certificate_string_array)을 지금 값 그대로 읽어 끝에 항목 하나를
 *   덧붙인다(기존 줄은 그대로, 이미 있으면 아무것도 안 함). 영구(persistent=true)로 넘기면 임시 층에도 합쳐진다(이 기기 코드).
 *   이 키 하나만 넘기므로 다른 설정은 그대로다(override는 putAll로 합침).
 * - has <sub> <항목>: 목록에 있는지만 본다.
 * 출력: "BEFORE [..]", "AFTER [..]", 마지막 줄 "RESULT ok|already|absent|fail <이유>".
 */
public final class SetupTool {
    static final String KEY = "carrier_certificate_string_array";

    private SetupTool() {
    }

    public static void main(String[] a) {
        try {
            if (a.length != 3) {
                out("RESULT fail usage");
                return;
            }
            int sub = Integer.parseInt(a[1]);
            String entry = a[2];
            if (sub < 1 || !validEntry(entry)) {
                out("RESULT fail bad_args");
                return;
            }
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "carrier_config");
            Object svc = Class.forName("com.android.internal.telephony.ICarrierConfigLoader$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
            String[] cur = certs(svc, sub);
            out("BEFORE " + (cur == null ? "null" : Arrays.toString(cur)));
            boolean present = contains(cur, entry);
            if ("has".equals(a[0])) {
                out(present ? "RESULT already" : "RESULT absent");
                return;
            }
            if (!"add".equals(a[0])) {
                out("RESULT fail usage");
                return;
            }
            if (present) {
                out("RESULT already");
                return;
            }
            List<String> list = new ArrayList<>(cur == null ? new ArrayList<>() : Arrays.asList(cur));
            list.add(entry);
            PersistableBundle pb = new PersistableBundle();
            pb.putStringArray(KEY, list.toArray(new String[0]));
            override(svc, sub, pb);
            String[] after = null;
            for (int i = 0; i < 25; i++) { // 최대 5초 기다리며 다시 읽는다
                Thread.sleep(200);
                after = certs(svc, sub);
                if (contains(after, entry)) break;
            }
            out("AFTER " + (after == null ? "null" : Arrays.toString(after)));
            out(contains(after, entry) ? "RESULT ok" : "RESULT fail not_applied");
        } catch (Throwable t) {
            out("RESULT fail " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /** "sha256 64자리 소문자 16진수:패키지" 형식만 받는다(명령 줄에 다른 것이 섞이지 않게). */
    static boolean validEntry(String e) {
        return e != null && e.matches("[0-9a-f]{64}:[A-Za-z0-9_.]+");
    }

    static boolean contains(String[] list, String entry) {
        if (list == null) return false;
        for (String s : list) if (entry.equalsIgnoreCase(s)) return true;
        return false;
    }

    private static void override(Object svc, int sub, PersistableBundle pb) throws Exception {
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("overrideConfig") && m.getParameterTypes().length == 3) {
                m.invoke(svc, sub, pb, true);
                return;
            }
        }
        throw new IllegalStateException("overrideConfig(3) not found");
    }

    private static String[] certs(Object svc, int sub) throws Exception {
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("getConfigForSubIdWithFeature") && m.getParameterTypes().length == 3) {
                PersistableBundle c = (PersistableBundle) m.invoke(svc, sub, "com.android.shell", null);
                return c == null ? null : c.getStringArray(KEY);
            }
        }
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("getConfigForSubId") && m.getParameterTypes().length == 2) {
                PersistableBundle c = (PersistableBundle) m.invoke(svc, sub, "com.android.shell");
                return c == null ? null : c.getStringArray(KEY);
            }
        }
        throw new IllegalStateException("getConfig not found");
    }

    private static void out(String s) {
        System.out.println(s);
    }
}
