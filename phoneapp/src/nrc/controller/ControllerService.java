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
 * 상주 서비스(DESIGN §5.13). 자동 제어가 켜져 있고 바꿀 통로가 있으면 판단 엔진(Engine)을 돌린다.
 * - 통로(§5.16 길 사다리): 길 1 = 폰이 이 앱을 통신사가 인정한 앱으로 대함. 길 2 = Shizuku 도우미(등록 방식이 막힌 게 확인된 폰,
 *   또는 개발 시험으로 고름). 길 2는 Shizuku가 꺼지거나 폰이 재부팅되면 통로를 잃고, Shizuku가 돌아오면 앱이 스스로 이어 간다.
 *   끊긴 때·원인·그때 상황(Shizuku·디버깅·Wi-Fi·화면)과 재부팅을 활동 기록에 남겨, 사용자가 앱에서 이어지는지 확인할 수 있게 한다.
 * - 알림 한 줄은 전면 서비스에 필요해 늘 만든다. 사용자가 이 앱의 알림을 꺼 두면 알림 창에는 나타나지 않는다(안드로이드 13+).
 * - 화면 켜짐·꺼짐, Wi-Fi, 삼성 설정 키 변경, 통신사 설정 변경, 폰 꺼짐을 받아 엔진에 넘긴다.
 * - 엔진이 없을 때 우리 막음이 남아 있으면(예: 통화 중이라 풀기를 미룬 채 자동 제어를 끔) 30초마다 풀기를 다시 시도한다.
 * 엔진과 관련된 일은 모두 작업 스레드(worker) 하나에서 한다.
 */
