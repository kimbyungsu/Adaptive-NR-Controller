package nrc.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * 처음 설정의 코드 입력 알림(사용자 결정 2026-09-30: 알림 + 분할 화면 둘 다).
 * 설정 화면의 코드 창이 닫히면 코드가 무효라, 사용자가 코드 창을 띄운 채 알림창을 내려 여기에 적는다(Shizuku와 같은 방식).
 * 알림의 [코드 입력] → 이 수신기 → Setup 시작. 진행과 결과는 같은 알림을 고쳐 보여 준다.
 * 수신기는 밖에 열지 않는다(이 앱이 만든 PendingIntent만 부른다).
 */
public final class SetupNotice extends BroadcastReceiver {
    static final String CHANNEL = "setup";
    static final int ID = 7;
    static final String KEY_CODE = "code";
    private static final String ACTION = "nrc.controller.SETUP_CODE";

    private static Setup.Listener listener;

    /** 코드 입력 칸이 있는 알림을 띄운다. 앱 알림이 꺼져 있으면 false(화면이 켜 달라고 안내한다). */
    static boolean showInput(Context c, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        ensureChannel(nm);
        RemoteInput ri = new RemoteInput.Builder(KEY_CODE).setLabel("6자리 코드").build();
        Intent i = new Intent(ACTION).setClass(c, SetupNotice.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action act = new Notification.Action.Builder(null, "코드 입력", pi).addRemoteInput(ri).build();
        nm.notify(ID, base(c, text).addAction(act).build());
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** 입력 칸 없이 글만 고친다(진행 중·끝). */
    static void showText(Context c, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(nm);
        nm.notify(ID, base(c, text).build());
    }

    static void cancel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
    }

    private static Notification.Builder base(Context c, String text) {
        PendingIntent open = PendingIntent.getActivity(c, 2, new Intent(c, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.nrc_tile)
                .setContentTitle("5G 자동 제어 · 처음 설정")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false);
    }

    private static void ensureChannel(NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "처음 설정", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("처음 설정 때 페어링 코드를 입력하는 알림(설정할 때만 떠요)");
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Bundle r = RemoteInput.getResultsFromIntent(intent);
        CharSequence code = r == null ? null : r.getCharSequence(KEY_CODE);
        Context c = ctx.getApplicationContext();
        if (code == null) return;
        attachListener(c);
        if (!Setup.start(c, code.toString(), -1, -1)) {
            showText(c, "이미 설정을 진행하고 있어요");
            return;
        }
        showText(c, "연결하는 중… (코드 창은 그대로 두세요)");
    }

    /** 알림으로 시작한 설정의 진행을 알림에 보여 준다(한 번만 붙인다). */
    private static synchronized void attachListener(Context c) {
        if (listener != null) return;
        listener = new Setup.Listener() {
            @Override
            public void line(String text, boolean ok) {
                showText(c, (ok ? "✓ " : "✗ ") + text);
            }

            @Override
            public void finished(boolean ok) {
                String w = Setup.warning();
                if (ok && w == null) {
                    showText(c, "끝났어요 ✓ 이 알림을 눌러 앱에서 마무리해 주세요(배터리 최적화 제외). 설정이 끝났으니 앱 알림은 다시 꺼도 돼요.");
                } else if (ok) {
                    showText(c, "권한 설정은 끝났어요 ✓ 다만 " + w + " (이 알림을 누르면 앱이 열려요)");
                } else {
                    showInput(c, "안 됐어요 ✗ " + lastLine() + " — 코드 창을 다시 띄우고 [코드 입력]으로 다시 해 주세요.");
                }
            }
        };
        Setup.addListener(listener);
    }

    private static String lastLine() {
        java.util.List<String[]> h = Setup.history();
        return h.isEmpty() ? "" : h.get(h.size() - 1)[0];
    }
}
