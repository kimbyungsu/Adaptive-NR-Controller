package nrc.shizupoc;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * no-wifi start PoC 관측 전용 하네스 (DESIGN §5.18 '새 후보 탐색 2차'). 제품 코드 아님.
 *
 * 목적: Wi-Fi 없는 cold reboot 뒤, 사용자 조작·PC 없이 Shizuku(사용자 설치 Shizuku-Next가 되살림) binder가
 *   스스로 살아나는지를 '관측만' 한다. 되살아나는 순간·부팅후경과·그때의 네트워크/화면 상태·Shizuku 신분을 기록한다.
 *
 * 구조적 fail-open(중요): 이 클래스에는 전화 설정을 '쓰는' 경로가 **없다**
 *   (setAllowedNetworkTypesForReason / telephony 호출 자체가 없음). 오직 Shizuku.pingBinder 등 읽기만 한다.
 *   따라서 이 하네스가 어떤 경로로 돌든 NR 제한을 남길 수 없다(부트스트랩 실패가 LTE-stuck을 만들지 못함).
 * 경계: Shizuku 권한 요청(requestPermission)·bindUserService도 하지 않는다 — 통로를 건드려 기동을 유발하지 않기 위해.
 *   pingBinder/getUid/getVersion/getSELinuxContext는 binder '생존 여부/신분'만 읽을 뿐 shell 서버를 시작시키지 않는다.
 *
 * 한계(판정 주의, Codex 지적 f-8330ba15): pingBinder 성공은 '통로가 살아 있다'는 **관측 신호**일 뿐,
 *   그 자체로 LocalOnlyHotspot이 쓰였는지·어떤 시작 경로/uid로 떴는지·관측 때문에 떴는지를 구분하지 못한다.
 *   그래서 부팅 횟수·부팅후경과·화면잠금·이 앱 화면을 연 때(ProbeControl이 'event: ui-open'을 남김)·uid/버전을
 *   함께 남겨 '무개입 서버 복구 관찰'로만 한정 판정한다(판정 규칙은 ProbeSteps.judge). 제품 관문 A·A′(§5.17)와는 별개.
 *   10초 간격 기록은 폰이 깊이 잠들면 멈췄다 깨면 이어진다 → 처음 살아난 시각은 '늦어도 그때'(상한)로만 읽는다.
 *   단 binder가 도착하는 순간(OnBinderReceived)에는 그 자리에서 한 줄을 더 남겨 시각을 좁힌다.
 *
 * 기록: filesDir/nowifi_probe.log 에 append(재부팅 뒤 PC adb로 끌어와 대조). 켜기 플래그: filesDir/nowifi_probe.on.
 */
public final class BootProbe extends Service {

    static final String CHANNEL = "nrc_nowifi_probe";
    static final int NOTE_ID = 2001;
    static final String FLAG = "nowifi_probe.on";
    static final String LOG = "nowifi_probe.log";
    static final String ANCHOR = "nowifi_probe.anchor"; // 시험 단위 관측 창 고정(프로세스 재생성에도 유지)
    static final long POLL_MS = 10_000L;            // 10초마다 상태 스냅샷
    static final long MAX_RUN_MS = ProbeSteps.WINDOW_MS; // 30분 시험 창(그 안에 통로가 안 살면 '창 안 미확인'으로 기록·종료)

    /** 이 앱 프로세스 안의 기록 쓰기 직렬화(서비스와 ProbeControl 화면이 같은 파일에 붙여 쓴다). */
    private static final Object LOG_LOCK = new Object();

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> task;
    private long startedElapsed;      // 이 서비스 인스턴스 시작 시각(진단용)
    private long testAnchorElapsed;   // 이 '시험'의 시작 시각(재생성에도 유지) — 30분 창은 이 값 기준
    private int ticks;
    private boolean stopped;          // 작업 줄에서만 읽고 쓴다
    private final AtomicBoolean firstAlive = new AtomicBoolean(false);

    /**
     * binder 도착 순간을 집는 sticky 리스너(이미 와 있으면 즉시 1회 발화). 통로를 켜지 않는다(관측용).
     * 도착 즉시 같은 작업 줄에서 한 번 더 기록해(tick) 10초 간격·깊은 잠 때문에 생기는 시각 오차를 줄인다.
     */
    private final Shizuku.OnBinderReceivedListener recvL = () -> {
        append("event: binderReceived boot=" + bootCount(this) + " sinceBootMs=" + SystemClock.elapsedRealtime()
                + " " + identity());
        ScheduledExecutorService e = exec;
        if (e != null) {
            try {
                e.execute(this::tick);
            } catch (Throwable ignored) {
            }
        }
    };

    /** 켜기 플래그 — BootReceiver가 부팅 시 이 값이 있을 때만 하네스를 띄운다(평상시 비동작). */
    static boolean enabled(Context c) {
        return new File(c.getFilesDir(), FLAG).exists();
    }

