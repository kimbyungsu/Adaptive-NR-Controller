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
 *   이 키 하나만 넘기지만 이 키의 배열은 통째로 바뀐다 → 폰이 통신사 설정을 다 읽은 뒤(carrier_config_applied_bool)에만 쓴다.
 *   다 읽기 전의 값은 기본값뿐이라, 그대로 쓰면 통신사 줄이 빠진 목록이 영구 저장돼 원래 줄을 가린다(외부 검증 지적).
 *   쓴 뒤에는 원래 줄이 모두 남았고 우리 줄이 들어갔는지 다시 읽어 확인한다.
 * - has <sub> <항목>: 목록에 있는지만 본다(같은 준비 조건).
 * 출력: "APPLIED true|false", "BEFORE [..]", "AFTER [..]", 마지막 줄 "RESULT ok|already|absent|fail <이유>".
 */
public final class SetupTool {
    static final String KEY = "carrier_certificate_string_array";
    static final String APPLIED = "carrier_config_applied_bool";

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
            PersistableBundle cfg = config(svc, sub);
            boolean applied = cfg != null && cfg.getBoolean(APPLIED, false);
            String[] cur = cfg == null ? null : cfg.getStringArray(KEY);
            out("APPLIED " + applied);
            out("BEFORE " + (cur == null ? "null" : Arrays.toString(cur)));
            if (!applied) {
                out("RESULT fail not_ready"); // 폰이 통신사 설정을 아직 다 읽지 않았다: 쓰지 않는다
                return;
            }
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
            boolean ok = false;
            for (int i = 0; i < 25 && !ok; i++) { // 최대 5초 기다리며 다시 읽는다
                Thread.sleep(200);
                PersistableBundle now = config(svc, sub);
                after = now == null ? null : now.getStringArray(KEY);
                ok = contains(after, entry) && containsAll(after, cur);
            }
            out("AFTER " + (after == null ? "null" : Arrays.toString(after)));
            out(ok ? "RESULT ok" : "RESULT fail not_applied");
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

    /** list에 need의 줄이 모두 있는지(need가 없으면 참). */
    static boolean containsAll(String[] list, String[] need) {
        if (need == null) return true;
        for (String s : need) if (!contains(list, s)) return false;
        return true;
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

    private static PersistableBundle config(Object svc, int sub) throws Exception {
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("getConfigForSubIdWithFeature") && m.getParameterTypes().length == 3) {
                return (PersistableBundle) m.invoke(svc, sub, "com.android.shell", null);
            }
        }
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("getConfigForSubId") && m.getParameterTypes().length == 2) {
                return (PersistableBundle) m.invoke(svc, sub, "com.android.shell");
            }
        }
        throw new IllegalStateException("getConfig not found");
    }

    private static void out(String s) {
        System.out.println(s);
    }
}
