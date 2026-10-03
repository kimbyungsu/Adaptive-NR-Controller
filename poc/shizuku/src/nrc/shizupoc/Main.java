package nrc.shizupoc;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.telephony.SubscriptionManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Shizuku 경로 PoC — 읽기 전용(제품 아님). 2026-10-03.
 * 목적: 통신사 권한에 기대지 않고, 사용자가 설치·동의한 Shizuku를 거쳐 앱이 전화 설정(허용 망 사유별 값)을
 *       '읽을' 수 있는지 레퍼런스 폰에서 확인한다. 즉 §5.15 "관측 문제"의 통로가 Shizuku로 열리는지의 첫 증거.
 * 안전: 이 PoC는 아무것도 쓰지 않는다(setAllowedNetworkTypesForReason 등 호출 없음). 네트워크 설정을 바꾸지 않는다.
 *       제어(POWER로 잠깐 막았다 되돌리기)는 되돌리기 기록의 전원차단·손상·SIM교체 안전성을 단단히 한 뒤 별도 증명(미구현).
 * 호출: SystemServiceHelper.getSystemService("phone")을 ShizukuBinderWrapper로 감싼 뒤 ITelephony로 reflection 읽기.
 */
public final class Main extends Activity {

    static final int USER = 0, POWER = 1, CARRIER = 2, ENABLE_2G = 3;
    static final long NR_BIT = 1L << 19; // TelephonyManager NR 비트(research §2.14)
    static final int REQ = 1001;

    private TextView tv;
    private final StringBuilder buf = new StringBuilder();
    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final Shizuku.OnRequestPermissionResultListener permL =
            (requestCode, grantResult) -> runOnUiThread(this::refresh);
    private final Shizuku.OnBinderReceivedListener recvL = () -> runOnUiThread(this::refresh);
    private final Shizuku.OnBinderDeadListener deadL = () -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        allowHiddenApis(); // Android 숨은 API 차단 해제(앱 프로세스가 ITelephony 숨은 메서드를 찾을 수 있게)
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad, pad, pad);

        Button read = new Button(this);
        read.setText("다시 읽기");
        read.setOnClickListener(v -> refresh());
        col.addView(read);

        tv = new TextView(this);
        tv.setTextIsSelectable(true);
        col.addView(tv);

        ScrollView sc = new ScrollView(this);
        sc.addView(col);
        setContentView(sc);

        Shizuku.addBinderReceivedListenerSticky(recvL);
        Shizuku.addBinderDeadListener(deadL);
        Shizuku.addRequestPermissionResultListener(permL);
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(recvL);
        Shizuku.removeBinderDeadListener(deadL);
        Shizuku.removeRequestPermissionResultListener(permL);
    }

    private void log(String s) {
        android.util.Log.i("NRSHIZU", s);
        buf.append(ts.format(new Date())).append("  ").append(s).append('\n');
        final String out = buf.toString();
        runOnUiThread(() -> tv.setText(out));
    }

    private void head(String s) {
        android.util.Log.i("NRSHIZU", "=== " + s);
        buf.setLength(0);
        buf.append(s).append("\n\n");
        final String out = buf.toString();
        runOnUiThread(() -> tv.setText(out));
    }

    private void refresh() {
        if (!Shizuku.pingBinder()) {
            head("Shizuku가 안 떠 있어요.\nShizuku 앱을 열어 '시작'한 뒤 [다시 읽기]를 눌러요.");
            return;
        }
        if (Shizuku.isPreV11()) {
            head("이 Shizuku는 옛 권한 방식(Shizuku API v11 이전)이에요. 이 PoC는 Shizuku API v11+ 방식만 다뤄요.");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                head("Shizuku 사용 권한이 거부돼 있어요. Shizuku 앱에서 이 앱을 허용해 주세요.");
            } else {
                head("Shizuku 사용 권한을 요청할게요. 뜨는 창에서 '허용'을 눌러요.");
                Shizuku.requestPermission(REQ);
            }
            return;
        }
        head("Shizuku 권한 OK. 네 사유의 허용 망을 Shizuku로 '읽기만' 하는 중… (아무것도 바꾸지 않아요)");
        new Thread(this::readAll).start();
    }

    private void readAll() {
        try {
            int sub = SubscriptionManager.getDefaultDataSubscriptionId();
            Object tel = telephony();
            log("기본 데이터 SIM sub=" + sub + " (Shizuku 경유 호출)");
            log("Shizuku 원격 신분 uid=" + Shizuku.getUid() + " (2000=shell), 버전=" + Shizuku.getVersion()
                    + ", SELinux=" + safeSelinux());
            for (int r = 0; r < 4; r++) {
                long v = getReason(tel, sub, r);
                log("사유 " + name(r) + " = " + v + (hasNr(v) ? "  (NR 있음)" : "  (NR 없음)"));
            }
            log("\n읽기 성공 = Shizuku 통로로 전화 설정을 '통신사 권한 없이' 읽었다는 뜻.");
            log("이 PoC는 읽기만 해요 — 네트워크 설정을 바꾸지 않았어요.");
        } catch (Throwable t) {
            log("읽기 실패: " + unwrap(t));
        }
    }

    /**
     * Android 9+의 숨은 API 접근 제한을 이 앱 프로세스에 한해 완화한다(LSPosed HiddenApiBypass, Unsafe 기반 구현).
     * ITelephony.getAllowedNetworkTypesForReason 같은 숨은 메서드를 앱 프로세스가 '찾을' 수 있게 할 뿐이고,
     * 실제 특권 호출은 여전히 Shizuku(shell 신분)로 대행된다. 권한을 올리는 코드가 아니다.
     */
    private static void allowHiddenApis() {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        try {
            boolean ok = org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
            android.util.Log.i("NRSHIZU", "hidden API 차단 해제 결과=" + ok);
        } catch (Throwable t) {
            android.util.Log.w("NRSHIZU", "hidden API 해제 실패: " + t);
        }
    }

    // ---- Shizuku 경유 전화 서비스(읽기 전용) ----

    private static Object telephony() throws Exception {
        IBinder raw = SystemServiceHelper.getSystemService("phone");
        if (raw == null) throw new IllegalStateException("phone 서비스 binder를 못 얻음");
        IBinder wrapped = new ShizukuBinderWrapper(raw);
        Class<?> stub = Class.forName("com.android.internal.telephony.ITelephony$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, wrapped);
    }

    private static long getReason(Object tel, int sub, int reason) throws Exception {
        Method m = tel.getClass().getMethod("getAllowedNetworkTypesForReason", int.class, int.class);
        return (Long) m.invoke(tel, sub, reason);
    }

    private static String safeSelinux() {
        try {
            return Shizuku.getSELinuxContext();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean hasNr(long mask) {
        return (mask & NR_BIT) != 0;
    }

    private static String name(int r) {
        switch (r) {
            case USER: return "USER";
            case POWER: return "POWER";
            case CARRIER: return "CARRIER";
            case ENABLE_2G: return "ENABLE_2G";
            default: return "?" + r;
        }
    }

    private static String unwrap(Throwable t) {
        Throwable c = t;
        while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
