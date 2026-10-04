package nrc.controller;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

/**
 * Shizuku 통로(길 2, DESIGN §5.15·§5.16)의 앱 쪽 관리자. 프로세스에 하나(get).
 * - Shizuku가 켜짐·꺼짐, 이 앱 사용 승인 결과를 듣는다.
 * - 승인돼 있으면 Shizuku에 우리 도우미(HostService)를 shell 신분 프로세스로 띄워 달라고 한다(bindUserService).
 *   도우미가 붙으면 Radio(길 2)를 만들 수 있다. 끊기면 언제·어떤 상황이었는지와 함께 Listener에 알리고, Shizuku가 살아 있으면 다시 붙인다.
 * - Shizuku 서버가 꺼져도 이미 띄운 도우미가 살아 있으면 그대로 쓴다(진짜 끝은 도우미의 죽음 알림으로만 판단).
 * 경계(self-bootstrap 금지): Shizuku를 켜거나 무선 디버깅을 켜지 않는다. 사용자가 설치·시작·승인한 Shizuku를 쓸 뿐이다.
 * 상태 필드는 주 스레드에서 바꾼다. Listener는 주 스레드에서 불린다(받는 쪽이 작업 스레드로 넘긴다).
 */
final class Shz {
    interface Listener {
        /** 도우미가 붙었다(제어 시작 가능). hello = 도우미 신분 한 줄. */
        void linkUp(String hello);

        /** 도우미가 끊겼다. why = 원인과 그때 상황(사람이 읽는 문장). */
        void linkDown(String why);

        /** Shizuku가 켜졌거나 꺼졌거나 승인이 바뀌었다. text = 활동 기록 문장. */
        void shizukuChanged(String text);
    }

    /** Shizuku 자체와 이 앱 승인의 상태(§5.16 길 2 상태와 같은 구분). */
    enum State { ABSENT, NOT_RUNNING, OLD, NO_APPROVAL, DENIED, READY }

    static final String MANAGER = ShizukuProvider.MANAGER_APPLICATION_ID;
    static final int REQ = 7301;
    private static final long BIND_TIMEOUT_MS = 20_000;
    private static final long CAUSE_SETTLE_MS = 600;
    private static final long[] RETRY_MS = {2_000, 5_000, 15_000, 30_000, 60_000};

    private static Shz instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 도우미가 이 앱 프로세스의 죽음을 알아채는 표지(앱이 죽으면 도우미도 스스로 끝난다). */
    private final Binder token = new Binder();
    private Listener listener;
    private boolean attached;
    private boolean bindRequested;
    private long bindAt;
    private int retryStep;
    private Shizuku.UserServiceArgs args;
    private volatile IHost host;
    private volatile long upSinceWall = -1;
    private volatile String lastLoss;
    private volatile String lastShizuku;

    static synchronized Shz get(Context c) {
        if (instance == null) instance = new Shz(c.getApplicationContext());
        return instance;
    }

    private Shz(Context app) {
        this.app = app;
    }

    // ================================================================ 시작·끝(어느 스레드에서나)

    void attach(Listener l) {
        main.post(() -> {
            listener = l;
            if (!attached) {
                attached = true;
                Shizuku.addBinderReceivedListenerSticky(onUp, main);
                Shizuku.addBinderDeadListener(onDown, main);
                Shizuku.addRequestPermissionResultListener(onPerm, main);
            }
            ensure();
        });
    }

    /** 서비스가 끝난다: 듣기를 멈추고 도우미를 끝낸다(남은 프로세스 없음). */
    void detach() {
        main.post(() -> {
            listener = null;
            if (attached) {
                attached = false;
                Shizuku.removeBinderReceivedListener(onUp);
                Shizuku.removeBinderDeadListener(onDown);
                Shizuku.removeRequestPermissionResultListener(onPerm);
            }
            dropHost();
        });
    }

