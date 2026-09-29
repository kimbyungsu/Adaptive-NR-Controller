package nrc.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionManager;

/**
 * 상주 서비스(DESIGN §5.13 "상태 표시 구조"). 이번 단계(뼈대): 사용자가 고른 모드와 Wi-Fi 여부를 지켜보고 타일 상태를 갱신한다.
 * 판단 엔진(쉬기·재시험)은 다음 단계에서 이 서비스에 옮긴다.
 * 알림 한 줄은 전면 서비스에 필요해 늘 만든다. 사용자가 이 앱의 알림을 꺼 두면 알림 창에는 나타나지 않는다(안드로이드 13+).
 */
public final class ControllerService extends Service {
    static final String CHANNEL = "status";
    static final int NOTE_ID = 1;
    /** 삼성 설정 화면의 모드 저장 키 앞부분(뒤에 SIM 번호). 사용자 선택이 바뀐 때를 알아채는 신호로만 쓴다(§5.12). */
    static final String KEY_PREFIX = "preferred_network_mode";
    /** 삼성 모드 번호가 이 값 이상이면 5G 포함(기존 nrctl `_key_has_nr`와 같은 기준). */
    static final int FIRST_NR_MODE = 23;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private ContentObserver keyObs;
    private String key;
    private volatile boolean wifi;

    /** 서비스를 띄운다(이미 떠 있으면 알림 한 줄을 다시 올리고 상태를 새로 읽는다). 막히면 조용히 넘긴다. */
    static void ensure(Context c) {
        try {
            c.startForegroundService(new Intent(c, ControllerService.class));
        } catch (RuntimeException ignored) {
            // 배경 시작 제한 등: 다음 부팅·앱 열기·타일 누르기 때 다시 시도된다
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "작동 상태", NotificationManager.IMPORTANCE_MIN));
        startForeground(NOTE_ID, note());
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        key = KEY_PREFIX + sub;
        keyObs = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
            }
        };
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(key), false, keyObs);
        cm = getSystemService(ConnectivityManager.class);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                main.post(ControllerService.this::refresh);
            }

            @Override
            public void onLost(Network n) {
                wifi = false;
                main.post(ControllerService.this::refresh);
            }
        };
        cm.registerDefaultNetworkCallback(netCb, main);
        refresh();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTE_ID, note()); // 알림 허락이 나중에 켜졌으면 이때 보인다
        refresh();
        // 상태가 그대로여도 타일을 한 번 칠하게 한다(부팅·업데이트 뒤 타일이 예전 모양에 머물지 않게, 09-30 기기 확인)
        AppState.refreshTile(this);
        return START_STICKY;
    }

    private void refresh() {
        AppState.observed(this, mode(), wifi, null, null);
    }

    /** 삼성 설정 키로 본 사용자 모드. 못 읽으면 모름. */
    private int mode() {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            if (v == null) return TileText.MODE_UNKNOWN;
            return Integer.parseInt(v.trim()) >= FIRST_NR_MODE ? TileText.MODE_NR : TileText.MODE_LTE;
        } catch (RuntimeException e) {
            return TileText.MODE_UNKNOWN;
        }
    }

    private Notification note() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.nrc_tile)
                .setContentTitle("5G 자동 제어")
                .setContentText("작동 중")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (keyObs != null) getContentResolver().unregisterContentObserver(keyObs);
        if (cm != null && netCb != null) {
            try {
                cm.unregisterNetworkCallback(netCb);
            } catch (RuntimeException ignored) {
                // 이미 풀렸으면 무시
            }
        }
        super.onDestroy();
    }
}