    /** 이 폰이 켜진 횟수(안드로이드가 부팅마다 올림). 기록의 '어느 부팅' 구분에 쓴다. 못 읽으면 -1. */
    static int bootCount(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 시작 전용 서비스
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String cause = intent == null ? "restart"
                : (intent.getStringExtra("cause") != null ? intent.getStringExtra("cause") : "manual");
        startForegroundSafe("재부팅 뒤 무선 없이 통로가 되살아나는지만 기록해요. 전화 설정은 바꾸지 않아요.");
        if (exec == null) {
            startedElapsed = SystemClock.elapsedRealtime();
            int boot = bootCount(this);
            // 손으로 켠 시작은 새 시험 창, 부팅·재생성은 같은 부팅의 저장 앵커를 이어 쓴다(재부팅이면 새 앵커)
            testAnchorElapsed = loadOrInitAnchor(boot, startedElapsed, "manual".equals(cause));
            File logDir = getFilesDir();
            append("=== probe start cause=" + cause + " boot=" + boot + " sinceBootMs=" + startedElapsed
                    + " testAnchorMs=" + testAnchorElapsed
                    + " model=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + "  (cause=이 시작 요청의 출처일 뿐 — 이후 Activity 개방·사용자 무개입을 뜻하지 않음."
                    + " 측정범위=첫 잠금해제 이후: BOOT_COMPLETED가 그때 전달되고 Shizuku 통로도 첫 잠금해제 게이트, §5.18:935)");
            fsyncDir(logDir); // 첫 기록(파일 생성)을 디스크까지 — 전원 차단 대비
            exec = Executors.newSingleThreadScheduledExecutor();
            try {
                Shizuku.addBinderReceivedListenerSticky(recvL);
            } catch (Throwable t) {
                append("addBinderReceivedListener 실패(무시): " + t);
            }
            task = exec.scheduleWithFixedDelay(this::tick, 0, POLL_MS, TimeUnit.MILLISECONDS);
        } else {
            // 이미 실행 중(ProbeControl 재탭·시스템 재시작 등) — 시작 원인을 정직히 남긴다(관측 창은 testAnchor 기준 유지).
            append("=== probe re-start cause=" + cause + " boot=" + bootCount(this) + " (서비스 이미 실행 중)");
        }
        return START_STICKY;
    }

    /** 작업 줄(단일 스레드)에서만 부른다. */
    private void tick() {
        if (stopped) return; // 창 종료 뒤 onDestroy 전까지 남은 예약 실행은 아무것도 안 남긴다
        try {
            long since = SystemClock.elapsedRealtime();
            if (since - testAnchorElapsed > MAX_RUN_MS) {
                stopped = true;
                // 창 검사를 생존 기록보다 먼저 한다 — 창이 지난 뒤 처음 살아난 것을 성공 알림·FIRST_ALIVE로 남기지 않게(Codex 지적)
                append("=== probe stop (max run reached) firstAlive=" + firstAlive.get());
                if (!firstAlive.get()) startForegroundSafe("시험 창(30분) 안에서 통로가 살아난 걸 확인하지 못했어요. 판정은 앱 화면에서.");
                clearAnchor(this);
                stopSelf();
                return;
            }
            boolean alive = pingSafe();
            StringBuilder sb = new StringBuilder();
            sb.append("tick#").append(++ticks)
                    .append(" boot=").append(bootCount(this))
                    .append(" sinceBootMs=").append(since)
                    .append(" sinceTestMs=").append(since - testAnchorElapsed)
                    .append(" ping=").append(alive)
                    .append(" wifiPresent=").append(wifiPresent())
                    .append(" wifiOn=").append(wifiSwitch())
                    .append(" cellPresent=").append(cellularPresent())
                    .append(" locked=").append(keyguardLocked())
                    .append(" interactive=").append(interactive());
            boolean first = alive && firstAlive.compareAndSet(false, true);
            if (first) sb.append("  >>> FIRST_ALIVE ").append(identity());
            append(sb.toString());
            if (first) {
                startForegroundSafe("통로가 살아난 게 보였어요(부팅 후 늦어도 " + (since / 1000) + "초). 판정은 앱 화면에서.");
            }
        } catch (Throwable t) {
            append("tick error: " + t);
        }
    }

    /** Shizuku 신분(통로가 살아 있을 때만 의미 있음). 읽기 실패는 통로 미생존/권한 무관이므로 조용히 표식만. */
    private static String identity() {
        try {
            if (!Shizuku.pingBinder()) return "(binder dead)";
            return "uid=" + Shizuku.getUid() + " shizukuVer=" + Shizuku.getVersion()
                    + " preV11=" + Shizuku.isPreV11() + " selinux=" + safeSelinux();
        } catch (Throwable t) {
            return "(identity read fail: " + t.getClass().getSimpleName() + ")";
        }
    }

    private static boolean pingSafe() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String safeSelinux() {
        try {
            return Shizuku.getSELinuxContext();
        } catch (Throwable t) {
            return "?";
        }
    }

    private String wifiPresent() {
        return transportPresent(this, NetworkCapabilities.TRANSPORT_WIFI);
    }

    private String cellularPresent() {
        return transportPresent(this, NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    /** Wi-Fi 스위치 상태(연결과 별개, 참고용): on/off/unknown. */
    private String wifiSwitch() {
        Boolean on = wifiSwitchOn(this);
        return on == null ? "unknown" : on ? "on" : "off";
    }

    static Boolean wifiSwitchOn(Context c) {
        try {
            WifiManager wm = c.getSystemService(WifiManager.class);
            return wm == null ? null : wm.isWifiEnabled();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 모든 네트워크를 훑어 그 transport가 하나라도 '있는지'를 3값(yes/no/unknown)으로 본다.
     * no-wifi 관문 오판 방지(Codex f03a73fa): 기본 경로가 모바일 데이터여도 별도 Wi-Fi가 붙어 있으면 yes.
     * INTERNET capability는 요구하지 않는다(통로가 '있는지'만 본다 — 외부 Wi-Fi 이용 복구를 no-wifi 성공으로 오독 방지).
     * 조회 불가(cm/null)·예외는 unknown으로 — '부재(no)'와 명확히 구분한다. 네트워크는 있는데 그 성질을 못 읽은 것이
     * 하나라도 있고 해당 transport를 못 찾았으면 역시 unknown(못 읽은 그게 Wi-Fi였을 수 있음).
     */
    static String transportPresent(Context c, int transport) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm == null) return "unknown";
            Network[] nets = cm.getAllNetworks();
            if (nets == null) return "unknown";
            boolean unread = false;
            for (Network n : nets) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null) unread = true;
                else if (caps.hasTransport(transport)) return "yes";
            }
            return unread ? "unknown" : "no";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ---- 시험 단위 관측 창 앵커(프로세스 재생성에도 30분 창 유지, 재부팅은 부팅 횟수로 감지) ----

    /**
     * 이 '시험'의 시작 elapsed를 파일에 "부팅횟수 elapsed"로 고정해, START_STICKY 재생성에도 같은 창을 쓰게 한다(Codex dd276f30).
     * 저장된 부팅 횟수가 지금과 같고(못 읽는 폰이면 저장값 ≤ 지금 elapsed) 손으로 새로 켠 것이 아니면 유지,
     * 아니면(재부팅·손으로 켬·파일 없음/손상) 새 앵커를 쓴다. elapsed만으로는 짧게 켜졌던 앞 부팅과 새 부팅을 못 가른다.
     */
    private long loadOrInitAnchor(int boot, long nowElapsed, boolean fresh) {
        File a = new File(getFilesDir(), ANCHOR);
        if (!fresh) {
            try {
                if (a.exists()) {
                    String[] p = new String(readAllBytes(a), StandardCharsets.UTF_8).trim().split(" ");
                    int storedBoot = Integer.parseInt(p[0]);
                    long stored = Long.parseLong(p[1]);
                    boolean sameBoot = boot >= 0 ? storedBoot == boot : storedBoot < 0;
                    if (sameBoot && stored >= 0 && stored <= nowElapsed) return stored; // 같은 부팅 → 기존 앵커 유지
                }
            } catch (Throwable ignored) {
            }
        }
        writeAnchor(a, boot + " " + nowElapsed); // 앵커 없음/손상/재부팅/손으로 켬 → 새로 고정
        return nowElapsed;
    }

    private static void writeAnchor(File a, String v) {
        try (FileOutputStream fos = new FileOutputStream(a, false)) {
            fos.write(v.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    static void clearAnchor(Context c) {
        try {
            new File(c.getFilesDir(), ANCHOR).delete();
        } catch (Throwable ignored) {
        }
    }

    private static byte[] readAllBytes(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private boolean keyguardLocked() {
        try {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean interactive() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 포그라운드 알림을 띄우거나 글을 바꾼다(같은 번호로 다시 부르면 글만 바뀜). 사용자가 앱을 안 열고 알림창에서 결과를 본다. */
    private void startForegroundSafe(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(
                        new NotificationChannel(CHANNEL, "no-wifi 관측 하네스", NotificationManager.IMPORTANCE_LOW));
            }
            Notification note = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("Shizuku 통로 관측 중(시험)")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    .build();
            startForeground(NOTE_ID, note);
        } catch (Throwable t) {
            append("startForeground 실패: " + t);
        }
    }

    private void append(String line) {
        appendLine(this, line);
    }

    /** nowifi_probe.log 에 한 줄 append(flush + fd.sync로 내구적). 서비스·화면 공용. */
    static void appendLine(Context c, String line) {
        synchronized (LOG_LOCK) {
            String out = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + line + "\n";
            android.util.Log.i("NRNOWIFI", line);
            File f = new File(c.getFilesDir(), LOG);
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(out.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            } catch (Throwable ignored) {
            }
        }
    }

    private void fsyncDir(File dir) {
        FileDescriptor fd = null;
        try {
            fd = Os.open(dir.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            Os.fsync(fd);
        } catch (Throwable ignored) {
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeBinderReceivedListener(recvL);
        } catch (Throwable ignored) {
        }
        ScheduledFuture<?> t = task;
        if (t != null) t.cancel(false);
        ScheduledExecutorService e = exec;
        if (e != null) e.shutdownNow();
        append("=== probe onDestroy boot=" + bootCount(this) + " firstAlive=" + firstAlive.get());
    }
}