    /**
     * 1분마다 서비스가 부른다: 응답 없는 붙이기 요청을 풀고 다시 시도한다. 승인이 거절 상태로 보이면 도우미를 끝낸다.
     * 주의(외부 검증 지적): Shizuku 라이브러리는 한 번 승인된 결과를 기억해 다시 묻지 않으므로, 이 확인이 '승인 취소'를 잡는다고
     * 보장하지 않는다. 승인 취소의 실제 정리는 Shizuku 서버가 한다(공식 13.6.0: 취소 시 앱 강제 종료·도우미 제거) — 그러면 앱 토큰이
     * 죽어 도우미도 스스로 끝난다(HostService.hello). 거절 알림(사용자가 창에서 거부)은 onPerm이 곧바로 처리한다.
     */
    void audit() {
        main.post(() -> {
            State s = state();
            if (hostAlive() && (s == State.NO_APPROVAL || s == State.DENIED)) {
                dropHost();
                lostNow("Shizuku 승인이 취소돼 도우미를 끝냄");
                return;
            }
            if (bindRequested && SystemClock.elapsedRealtime() - bindAt > BIND_TIMEOUT_MS) {
                bindRequested = false; // 응답 없는 붙이기 요청
            }
            ensure();
        });
    }

    // ================================================================ 읽기(어느 스레드에서나)

