package nrc.poc;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 앱 프로세스 안에서 통신사 권한 확인·허용 망 변경을 한다(ADB가 대신 실행하지 않는다). */
final class Probe {
    static final String TAG = "NRCPOC";
    /** 레퍼런스 기기 값: 5G 포함(840583), LTE 계열(316295). NR 비트 = 1<<19. */
    static final long NR_BIT = 1L << 19;
    static final long WITH_NR = 840583L;

    private Probe() {
    }

    static String run(Context ctx, String op, int reason, String from) {
        StringBuilder sb = new StringBuilder();
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        sb.append("from=").append(from).append(" op=").append(op).append(" uid=").append(android.os.Process.myUid())
                .append(" sub=").append(sub);
        TelephonyManager tm = ctx.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);
        boolean priv = false;
        try {
            priv = tm.hasCarrierPrivileges();
            sb.append(" carrierPriv=").append(priv);
        } catch (Throwable e) {
            sb.append(" carrierPriv=err:").append(e);
        }
        if ("block".equals(op) || "unblock".equals(op)) {
            long mask = "block".equals(op) ? (WITH_NR & ~NR_BIT) : WITH_NR;
            try {
                tm.setAllowedNetworkTypesForReason(reason, mask);
                sb.append(" set reason=").append(reason).append(" mask=").append(mask).append(" ok");
            } catch (Throwable e) {
                sb.append(" set reason=").append(reason).append(" failed=").append(e);
            }
        }
        for (int r = 0; r < 4; r++) {
            try {
                long v = tm.getAllowedNetworkTypesForReason(r);
                sb.append(" r").append(r).append('=').append(v).append((v & NR_BIT) != 0 ? "(NR)" : "(noNR)");
            } catch (Throwable e) {
                sb.append(" r").append(r).append("=err:").append(e.getClass().getSimpleName());
            }
        }
        String line = sb.toString();
        Log.i(TAG, line);
        append(ctx, line);
        return line;
    }

    private static void append(Context ctx, String line) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            try (FileWriter w = new FileWriter(new File(dir, "poc.log"), true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + line + "\n");
            }
        } catch (Throwable ignored) {
            // 기록 실패는 무시(logcat에는 남음)
        }
    }
}