public final class ControllerService extends Service implements Engine.Host, Shz.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    static final String CHANNEL = "status";
    static final int NOTE_ID = 1;
    /** 삼성 설정 화면의 모드 저장 키 앞부분(뒤에 SIM 번호). 사용자 선택이 바뀐 때를 알아채는 신호로 쓴다(§5.12). */
    static final String KEY_PREFIX = "preferred_network_mode";
    /** 통신사 권한이 없을 때만 쓰는 임시 판정: 삼성 모드 번호가 이 값 이상이면 5G 포함. */
    static final int FIRST_NR_MODE = 23;
    static final long LEFTOVER_RETRY_MS = 30_000;
    static final long SHUTDOWN_WAIT_MS = 3_000;
    static final long SHZ_AUDIT_MS = 60_000;
    /** 부팅 직후 통신사 인정·SIM이 자리잡기까지 기다리는 재시도 간격·횟수(§5.18 전진 설계: Phomeleon식 수십 초 지연 허용). */
    static final long RECOVERY_RETRY_MS = 5_000;
    static final int RECOVERY_MAX_TRIES = 12;
    /** 무선 디버깅 켜짐 여부 전역 설정 키(Shizuku가 꺼지는 원인 진단용으로 바뀜만 기록). */
    static final String ADB_WIFI_KEY = "adb_wifi_enabled";

    /**
     * 이 프로세스에서 서비스가 떠 있는지. 앱은 한 프로세스라 타일·화면도 같은 값을 본다. 프로세스가 죽었다 다시 뜨면 false에서 시작한다.
     * 타일·화면은 이 값이 false면 "멈춤"으로 보여 준다(관리 중인 척하지 않음).
     */
    static volatile boolean running;
    /** 개발 시험 명령이 엔진에 닿기 위한 지금 서비스(없으면 null). */
    private static volatile ControllerService current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService worker;
    private Journal journal;
    /** 활동 기록(관측 화면). 프로세스에 하나. */
    private Timeline timeline;
    private Radio radio;
    private Engine engine;
    private ScheduledFuture<?> leftoverRetry;
    /** 부팅 직후 통신사 인정·SIM이 늦게 자리잡는 경우를 위한 한정 재시도(엔진이 뜨거나 한도를 넘으면 멈춘다). 길 1(통신사) 경로에서만 쓴다. */
    private ScheduledFuture<?> recoveryRetry;
    private int recoveryTries;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private ContentObserver keyObs;
    private BroadcastReceiver sysRx;
    private SharedPreferences prefs;
    private String key;
    private volatile boolean wifi;
    /** 폰 꺼짐·서비스 종료 처리를 시작했다. 이후 새 일은 받지 않는다. */
    private volatile boolean terminating;
    /** 길 2(Shizuku) 관리자. 길 2를 쓰기 시작할 때 붙인다(작업 스레드). */
    private Shz shz;
    private ContentObserver adbObs;
    private ScheduledFuture<?> shzAudit;

    /**
     * 서비스를 띄운다(이미 떠 있으면 알림 한 줄을 다시 올리고 엔진 상태를 다시 본다). 시작 요청이 거절되면 false.
     * 거절돼도 running이 false로 남으므로 타일·화면에 "멈춤"이 보인다.
     */
    static boolean ensure(Context c) {
        return ensure(c, "ui");
    }

    /** cause = 무엇이 서비스를 띄웠는지(부팅 "boot"/업데이트 "update"/화면·타일 "ui"). 원본 기록 nrc.log에 남겨 '무개입 부팅 복구'를 '앱 열어서 시작'과 구분한다(§5.18 확인용 — 알림·활동 기록은 보조 신호일 뿐). */
    static boolean ensure(Context c, String cause) {
        try {
            c.startForegroundService(new Intent(c, ControllerService.class).putExtra("cause", cause));
            return true;
        } catch (RuntimeException e) {
            AppState.refreshTile(c);
            return false;
        }
    }

    /** 같은 안내를 연달아 쌓지 않는다(엔진을 못 띄운 이유 등). */
    private String lastNote;

    private void noteOnce(String key, String text) {
        if (key.equals(lastNote)) return;
        lastNote = key;
        timeline.add(Timeline.Cat.JUDGE, text);
    }

    /** 활동 기록(프로세스에 하나). 화면도 이걸 읽는다. */
    static Timeline timeline(Context c) {
        return Timeline.get(c.getFilesDir());
    }

    /** 관측 화면이 읽는 엔진의 지금 모습(엔진이 없으면 null). */
    static Live live() {
        ControllerService s = current;
        Engine e = s == null ? null : s.engine;
        return e == null ? null : e.live();
    }

    /** 자가 점검(관측 화면 버튼): 작업 스레드에서 엔진이 한다. 결과 줄은 작업 스레드에서 sink로 온다. 서비스가 없으면 false. */
    static boolean selfTest(Engine.TestSink sink) {
        ControllerService s = current;
        if (s == null) return false;
        return s.post("self_test", () -> {
            if (s.engine != null) {
                s.engine.selfTest(sink);
            } else {
                sink.line("자동 제어가 꺼져 있어 점검할 수 없음", false);
                sink.done(false);
            }
        });
    }

    /**
     * 사용자가 [남은 5G 막음 풀기]를 눌렀다(확인 창 뒤). 앱이 건 것이 아닌 통신사 칸 5G 막음을 푼다 — 앱이 스스로는 절대 풀지 않는
     * 막음이라 사용자 요청일 때만. 통화 확인은 쓰기 바로 앞. 결과 줄은 작업 스레드에서 sink로 온다. 서비스가 없으면 false.
     */
    static boolean liftExternal(Engine.TestSink sink) {
        ControllerService s = current;
        if (s == null) return false;
        return s.post("lift_external", () -> s.doLiftExternal(sink));
    }

    private void doLiftExternal(Engine.TestSink sink) {
        Radio r = link();
        if (r == null || !r.privileged()) {
            sink.line(shizukuWay(this) ? "Shizuku 통로가 없어 풀 수 없어요(Shizuku를 켜고 승인해 주세요)"
                    : "5G/LTE 전환 권한이 없어 풀 수 없어요(처음 설정 필요)", false);
            sink.done(false);
            return;
        }
        long own = Engine.ownMask(this, r.sub);
        long c = r.read(Radio.CARRIER);
        CarrierPlan.Carrier k = CarrierPlan.classify(c, own);
        if (k == CarrierPlan.Carrier.UNKNOWN) {
            sink.line("값을 읽지 못했어요. 잠시 뒤 다시 눌러 주세요", false);
            sink.done(false);
            return;
        }
        if (k == CarrierPlan.Carrier.OPEN) {
            sink.line("이미 5G가 허용돼 있어요", true);
            sink.done(true);
            return;
        }
        if (k == CarrierPlan.Carrier.OURS) {
            sink.line("앱이 건 막음이라 앱이 알아서 풀어요(쉬는 중이면 쉬는 시간이 끝날 때)", true);
            sink.done(true);
            return;
        }
        String g = r.callGuard(); // 쓰기 바로 앞
        if (g != null) {
            sink.line("통화 중이라 지금은 풀 수 없어요. 통화가 끝난 뒤 다시 눌러 주세요", false);
            sink.done(false);
            return;
        }
        boolean called = r.writeCarrier(CarrierPlan.target(c, true));
        long after = r.read(Radio.CARRIER);
        boolean ok = called && CarrierPlan.hasNr(after);
        journal.write("lift_external", "ok", ok, "before", c, "after", after, "own", own);
        String text = "사용자 요청으로 남아 있던 5G 막음을 풂";
        timeline.add(Timeline.Cat.ACT, ok ? text : "남아 있던 5G 막음 풀기 실패");
        if (ok) {
            if (engine != null) engine.userLifted(text);
            else Engine.noteActionWithoutEngine(this, text);
        }
        sink.line(ok ? "풀었어요. 5G가 다시 허용됐어요" : "풀지 못했어요", ok);
        sink.done(ok);
    }

    /** 개발 시험(TestCommand): 엔진에 시험용 쉬기를 넘긴다. 서비스·엔진이 없으면 false. */
    static boolean testCooldown() {
        ControllerService s = current;
        if (s == null) return false;
        s.post("test_cooldown", () -> {
            if (s.engine != null) s.engine.testCooldown();
        });
        return true;
    }

    static boolean engineRunning() {
        ControllerService s = current;
        return s != null && s.engine != null;
    }

    /**
     * 길 2(Shizuku)로 제어하는지(DESIGN §5.16): 개발 시험으로 골랐거나, 등록 방식이 막힌 게 확인됐고(blocked_patch) 지금 통신사 인정이 없을 때.
     * 권한만 잃은 것은 '막힘'이 아니라 '모름'이라 길 2로 넘기지 않는다(먼저 처음 설정 다시).
     */
    static boolean shizukuWay(Context c) {
        if (AppState.WAY_SHIZUKU.equals(AppState.way(c))) return true;
        if (!SupportCheck.SETUP_BLOCKED_PATCH.equals(AppState.setupResult(c))) return false;
        Radio r = Radio.open(c);
        return r == null || !r.privileged();
    }

    /** 화면용: 지금 길의 Radio(길 2인데 도우미가 없으면 null, SIM이 없으면 null). */
    static Radio linkRadio(Context c) {
        return shizukuWay(c) ? Shz.get(c).radio() : Radio.open(c);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "작동 상태", NotificationManager.IMPORTANCE_MIN));
        startForeground(NOTE_ID, note());
        // 이전 실행의 "엔진 돌고 있음"을 먼저 지운다(재생성 직후 엔진이 뜨기 전 "관리 중"으로 보이지 않게, 외부 검증 보완)
        AppState.prefs(this).edit().putBoolean(AppState.ENGINE, false).commit();
        running = true;
        current = this;
        AppState.aliveChanged(this);
        worker = Executors.newSingleThreadScheduledExecutor();
        File dir = getExternalFilesDir(null);
        journal = new Journal(dir != null ? dir : getFilesDir());
        timeline = timeline(this);
        journal.write("service_start");
        prefs = AppState.prefs(this);
        noteBoot();
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

        // 엔진 시작은 onStartCommand에서 한다(시작 원인 기록 뒤). onBind가 null·바인딩 경로 없음 = start-only 서비스라
        // 모든 시작에 onStartCommand가 따라오므로, 여기서 먼저 걸면 engine_start가 start 기록보다 앞서게 된다(검증 지적 f-60d19a24).
        shzAudit = worker.scheduleWithFixedDelay(() -> guarded("shz_audit", () -> {
            if (shz != null) shz.audit();
        }), SHZ_AUDIT_MS, SHZ_AUDIT_MS, TimeUnit.MILLISECONDS);
    }

    /** 폰이 다시 켜졌으면(부팅 횟수가 바뀜) 활동 기록에 남긴다. 처음 설치 때는 비교할 값이 없어 남기지 않는다. */
    private void noteBoot() {
        int boots = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boots < 0) return;
        int seen = prefs.getInt(AppState.BOOT_SEEN, -1);
        if (seen == boots) return;
        prefs.edit().putInt(AppState.BOOT_SEEN, boots).apply();
        if (seen < 0) return;
        long bootWall = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        journal.write("boot", "count", boots);
        timeline.add(Timeline.Cat.OBS, "폰이 다시 켜짐(" + NowText.clock(bootWall) + "쯤 켜짐) → 앱이 다시 시작함");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTE_ID, note()); // 알림 허락이 나중에 켜졌으면 이때 보인다
        // 시작 원인: 부팅/업데이트/화면·타일(ui)은 ensure가 extra로 넣는다. intent==null은 START_STICKY로 시스템이
        // 되살린 것(사용자 조작 아님)이라 "restart", extra가 없으면 "unknown" — 무개입 시작을 "ui"로 오기록하지 않는다(f-b8652cd0).
        final String cause = intent == null ? "restart"
                : (intent.getStringExtra("cause") != null ? intent.getStringExtra("cause") : "unknown");
        post("start_command", () -> {
            journal.write("start", "cause", cause); // boot/update=무개입 · ui=앱 열기 · restart=시스템 재생성 · unknown — 무개입 복구 확인용(§5.18)
            startEngineIfWanted();
            if (engine == null) publishIdle();
            AppState.refreshTile(this); // 상태가 그대로여도 타일을 한 번 칠한다(부팅·업데이트 뒤)
        });
        return START_STICKY;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String k) {
        if (AppState.WAY.equals(k)) {
            post("way", () -> {
                String way = AppState.way(this);
                journal.write("way", "to", way);
                timeline.add(Timeline.Cat.JUDGE, "제어 방식 바뀜: "
                        + (AppState.WAY_SHIZUKU.equals(way) ? "Shizuku 방식(시험)" : "자동(통신사 인정 우선)"));
                // 통로가 실제로 바뀌면 지금 엔진을 먼저 끝내고(stopped 뒤 새 통로로 시작), 엔진이 없으면 바로 새 통로로
                startEngineIfWanted();
                if (engine == null) publishIdle();
            });
            return;
        }
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
        boolean viaShz = shizukuWay(this);
        if (engine != null) {
            // 통로가 바뀜(예: 길 2로 돌던 폰에 통신사 인정이 생김, 개발 시험 선택 변경): 지금 엔진을 지금 통로로 먼저 끝낸다
            // (쉬는 중이면 풀기, 통화 중이면 통화 뒤). 끝나면 stopped()가 다시 불러 새 통로로 시작하고, 도우미는 그때 뗀다.
            // 도우미를 먼저 떼면 옛 엔진이 죽은 통로에 남아 새 시작과 막음 정리가 막힌다(외부 검증 지적). stop은 여러 번 불러도 같다.
            if (engine.viaShizuku() != viaShz) engine.stop();
            return;
        }
        if (viaShz) attachShz();
        else detachShz();
        if (!AppState.auto(this)) return;
        Radio carrier = Radio.open(this);
        if (carrier == null) {
            journal.write("engine_wait", "why", "no_sim");
            noteOnce("no_sim", "SIM을 아직 확인하지 못해 자동 제어를 기다리는 중");
            if (!viaShz) armRecoveryRetry(); // 부팅 직후 SIM이 늦게 잡히는 경우 몇 번 더 본다
            publishIdle();
            return;
        }
        if (viaShz) {
            radio = shz.radio();
            if (radio == null) {
                Shz.State s = shz.state();
                journal.write("engine_wait", "why", "shizuku", "state", s.name(), "own", Engine.ownMask(this, carrier.sub));
                noteOnce("shizuku_" + s, "Shizuku 방식: " + Shz.waitText(s));
                publishIdle();
                return;
            }
        } else {
            radio = carrier;
            if (!radio.privileged()) {
                journal.write("engine_wait", "why", "no_privilege", "own", Engine.ownMask(this, radio.sub));
                noteOnce("no_privilege", "5G/LTE 전환 권한이 없어 자동 제어를 시작하지 못함(처음 설정 필요)");
                armRecoveryRetry(); // 부팅 직후 통신사 인정이 늦게 생기는 경우 몇 번 더 본다
                publishIdle();
                return;
            }
        }
        lastNote = null; // 다음에 다시 기다리게 되면 그 이유를 다시 남긴다
        cancelRecovery(); // 엔진이 떴으니 부팅 재시도는 멈춘다
        engine = new Engine(this, radio, journal, timeline, worker, this);
        engine.start(wifi, isInteractive());
    }

    /** 지금 길의 Radio(길 2인데 도우미가 없으면 null, SIM이 없으면 null). 작업 스레드. */
    private Radio link() {
        if (shizukuWay(this)) {
            attachShz();
            return shz.radio();
        }
        return Radio.open(this);
    }

    // ================================================================ 길 2(Shizuku) 연결(작업 스레드에서 붙이고 뗀다)

    private void attachShz() {
        if (shz != null) return;
        shz = Shz.get(this);
        shz.attach(this);
        journal.write("shz_attach");
        // 진단: 무선·USB 디버깅이 켜지고 꺼진 때를 기록한다(Shizuku가 꺼지는 원인과 맞춰 보려고). 못 읽는 폰이면 조용히 넘어간다
        final Shz z = shz; // 관찰자는 주 스레드에서 불린다: 떼어 낸 뒤 늦게 와도 필드(null일 수 있음) 대신 이 값을 쓴다
        try {
            adbObs = new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange, android.net.Uri uri) {
                    String k = uri == null ? null : uri.getLastPathSegment();
                    Integer v = k == null ? null : z.global(k);
                    if (v == null) return;
                    String name = ADB_WIFI_KEY.equals(k) ? "무선 디버깅" : "USB 디버깅";
                    post("adb", () -> timeline.add(Timeline.Cat.OBS, name + (v == 1 ? " 켜짐" : " 꺼짐")));
                }
            };
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(ADB_WIFI_KEY), false, adbObs);
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObs);
        } catch (RuntimeException e) {
            adbObs = null;
        }
    }

    private void detachShz() {
        if (shz == null) return;
        if (adbObs != null) {
            try {
                getContentResolver().unregisterContentObserver(adbObs);
            } catch (RuntimeException ignored) {
                // 이미 풀렸으면 무시
            }
            adbObs = null;
        }
        shz.detach();
        shz = null;
        journal.write("shz_detach");
    }

    // ================================================================ Shz.Listener(주 스레드에서 불린다 → 작업 스레드로)

    @Override
    public void linkUp(String hello) {
        post("shz_up", () -> {
            journal.write("shz_up", "host", hello);
            timeline.add(Timeline.Cat.JUDGE, "Shizuku 통로(앱의 도우미) 연결됨 → 앱이 5G/LTE를 바꿀 수 있음");
            if (engine != null && engine.viaShizuku() && !engine.linkAlive()) {
                engine.linkLost(); // 죽은 통로에 붙은 옛 엔진(끊김 알림보다 새 연결이 먼저 옴)
                engine = null;
            }
            startEngineIfWanted();
            if (engine == null) publishIdle();
        });
    }

    @Override
    public void linkDown(String why) {
        post("shz_down", () -> {
            journal.write("shz_down", "why", why);
            timeline.add(Timeline.Cat.JUDGE, "Shizuku 통로 끊김: " + why);
            if (engine != null && engine.viaShizuku()) {
                engine.linkLost(); // 막음 기록은 남긴다 — 통로가 돌아오면 새 엔진이 실제 값을 보고 푼다
                engine = null;
            }
            radio = null;
            // 지금 선택을 다시 본다: 길 1로 바꾸는 끄기를 기다리던 중이었다면 쓸 수 있는 길 1 엔진을 곧바로 시작하고,
            // 길 2 그대로면 Shizuku를 기다린다(외부 검증 지적: 정리만 하고 시작을 빠뜨렸다)
            startEngineIfWanted();
            if (engine == null) publishIdle();
        });
    }

    @Override
    public void shizukuChanged(String text) {
        post("shz", () -> {
            journal.write("shz", "text", text);
            timeline.add(Timeline.Cat.OBS, text);
            if (engine == null) {
                startEngineIfWanted();
                if (engine == null) publishIdle();
            }
        });
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
    public void stopped(Engine which) {
        if (which != engine) {
            // 이미 바뀐 엔진의 늦은 알림(예: 끄기를 마무리하는 사이 통로가 끊겨 새 엔진이 시작됨): 지금 엔진을 건드리지 않는다.
            // 옛 엔진은 이미 멈췄고(halt), 남은 막음은 지금 엔진의 정리가 맡는다(외부 검증 지적: 늦은 알림이 새 엔진을 지워 엔진이 둘 돌았다)
            journal.write("stopped_stale");
            return;
        }
        engine = null;
        // 자동 제어가 꺼져 있어도 부른다: 통로가 바뀐 끄기였으면 여기서 도우미를 붙이거나 뗀다(엔진 시작 여부는 안에서 auto로 본다)
        if (!terminating) startEngineIfWanted();
        if (engine == null) publishIdle();
    }

    /**
     * 엔진이 없을 때의 상태: 사용자 모드와 문제(설정 필요·Shizuku 꺼짐 등). 우리 막음이 남아 있으면 풀기 재시도를 건다.
     * 길 2에서 도우미가 없으면 남은 막음을 풀 통로가 없으므로 "지금 못 바꿈"을 문제로 보인다(통로가 돌아오면 linkUp이 다시 부른다).
     */
    private void publishIdle() {
        Radio carrier = Radio.open(this);
        boolean viaShz = shizukuWay(this);
        Radio r = carrier == null ? null : (viaShz ? link() : carrier);
        String problem = null;
        int mode;
        boolean ours = carrier != null && Engine.ownMask(this, carrier.sub) >= 0;
        if (carrier == null) {
            problem = "SIM 확인 중";
            mode = TileText.MODE_UNKNOWN;
        } else if (r != null && r.privileged()) {
            long u = r.read(Radio.USER);
            mode = u < 0 ? TileText.MODE_UNKNOWN : (CarrierPlan.hasNr(u) ? TileText.MODE_NR : TileText.MODE_LTE);
            if (ours) liftLeftover(r, "idle");
        } else if (viaShz) {
            mode = keyMode();
            problem = (ours ? "5G 막힘 · " : "") + Shz.problem(shz == null ? Shz.State.NOT_RUNNING : shz.state());
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
        timeline.add(Timeline.Cat.ACT, ok ? "남아 있던 5G 막음을 풂(자동 제어 꺼진 뒤 정리)" : "5G 막음 풀기 실패");
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

    /**
     * 부팅 직후 통신사 인정·SIM이 늦게 자리잡는 경우를 위한 한정 재시도(§5.18 전진 설계: Phomeleon식 수십 초 지연 허용).
     * 길 1(통신사) 경로에서 엔진을 못 띄우고 기다리게 됐을 때만 건다. 엔진이 뜨거나 한도(약 1분)를 넘으면 스스로 멈춘다.
     * 작업 스레드에서만 부른다(startEngineIfWanted와 같은 스레드라 recoveryRetry·recoveryTries 접근이 안전).
     */
    private void armRecoveryRetry() {
        if (terminating || recoveryRetry != null || engine != null) return;
        recoveryTries = 0;
        recoveryRetry = worker.scheduleWithFixedDelay(() -> guarded("recovery", this::recoveryTick),
                RECOVERY_RETRY_MS, RECOVERY_RETRY_MS, TimeUnit.MILLISECONDS);
    }

    private void recoveryTick() {
        if (engine != null || terminating || !AppState.auto(this) || shizukuWay(this) || ++recoveryTries > RECOVERY_MAX_TRIES) {
            cancelRecovery();
            return;
        }
        journal.write("recovery_retry", "try", recoveryTries);
        startEngineIfWanted(); // 가능해졌으면 엔진이 뜨고(그 안에서 cancelRecovery), 아니면 다음 tick에서 다시 본다
    }

    private void cancelRecovery() {
        if (recoveryRetry != null) recoveryRetry.cancel(false);
        recoveryRetry = null;
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
                    Radio r = link();
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

    /** 작업 스레드에 넘긴다. 서비스가 끝나는 중이라 넘기지 못하면 false(버튼이 결과를 기다리며 멈춰 있지 않게). */
    private boolean post(String where, Runnable r) {
        if (terminating || worker == null || worker.isShutdown()) return false;
        try {
            worker.execute(() -> guarded(where, r));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
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
        current = null;
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
        // 서비스가 정상 종료되면 폰 꺼짐과 같게 우리 막음을 푼다(쉬는 중이어도). 길 2는 풀고 나서 도우미를 끝낸다
        liftBeforeShutdown();
        if (shzAudit != null) shzAudit.cancel(false);
        // 워커의 cancelRecovery가 이 필드를 null로 만들 수 있으니 한 번만 읽어 쓴다(검사·사용 사이 경합 NPE 방지, f-348f91c2). cancel은 멱등·스레드안전.
        ScheduledFuture<?> rr = recoveryRetry;
        if (rr != null) rr.cancel(false);
        if (adbObs != null) {
            try {
                getContentResolver().unregisterContentObserver(adbObs);
            } catch (RuntimeException ignored) {
                // 이미 풀렸으면 무시
            }
            adbObs = null;
        }
        if (shz != null) shz.detach();
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }
}