    State state() {
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable t) {
            alive = false;
        }
        if (!alive) return installed() ? State.NOT_RUNNING : State.ABSENT;
        try {
            if (Shizuku.isPreV11()) return State.OLD;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return State.READY;
            return Shizuku.shouldShowRequestPermissionRationale() ? State.DENIED : State.NO_APPROVAL;
        } catch (Throwable t) {
            return State.NOT_RUNNING;
        }
    }

    boolean hostAlive() {
        IHost h = host;
        try {
            return h != null && h.asBinder().pingBinder();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 도우미가 붙어 있으면 길 2 Radio, 아니면 null. */
    Radio radio() {
        IHost h = host;
        return h != null && hostAlive() ? Radio.shizuku(h) : null;
    }

    /** 도우미가 붙은 때(벽시계, 없으면 -1). */
    long upSinceWall() {
        return upSinceWall;
    }

    /** 마지막 끊김 문장(시각·원인·그때 상황, 없으면 null). */
    String lastLoss() {
        return lastLoss;
    }

    /** 마지막 Shizuku 켜짐·꺼짐 문장(없으면 null). */
    String lastShizuku() {
        return lastShizuku;
    }

    boolean installed() {
        try {
            app.getPackageManager().getPackageInfo(MANAGER, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Shizuku의 '부팅 시 시작'이 켜져 있는지: Shizuku 앱의 부팅 완료 수신기가 켜져 있는지로 본다(수신기 이름을 정해 두지 않고 찾는다).
     * 켜짐 true · 꺼짐 false · 모름 null. 재부팅 뒤 스스로 켜지려면 이것 말고도 Wi-Fi 조건이 필요하다(화면 안내).
     */
    Boolean bootStart() {
        try {
            PackageManager pm = app.getPackageManager();
            List<ResolveInfo> rs = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(MANAGER),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            if (rs == null || rs.isEmpty()) return null;
            for (ResolveInfo r : rs) {
                int st = pm.getComponentEnabledSetting(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
                boolean on = st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        || (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && r.activityInfo.enabled);
                if (on) return true;
            }
            return false;
        } catch (Exception e) {
            return null;
        }
    }

    /** Shizuku 앱을 여는 Intent(없으면 null). */
    Intent managerIntent() {
        try {
            Intent i = app.getPackageManager().getLaunchIntentForPackage(MANAGER);
            if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    /** 이 앱 사용 승인을 요청한다(Shizuku가 떠 있을 때만 창이 뜬다). 뜨면 true. */
    boolean requestPermission() {
        try {
            if (!Shizuku.pingBinder()) return false;
            Shizuku.requestPermission(REQ);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 끊긴 그때 상황(진단용, 개인정보 없음): Shizuku·무선/USB 디버깅·Wi-Fi·화면·폰 켜진 지. */
    String situation() {
        List<String> s = new ArrayList<>();
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable t) {
            alive = false;
        }
        s.add(alive ? "Shizuku 켜져 있음" : "Shizuku 꺼져 있음");
        Integer wd = global("adb_wifi_enabled");
        if (wd != null) s.add(wd == 1 ? "무선 디버깅 켜짐" : "무선 디버깅 꺼짐");
        Integer ud = global(Settings.Global.ADB_ENABLED);
        if (ud != null) s.add(ud == 1 ? "USB 디버깅 켜짐" : "USB 디버깅 꺼짐");
        s.add(wifiNow() ? "Wi-Fi 연결됨" : "Wi-Fi 연결 안 됨");
        PowerManager pm = app.getSystemService(PowerManager.class);
        if (pm != null) s.add(pm.isInteractive() ? "화면 켜짐" : "화면 꺼짐");
        s.add("폰 켜진 지 " + dur(SystemClock.elapsedRealtime()));
        return String.join(" · ", s);
    }

    /** 전역 설정 숫자 값(못 읽으면 null — 안드로이드가 앱에 안 보여 주는 값일 수 있다). */
    Integer global(String key) {
        try {
            String v = Settings.Global.getString(app.getContentResolver(), key);
            return v == null ? null : Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean wifiNow() {
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (RuntimeException ignored) {
            // 모르면 연결 안 됨으로
        }
        return false;
    }

    /** 타일·화면에 보일 짧은 문제 말(길 2인데 도우미가 없을 때). */
    static String problem(State s) {
        switch (s) {
            case ABSENT:
                return "Shizuku 설치 필요";
            case NOT_RUNNING:
                return "Shizuku 꺼짐";
            case OLD:
                return "Shizuku 업데이트 필요";
            case NO_APPROVAL:
            case DENIED:
                return "Shizuku 승인 필요";
            default:
                return "Shizuku 연결 중";
        }
    }

    /** 활동 기록에 남길 '왜 아직 제어를 못 하는지'와 사용자가 할 일. */
    static String waitText(State s) {
        switch (s) {
            case ABSENT:
                return "Shizuku 앱이 없어 기다리는 중(Shizuku 설치 필요)";
            case NOT_RUNNING:
                return "Shizuku가 꺼져 있어 기다리는 중(Shizuku 앱에서 시작하면 앱이 이어서 함)";
            case OLD:
                return "Shizuku가 오래된 버전이라 기다리는 중(Shizuku 업데이트 필요)";
            case NO_APPROVAL:
                return "이 앱의 Shizuku 사용 승인을 기다리는 중(앱 화면의 [Shizuku 사용 승인 요청])";
            case DENIED:
                return "Shizuku 사용이 거절돼 있어 기다리는 중(Shizuku 앱에서 이 앱을 허용해야 함)";
            default:
                return "Shizuku 도우미를 붙이는 중";
        }
    }

    static String dur(long ms) {
        long m = ms / 60_000;
        if (m < 1) return (ms / 1000) + "초";
        if (m < 60) return m + "분 " + (ms / 1000 % 60) + "초";
        return (m / 60) + "시간 " + (m % 60) + "분";
    }

    // ================================================================ 붙이기·끊김(주 스레드)

    private final Shizuku.OnBinderReceivedListener onUp = () -> {
        String text = "Shizuku 켜져 있음 확인(폰 켜진 지 " + dur(SystemClock.elapsedRealtime()) + ")";
        lastShizuku = NowText.clock(System.currentTimeMillis()) + " " + text;
        if (listener != null) listener.shizukuChanged(text);
        retryStep = 0;
        ensure();
    };

    private final Shizuku.OnBinderDeadListener onDown = () -> {
        String text = "Shizuku 꺼짐" + (hostAlive() ? "(앱의 도우미는 아직 살아 있어 제어는 계속됨)" : "") + " · 그때: " + situation();
        lastShizuku = NowText.clock(System.currentTimeMillis()) + " " + text;
        if (listener != null) listener.shizukuChanged(text);
    };

    private final Shizuku.OnRequestPermissionResultListener onPerm = (code, result) -> {
        boolean ok = result == PackageManager.PERMISSION_GRANTED;
        if (listener != null) listener.shizukuChanged(ok ? "Shizuku 사용을 허락받음" : "Shizuku 사용이 거절됨");
        if (ok) {
            ensure();
        } else if (hostAlive()) {
            dropHost();
            lostNow("Shizuku 승인이 거절돼 도우미를 끝냄");
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            main.post(() -> connected(binder));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Shizuku 쪽 연결 알림이 끊겨도 도우미가 실제로 살아 있으면 계속 쓴다(진짜 끝은 죽음 알림)
            main.post(() -> {
                if (hostAlive()) return;
                if (host != null) {
                    host = null;
                    reportLoss();
                } else {
                    bindRequested = false;
                    scheduleRetry();
                }
            });
        }
    };

    private void ensure() {
        if (hostAlive() || bindRequested || !attached) return;
        if (state() != State.READY) return;
        try {
            Shizuku.bindUserService(args(), conn);
            bindRequested = true;
            bindAt = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            bindRequested = false;
            scheduleRetry();
        }
    }

    private void connected(IBinder binder) {
        bindRequested = false;
        IHost cur = host;
        if (cur != null && binder != null && cur.asBinder() == binder) {
            return; // 같은 도우미의 다시 알림: 지금 연결 그대로(대리 객체가 새로 만들어져도 binder가 같으면 같은 도우미 — 외부 검증 지적)
        }
        if (!attached) {
            // 떼어 낸 뒤 늦게 도착한 연결: 채택하지 않고 끝낸다(남은 프로세스 없음)
            if (binder != null) {
                try {
                    IHost.Stub.asInterface(binder).destroy();
                } catch (RemoteException | RuntimeException ignored) {
                    // 이미 끝났으면 그만
                }
            }
            return;
        }
        if (binder == null || !binder.pingBinder()) {
            scheduleRetry();
            return;
        }
        IHost h = IHost.Stub.asInterface(binder);
        String hello;
        try {
            hello = h.hello(token);
            binder.linkToDeath(() -> main.post(() -> {
                IHost now = host;
                if (now == null || now.asBinder() != binder) return; // 이미 정리했거나 다른 도우미
                host = null; // 바로 쓰지 않게 하고, 원인은 Shizuku 쪽 알림이 도착할 틈을 준 뒤 판단한다
                main.postDelayed(() -> {
                    if (host == null) reportLoss(); // 그 사이 새 도우미가 붙었으면 서비스가 새 연결 알림에서 정리한다
                }, CAUSE_SETTLE_MS);
            }), 0);
        } catch (RemoteException | RuntimeException e) {
            scheduleRetry();
            return;
        }
        if (hostAlive()) {
            // 이미 살아 있는 다른 binder의 도우미가 붙어 있다(겹친 요청): 새 것은 쓰지 않는다(같은 binder는 위에서 걸렀다)
            try {
                h.destroy();
            } catch (RemoteException | RuntimeException ignored) {
                // 끝났으면 그만
            }
            return;
        }
        host = h;
        upSinceWall = System.currentTimeMillis();
        retryStep = 0;
        if (listener != null) listener.linkUp(hello);
    }

    /** 도우미가 끝났다: 원인과 그때 상황을 남기고 알린 뒤, Shizuku가 살아 있으면 다시 붙인다. */
    private void reportLoss() {
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable t) {
            alive = false;
        }
        lostNow(alive ? "도우미 프로세스만 끝남(Shizuku는 켜져 있음 → 다시 붙임)" : "Shizuku가 꺼지면서 도우미도 끝남");
    }

    private void lostNow(String why) {
        upSinceWall = -1;
        String text = why + " · 그때: " + situation();
        lastLoss = NowText.clock(System.currentTimeMillis()) + " " + text;
        if (listener != null) listener.linkDown(text);
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (!attached) return;
        long d = RETRY_MS[Math.min(retryStep, RETRY_MS.length - 1)];
        retryStep++;
        main.postDelayed(this::ensure, d);
    }

    /** 도우미를 끝낸다(Shizuku가 살아 있으면 정식 해제, 아니면 도우미에 직접 종료 요청). */
    private void dropHost() {
        IHost h = host;
        host = null;
        upSinceWall = -1;
        bindRequested = false;
        try {
            if (Shizuku.pingBinder()) Shizuku.unbindUserService(args(), conn, true);
        } catch (Throwable ignored) {
            // 아래 직접 종료로
        }
        if (h != null) {
            try {
                if (h.asBinder().pingBinder()) h.destroy();
            } catch (RemoteException | RuntimeException ignored) {
                // 이미 끝났으면 그만
            }
        }
    }

    private Shizuku.UserServiceArgs args() {
        if (args == null) {
            int ver = 1;
            try {
                // 설치할 때마다 바뀌는 값: 앱을 새로 깔면 Shizuku가 옛 도우미 대신 새 도우미를 띄운다
                ver = (int) (app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime / 1000 % Integer.MAX_VALUE);
            } catch (Exception ignored) {
                // 못 읽으면 1
            }
            args = new Shizuku.UserServiceArgs(new ComponentName(app.getPackageName(), HostService.class.getName()))
                    .daemon(false).processNameSuffix("host").debuggable(false).version(ver).tag("nrchost");
        }
        return args;
    }
}
