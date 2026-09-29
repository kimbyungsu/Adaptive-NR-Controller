package nrc.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 상주 서비스(DESIGN §5.13). 자동 제어가 켜져 있고 폰이 이 앱을 통신사가 인정한 앱으로 대하면 판단 엔진(Engine)을 돌린다.
 * - 알림 한 줄은 전면 서비스에 필요해 늘 만든다. 사용자가 이 앱의 알림을 꺼 두면 알림 창에는 나타나지 않는다(안드로이드 13+).
 * - 화면 켜짐·꺼짐, Wi-Fi, 삼성 설정 키 변경, 통신사 설정 변경, 폰 꺼짐을 받아 엔진에 넘긴다.
 * - 엔진이 없을 때 우리 막음이 남아 있으면(예: 통화 중이라 풀기를 미룬 채 자동 제어를 끔) 30초마다 풀기를 다시 시도한다.
 * 엔진과 관련된 일은 모두 작업 스레드(worker) 하나에서 한다.
 */
public final class ControllerService extends Service implements Engine.Host,
        SharedPreferences.OnSharedPreferenceChangeListener {
    static final String CHANNEL = "status";
    static final int NOTE_ID = 1;
    /** 삼성 설정 화면의 모드 저장 키 앞부분(뒤에 SIM 번호). 사용자 선택이 바뀐 때를 알아채는 신호로 쓴다(§5.12). */
    static final String KEY_PREFIX = "preferred_network_mode";
    /** 통신사 권한이 없을 때만 쓰는 임시 판정: 삼성 모드 번호가 이 값 이상이면 5G 포함. */
    static final int FIRST_NR_MODE = 23;
    static final long LEFTOVER_RETRY_MS = 30_000;
    static final long SHUTDOWN_WAIT_MS = 3_000;

    /**
     * 이 프로세스에서 서비스가 떠 있는지. 앱은 한 프로세스라 타일·화면도 같은 값을 본다. 프로세스가 죽었다 다시 뜨면 false에서 시작한다.
     * 타일·화면은 이 값이 false면 "멈춤"으로 보여 준다(관리 중인 척하지 않음).
     */
    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService worker;
    private Journal journal;
    private Radio radio;
    private Engine engine;
    private ScheduledFuture<?> leftoverRetry;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private ContentObserver keyObs;
    private BroadcastReceiver sysRx;
    private SharedPreferences prefs;
    private String key;
    private volatile boolean wifi;
    /** 폰 꺼짐·서비스 종료 처리를 시작했다. 이후 새 일은 받지 않는다. */
    private volatile boolean terminating;

    /**
     * 서비스를 띄운다(이미 떠 있으면 알림 한 줄을 다시 올리고 엔진 상태를 다시 본다). 시작 요청이 거절되면 false.
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
        worker = Executors.newSingleThreadScheduledExecutor();
        File dir = getExternalFilesDir(null);
        journal = new Journal(dir != null ? dir : getFilesDir());
        journal.write("service_start");
        prefs = AppState.prefs(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        key = KEY_PREFIX + SubscriptionManager.getDefaultDataSubscriptionId();
        keyObs = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                post("key", () -> {
                    if (engine != null) engine.onUserSignal();
                    else publishIdle();
                });
            }
        };
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(key), false, keyObs);

        cm = getSystemService(ConnectivityManager.class);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                setWifi(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            }

            @Override
            public void onLost(Network n) {
                setWifi(false);
            }
        };
        cm.registerDefaultNetworkCallback(netCb, main);

        sysRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_SCREEN_OFF.equals(a)) {
                    boolean on = Intent.ACTION_SCREEN_ON.equals(a);
                    post("screen", () -> {
                        if (engine != null) engine.onScreen(on);
                    });
                } else if (Intent.ACTION_SHUTDOWN.equals(a)) {
                    liftBeforeShutdown();
                } else if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(a)) {
                    post("carrier_config", () -> {
                        journal.write("carrier_config_changed");
                        startEngineIfWanted(); // 통신사 인정이 막 생겼을 수 있다
                        if (engine == null) publishIdle();
                    });
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SHUTDOWN);
        f.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        registerReceiver(sysRx, f);

        post("create", this::startEngineIfWanted);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTE_ID, note()); // 알림 허락이 나중에 켜졌으면 이때 보인다
        post("start_command", () -> {
            startEngineIfWanted();
            if (engine == null) publishIdle();
            AppState.refreshTile(this); // 상태가 그대로여도 타일을 한 번 칠한다(부팅·업데이트 뒤)
        });
        return START_STICKY;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String k) {
        if (!AppState.AUTO.equals(k)) return;
        post("auto", () -> {
            boolean on = AppState.auto(this);
            journal.write("auto", "on", on);
            if (on) {
                startEngineIfWanted();
            } else if (engine != null) {
                engine.stop(); // 쉬는 중이면 풀고 끝난 뒤 stopped()
            } else {
                publishIdle();
            }
        });
    }

    // ================================================================ 엔진 켜기(작업 스레드)

    private void startEngineIfWanted() {
        if (engine != null || !AppState.auto(this)) return;
        radio = Radio.open(this);
        if (radio == null) {
            journal.write("engine_wait", "why", "no_sim");
            publishIdle();
            return;
        }
        if (!radio.privileged()) {
            journal.write("engine_wait", "why", "no_privilege", "own", Engine.ownMask(this, radio.sub));
            publishIdle();
            return;
        }
        engine = new Engine(this, radio, journal, worker, this);
        engine.start(wifi, isInteractive());
    }

    // ================================================================ Engine.Host(작업 스레드에서 불린다)

    @Override
    public void publish(int mode, String phase, String problem) {
        AppState.observed(this, mode, wifi, phase, problem, true);
    }

    /**
     * 자동 제어 끄기가 끝났다. 끄기를 마무리하는 사이(예: 통화가 끝나길 기다리는 동안) 사용자가 다시 켰을 수 있으므로
     * 최신 선택을 다시 보고 켜져 있으면 엔진을 새로 띄운다(외부 검증 지적: 다시 켜기가 사라지고 "관리 중"으로 보였다).
     */
    @Override
    public void stopped() {
        engine = null;
        if (!terminating && AppState.auto(this)) startEngineIfWanted();
        if (engine == null) publishIdle();
    }

    /** 엔진이 없을 때의 상태: 사용자 모드와 문제(설정 필요 등). 우리 막음이 남아 있으면 풀기 재시도를 건다. */
    private void publishIdle() {
        Radio r = radio != null ? radio : Radio.open(this);
        String problem = null;
        int mode;
        boolean ours = r != null && Engine.ownMask(this, r.sub) >= 0;
        if (r == null) {
            problem = "SIM 확인 중";
            mode = TileText.MODE_UNKNOWN;
        } else if (r.privileged()) {
            long u = r.read(Radio.USER);
            mode = u < 0 ? TileText.MODE_UNKNOWN : (CarrierPlan.hasNr(u) ? TileText.MODE_NR : TileText.MODE_LTE);
            if (ours) liftLeftover(r, "idle");
        } else {
            mode = keyMode();
            problem = ours ? "5G 막힘 · 다시 설정 필요" : "처음 설정 필요";
        }
        AppState.observed(this, mode, wifi, null, problem, false);
        scheduleLeftoverRetry(r);
    }

    /**
     * 엔진 없이 남은 이 SIM의 우리 막음을 푼다(통화 중이면 다음 재시도에서). 지금 값이 우리가 남긴 값이 아니면
     * 남이 바꾼 것이므로 풀지 않고 우리 기록만 지운다.
     */
    private void liftLeftover(Radio r, String why) {
        long own = Engine.ownMask(this, r.sub);
        if (own < 0) return;
        long c = r.read(Radio.CARRIER);
        CarrierPlan.Carrier k = CarrierPlan.classify(c, own);
        if (k == CarrierPlan.Carrier.OPEN || k == CarrierPlan.Carrier.EXTERNAL) {
            Engine.setOwn(this, r.sub, -1);
            journal.write("own_cleared", "why", why, "carrier", c, "own", own, "as", k.name());
            return;
        }
        if (k != CarrierPlan.Carrier.OURS || r.callGuard() != null) return;
        boolean called = r.writeCarrier(CarrierPlan.target(c, true));
        long after = r.read(Radio.CARRIER);
        boolean ok = called && CarrierPlan.hasNr(after);
        journal.write("lift", "why", why, "ok", ok, "before", c, "after", after);
        if (ok) Engine.setOwn(this, r.sub, -1);
    }

    /** 엔진이 없는데 이 SIM에 우리 막음이 남아 있으면 30초 뒤 다시 풀어 본다(다른 SIM의 기록은 그 SIM이 켜졌을 때 푼다). */
    private void scheduleLeftoverRetry(Radio r) {
        if (leftoverRetry != null) leftoverRetry.cancel(false);
        leftoverRetry = null;
        if (terminating || engine != null || r == null || Engine.ownMask(this, r.sub) < 0) return;
        leftoverRetry = worker.schedule(() -> guarded("leftover", () -> {
            if (engine == null) publishIdle();
        }), LEFTOVER_RETRY_MS, TimeUnit.MILLISECONDS);
    }

    // ================================================================ 폰 꺼짐

    /**
     * 폰이 꺼지기 직전·서비스 종료: 먼저 새 일을 막고 엔진을 완전히 멈춘 뒤, 쉬는 중이어도 우리 막음을 푼다(최대 3초 기다림).
     * 외부 검증 지적: 엔진을 멈추지 않고 풀기만 하면 줄 서 있던 사건이 다시 막을 수 있었다.
     */
    private void liftBeforeShutdown() {
        if (worker == null || worker.isShutdown()) return;
        terminating = true;
        try {
            Future<?> f = worker.submit(() -> guarded("shutdown", () -> {
                journal.write("shutdown");
                if (leftoverRetry != null) leftoverRetry.cancel(false);
                if (engine != null) {
                    engine.terminate("shutdown");
                } else {
                    Radio r = radio != null ? radio : Radio.open(this);
                    if (r != null && r.privileged()) liftLeftover(r, "shutdown");
                }
            }));
            f.get(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // 시간 안에 못 끝내면 다음 시작 때 푼다(Engine.start의 정리)
        }
    }

    // ================================================================ 내부

    private void setWifi(boolean on) {
        if (wifi == on) return;
        wifi = on;
        post("wifi", () -> {
            if (engine != null) engine.onWifi(on);
            else publishIdle();
        });
    }

    private void post(String where, Runnable r) {
        if (terminating || worker == null || worker.isShutdown()) return;
        worker.execute(() -> guarded(where, r));
    }

    private void guarded(String where, Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            if (journal != null) journal.write("error", "where", where, "msg", String.valueOf(e));
        }
    }

    private boolean isInteractive() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isInteractive();
    }

    /** 통신사 권한이 없을 때 삼성 설정 키로 본 사용자 모드(임시 판정). 못 읽으면 모름. */
    private int keyMode() {
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
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (keyObs != null) getContentResolver().unregisterContentObserver(keyObs);
        if (sysRx != null) {
            try {
                unregisterReceiver(sysRx);
            } catch (RuntimeException ignored) {
                // 이미 풀렸으면 무시
            }
        }
        if (cm != null && netCb != null) {
            try {
                cm.unregisterNetworkCallback(netCb);
            } catch (RuntimeException ignored) {
                // 이미 풀렸으면 무시
            }
        }
        // 서비스가 정상 종료되면 폰 꺼짐과 같게 우리 막음을 푼다(쉬는 중이어도)
        liftBeforeShutdown();
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }
}
