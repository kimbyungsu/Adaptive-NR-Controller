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

    /**
     * 이 프로세스에서 서비스가 떠 있는지. 앱은 한 프로세스라 타일·화면도 같은 값을 본다. 프로세스가 죽었다 다시 뜨면 false에서 시작한다.
     * 타일·화면은 이 값이 false면 "멈춤"으로 보여 준다(관리 중인 척하지 않음).
     */
    static volatile boolean running;

    /**
     * 서비스를 띄운다(이미 떠 있으면 알림 한 줄을 다시 올리고 상태를 새로 읽는다). 시작 요청이 거절되면 false.
     * 거절돼도 running이 false로 남으므로 타일·화면에 "멈춤"이 보인다.
     */
    static boolean ensure(Context c) {
        try {
            c.startForegroundService(new Intent(c, ControllerService.class));
            return true;
        } catch (RuntimeException e) {
            AppState.refreshTile(c);
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "작동 상태", NotificationManager.IMPORTANCE_MIN));
        startForeground(NOTE_ID, note());
        running = true;
        AppState.aliveChanged(this);
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

    /**
     * 삼성 설정 키로 본 사용자 모드. 못 읽으면 모름.
     * 뼈대의 임시 판정이다: 엔진을 연결할 때 설정 화면이 실제로 보여 주는 USER 사유(통신사 권한으로 읽기)로 바꾸고,
     * 이 키는 사용자 선택이 바뀐 때를 알아채는 신호로만 쓴다(DESIGN §5.13).
     */
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
        running = false;
        AppState.aliveChanged(this);
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
