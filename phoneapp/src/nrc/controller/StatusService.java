package nrc.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

/**
 * 상주 서비스. 이번 단계에서는 상단바 표시만 맡는다(시험 화면에서 고른 모양·색·크기·위치).
 * 알림 창의 "작동 중" 한 줄은 이 서비스의 알림이다. 사용자가 이 앱의 알림을 꺼도 서비스와 상단바 표시는 계속된다.
 */
public final class StatusService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String CHANNEL = "status";
    static final int NOTE_ID = 1;

    private Mark mark;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        // 중요도 최소: 알림 창 아래 무음 칸에만 보이고 상단바 아이콘·소리·진동은 없다(상단바 표시는 앱이 따로 그린다)
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "작동 상태", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.nrc_note)
                .setContentTitle("NR 컨트롤러")
                .setContentText("상단바 표시 시험 중")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(NOTE_ID, n);
        mark = new Mark(this);
        prefs = Prefs.of(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refresh();
        return START_STICKY;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        refresh();
    }

    private void refresh() {
        mark.show(Prefs.preview(prefs), Prefs.size(prefs), Prefs.x(prefs), Prefs.y(prefs));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        mark.hide();
        super.onDestroy();
    }
}
