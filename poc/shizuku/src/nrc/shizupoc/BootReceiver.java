package nrc.shizupoc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;

/**
 * 재부팅(BOOT_COMPLETED) 뒤, '되돌릴 기록'(POWER로 5G를 막아 둔 채 끝나지 않음)이 남아 있으면 사용자에게 알린다
 * — §5.15 '지금 못 바꿈' 정직 표시의 재부팅판. 재부팅 뒤에는 앱이 떠 있지 않으므로, 사용자가 앱을 안 열어도
 * 상황을 알리는 유일한 통로가 알림이다.
 *
 * 경계(중요, self-bootstrap-boundary): 이 리시버는 어떤 특권도 쓰지 않고, Shizuku를 자동으로 켜지도 않는다.
 * 그저 알릴 뿐이다. 사용자가 알림을 눌러 앱을 열고 Shizuku를 켜면 Main이 내구 기록을 보고 되돌린다.
 * 알림 자체가 실패해도(권한 없음 등) 앱을 열면 Main이 '지금 못 바꿈'을 보여주므로 조용히 넘어간다.
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "nrc_restore";
    private static final int NOTE_ID = 1001;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // 되돌릴 게 없으면 조용히(앱 내부 저장소의 기록 파일 — 재부팅 후에도 남는다).
        File pending = new File(ctx.getFilesDir(), "pending_power_restore.txt");
        if (!pending.exists()) return;
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "5G 되돌리기 안내", NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(ctx, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification note = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("5G가 꺼진 채 멈췄을 수 있어요")
                    .setContentText("도우미(Shizuku)를 켜고 이 앱을 열면 원래대로 되돌리기를 시도할게요.")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(NOTE_ID, note);
        } catch (Throwable ignored) {
            // 알림 실패는 조용히 — 앱을 열면 Main이 '지금 못 바꿈'을 보여준다.
        }
    }
}
