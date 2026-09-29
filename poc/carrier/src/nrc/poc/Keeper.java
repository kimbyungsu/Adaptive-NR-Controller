package nrc.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 관문 ④: 부팅 뒤 스스로 올라와 오래 살아 있는지 본다(전면 서비스).
 * 30초·2분 뒤, 이후 10분마다 권한을 기록한다. 기록 간격이 벌어지면 그동안 잠들었거나 죽었다는 뜻이다(pid로 구분).
 */
public final class Keeper extends Service {
    static final String CH = "keeper";
    private final Handler h = new Handler(Looper.getMainLooper());
    private long started;
    private int beats;

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            beats++;
            long upMin = (SystemClock.elapsedRealtime() - started) / 60000;
            Probe.run(Keeper.this, "check", 0, "keeper:beat" + beats + ":upMin" + upMin + ":sig" + Signals.summary());
            h.postDelayed(this, beats < 2 ? 90_000L : 600_000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "PoC 생존 시험", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("NR PoC")
                .build();
        startForeground(1, n);
        started = SystemClock.elapsedRealtime();
        Probe.run(this, "check", 0, "keeper:create");
        Signals.start(this);
        h.postDelayed(beat, 30_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(beat);
        Signals.stop();
        Probe.run(this, "check", 0, "keeper:destroy");
        super.onDestroy();
    }
}
