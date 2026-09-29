package nrc;

import android.os.Build;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * nrd — Adaptive NR Controller 폰 데몬.
 * 실행: CLASSPATH=<dex> app_process /system/bin nrc.Nrd --observe
 *       CLASSPATH=<dex> app_process /system/bin nrc.Nrd --control [--leftover=restore|keep] [--original=nr|lte]
 *                                                        [--indicator-slot=<상단바 자리>]
 * --observe: 기록만(설정 쓰기 없음). --control: 5G 우선 모드일 때 불안정하면 LTE로 쉬었다가 재시험(DESIGN §5.5).
 */
public final class Nrd {
    static final String VERSION = "0.2.0";
    static final File HOME = new File("/data/local/tmp/nrc");
    static final File PID = new File(HOME, "nrd.pid");
    /** 프로세스 수명 동안 잡고 있는 배타 잠금. 두 번째 기동은 잠금을 못 얻어 곧바로 끝난다. */
    static final File LOCK = new File(HOME, "nrd.lock");
    /** PC가 대상 pid를 적어 쓰면 기록을 마무리하고(제어 모드는 원래 모드로 되돌리고) 스스로 종료한다. */
    static final String STOP_REQ = "stop.req";
    /** PC 명령: "<pid> <pause|resume|keep-lte>". */
    static final String CTL_REQ = "ctl.req";
    static final long TICK_SEC = 30;
    /** 종료 요청 처리(되돌리기 쓰기 포함)를 기다리는 시간. 넘으면 작업 스레드가 멈춘 것으로 보고 스스로 끝낸다. */
    static final long STOP_WAIT_SEC = 20;
    /** 레퍼런스 기기에서 확인한 빈 자리(두 번째 SIM의 VoLTE 자리, research §2.7). 기기마다 다를 수 있어 인자로 바꿀 수 있다. */
    static final String DEFAULT_INDICATOR_SLOT = "ims_volte2";

    private static volatile boolean phoneLinked;
    // GC로 풀리지 않게 정적 참조로 잡아 둔다
    private static FileLock lock;
    private static FileObserver requestWatch;

    public static void main(String[] args) {
        if (args.length == 1 && "--query".equals(args[0])) {
            query();
            return;
        }
        Map<String, String> opts = parseArgs(args);
        if (opts == null) {
            System.err.println("usage: nrc.Nrd --observe | --control [--leftover=restore|keep] [--original=nr|lte]"
                    + " [--indicator-slot=NAME] [--indicator-icon=PKG:ID] | --query");
            System.exit(2);
        }
        boolean control = opts.containsKey("control");
        HOME.mkdirs();
        lock = tryLock();
        if (lock == null) {
            System.err.println("nrd already running");
            System.exit(3);
        }
        new File(HOME, STOP_REQ).delete(); // 이전 실행이 남긴 요청은 무시한다
        new File(HOME, CTL_REQ).delete();
        if (!writePid()) {
            System.err.println("cannot write " + PID);
            System.exit(5); // PC 도구가 이 데몬을 찾을 수 없게 되므로 돌지 않는다
        }

        Journal log = new Journal(new File(HOME, "logs"));
        Thread.setDefaultUncaughtExceptionHandler((th, e) -> {
            log.write("fatal", "thread", th.getName(), "msg", String.valueOf(e), "pid", android.os.Process.myPid());
            System.exit(1);
        });
        try {
            Looper.prepareMainLooper();
            ShellContext ctx = ShellContext.create();
            int sub = Phone.defaultDataSubId();
            int slot = Phone.slotIndex(sub);
            int activeSubs = Phone.activeSubCount(ctx);
            log.write("start", "version", VERSION, "schema", Journal.SCHEMA, "mode", control ? "control" : "observe",
                    "sdk", Build.VERSION.SDK_INT, "model", Build.MODEL, "incremental", Build.VERSION.INCREMENTAL,
                    "sub", sub, "slot", slot, "activeSubs", activeSubs, "pid", android.os.Process.myPid(),
                    "elapsedMs", SystemClock.elapsedRealtime());

            // 알림 처리·주기 확인·정책·쓰기를 한 스레드에서 돌려 상태를 잠금 없이 다룬다.
            // 작업 안의 예외는 기록만 한다(기본 처리기는 프로세스를 죽인다).
            ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
            Executor safe = task -> worker.execute(() -> runSafely(log, "executor", task));

            Controller controller = null;
            if (control) {
                try {
                    String[] icon = parseIcon(opts.get("indicator-icon"));
                    controller = new Controller(log, ctx, sub, slot, activeSubs, worker, opts,
                            opts.getOrDefault("indicator-slot", DEFAULT_INDICATOR_SLOT),
                            icon == null ? null : icon[0], icon == null ? 0 : Integer.decode(icon[1]));
                } catch (Controller.StartError e) {
                    log.write("start_refused", "code", e.code, "msg", e.getMessage(), "pid", android.os.Process.myPid());
                    System.err.println(e.getMessage());
                    removePidIfMine();
                    System.exit(e.code);
                }
            }
            TelephonyManager tm = Phone.manager(ctx, sub);
            Observer observer = register(log, tm, sub, safe, controller != null ? controller : Observer.NONE);
            final Controller ctl = controller;
            if (ctl != null) {
                ctl.onStopDone(() -> {
                    observer.shutdown("request");
                    finishAndHalt();
                });
                worker.execute(() -> runSafely(log, "begin", ctl::begin));
            }
            worker.scheduleWithFixedDelay(() -> runSafely(log, "tick", () -> {
                observer.tick();
                if (!phoneLinked) phoneLinked = linkPhoneDeath(log);
            }), TICK_SEC, TICK_SEC, TimeUnit.SECONDS);

            watchRequests(log, worker, observer, ctl);

            // 기본 인터넷 경로 감시: Wi-Fi로 인터넷을 쓰는 동안 컨트롤러는 쉬고 상단바 점을 숨긴다(관찰 모드는 기록만)
            final String[] lastDesc = {""};
            boolean netOk = NetWatch.start(ctx, (wifi, desc) -> worker.execute(() -> runSafely(log, "net", () -> {
                if (!desc.equals(lastDesc[0])) log.write("net", "default", desc, "wifi", wifi);
                lastDesc[0] = desc;
                if (ctl != null) ctl.onDefaultNetwork(SystemClock.elapsedRealtime(), wifi);
            })));
            if (!netOk) {
                log.write("warn", "what", "default_network_watch_unavailable");
                // Wi-Fi 여부를 모르면 Wi-Fi 중 점 숨김·되돌리기를 지킬 수 없다 → 셀룰러로 가정하지 않고 관찰만 한다
                if (ctl != null) worker.execute(() -> runSafely(log, "net", ctl::wifiWatchUnavailable));
            }

            // 알림 등록은 system_server의 레지스트리에 있다. 레지스트리가 죽으면 구독이 끊기므로 기록하고 끝낸다
            // (조용히 살아 있으면 "사건 없음"으로 오독된다). 전화 프로세스 재시작은 구독과 무관해 기록만 한다.
            Phone.service("telephony.registry").linkToDeath(() -> {
                log.write("fatal", "msg", "telephony.registry died", "pid", android.os.Process.myPid());
                System.exit(4);
            }, 0);
            phoneLinked = linkPhoneDeath(log);
            Looper.loop();
        } catch (Throwable e) {
            log.write("fatal", "msg", String.valueOf(e), "pid", android.os.Process.myPid());
            System.exit(1);
        }
    }

    /**
     * PC 요청 파일 감시. 파일에 적힌 pid가 이 프로세스일 때만 받는다(다른 실행을 겨냥한 늦은 요청은 무시).
     * 종료: 작업 스레드가 정리(제어 모드는 원래 모드 되돌리기)하고 끝낸다. 통화 중이면 되돌리기를 미루고 계속 산다.
     * 작업 스레드가 STOP_WAIT_SEC 안에 응답하지 않으면(멈춤) 기록 없이 스스로 끝낸다. PC는 프로세스 번호로 신호를 보내지 않는다.
     */
    private static void watchRequests(Journal log, ScheduledExecutorService worker, Observer observer, Controller ctl) {
        requestWatch = new FileObserver(HOME, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (CTL_REQ.equals(path)) {
                    String[] req = readRequest(CTL_REQ);
                    if (req == null || req.length < 2) return;
                    new File(HOME, CTL_REQ).delete();
                    if (ctl == null) {
                        log.write("warn", "what", "command_in_observe_mode", "verb", req[1]);
                        return;
                    }
                    worker.execute(() -> runSafely(log, "command", () -> ctl.command(req[1])));
                    return;
                }
                if (!STOP_REQ.equals(path) || readRequest(STOP_REQ) == null) return;
                CountDownLatch accepted = new CountDownLatch(1);
                worker.execute(() -> {
                    if (ctl != null) {
                        runSafely(log, "stop", ctl::requestStop); // 끝나면 onStopDone → 종료, 통화 중이면 미룸
                    } else {
                        runSafely(log, "stop", () -> observer.shutdown("request"));
                        finishAndHalt();
                    }
                    accepted.countDown();
                });
                boolean ok = false;
                try {
                    ok = accepted.await(STOP_WAIT_SEC, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 아래에서 끝낸다
                }
                if (!ok) finishAndHalt(); // 작업 스레드 멈춤: 기록 없이 끝낸다(상태 파일은 비정상 종료로 남는다)
                new File(HOME, STOP_REQ).delete(); // 미뤄진 종료: 계속 살고 요청 파일만 정리
            }
        };
        requestWatch.startWatching();
    }

    private static void finishAndHalt() {
        removePidIfMine();
        new File(HOME, STOP_REQ).delete();
        Runtime.getRuntime().halt(0);
    }

    /**
     * 읽기 전용 조회(nrctl restore의 재확인용): 기본 데이터 SIM의 USER 허용 타입을 마스크 숫자로 출력한다.
     * 잠금·pid 파일·기록을 건드리지 않는다. 출력: "user=<마스크> sub=<subId> slot=<슬롯>".
     */
    private static void query() {
        try {
            int sub = Phone.defaultDataSubId();
            System.out.println("user=" + Phone.allowedUser(sub) + " sub=" + sub + " slot=" + Phone.slotIndex(sub));
            System.exit(0);
        } catch (Throwable e) {
            System.out.println("error=" + e);
            System.exit(1);
        }
    }

    /** 요청 파일을 읽어 첫 칸이 이 프로세스의 pid일 때만 칸 배열을 돌려준다. */
    private static String[] readRequest(String name) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(HOME, name)))) {
            String line = r.readLine();
            if (line == null) return null;
            String[] parts = line.trim().split("\\s+");
            return parts[0].equals(String.valueOf(android.os.Process.myPid())) ? parts : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (!a.startsWith("--")) return null;
            int eq = a.indexOf('=');
            if (eq < 0) m.put(a.substring(2), "");
            else m.put(a.substring(2, eq), a.substring(eq + 1));
        }
        boolean observe = m.containsKey("observe");
        boolean control = m.containsKey("control");
        if (observe == control) return null;
        for (String k : m.keySet()) {
            if (!k.equals("observe") && !k.equals("control") && !k.equals("leftover") && !k.equals("original")
                    && !k.equals("indicator-slot") && !k.equals("indicator-icon")) return null;
        }
        String lo = m.get("leftover");
        if (lo != null && !lo.equals("restore") && !lo.equals("keep")) return null;
        String or = m.get("original");
        if (or != null && !or.equals("nr") && !or.equals("lte")) return null;
        if (observe && m.size() > 1) return null;
        if (m.containsKey("indicator-icon") && parseIcon(m.get("indicator-icon")) == null) return null;
        return m;
    }

    /** "패키지:리소스번호"(예: nrc.companion:0x7f010000) → {패키지, 번호}. 형식이 틀리면 null. */
    static String[] parseIcon(String v) {
        if (v == null) return null;
        int c = v.indexOf(':');
        if (c <= 0) return null;
        String pkg = v.substring(0, c);
        String id = v.substring(c + 1);
        if (!pkg.matches("[A-Za-z0-9_.]+")) return null;
        try {
            Integer.decode(id);
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{pkg, id};
    }

    /** 숨은 허용 타입 알림까지 받는 관찰기로 등록하고, 안 되면 공개 알림만 받는 관찰기로 내려간다. */
    private static Observer register(Journal log, TelephonyManager tm, int sub, Executor ex, Observer.Listener out) {
        Observer o;
        try {
            o = new AllowedObserver(log, sub, out);
        } catch (LinkageError e) {
            log.write("warn", "what", "allowed_cb_unavailable", "msg", String.valueOf(e));
            o = null;
        }
        if (o != null) {
            try {
                o.markRegistered();
                tm.registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE, ex, o);
                log.write("registered", "allowedCb", true);
                return o;
            } catch (SecurityException e) {
                log.write("warn", "what", "allowed_cb_denied", "msg", String.valueOf(e));
            }
        }
        o = new Observer(log, sub, out);
        o.markRegistered();
        // 위치 정보는 받지 않는다(DESIGN §5.3: 위치 포기 옵션으로 조회).
        tm.registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE, ex, o);
        log.write("registered", "allowedCb", false);
        return o;
    }

    private static boolean linkPhoneDeath(Journal log) {
        try {
            IBinder phone = Phone.service("phone");
            if (phone == null) return false;
            phone.linkToDeath(() -> {
                log.write("phone_died");
                phoneLinked = false;
            }, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void runSafely(Journal log, String where, Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            log.write("error", "where", where, "msg", String.valueOf(e));
        }
    }

    /** 배타 잠금. 다른 데몬이 잡고 있으면 null. 채널은 프로세스가 끝날 때 운영체제가 닫는다. */
    @SuppressWarnings("resource")
    private static FileLock tryLock() {
        try {
            return new RandomAccessFile(LOCK, "rw").getChannel().tryLock();
        } catch (IOException | OverlappingFileLockException e) {
            return null;
        }
    }

    /** PC 도구가 이 데몬을 찾는 데 쓴다(잠금을 얻은 뒤에만 쓴다). */
    private static boolean writePid() {
        try (PrintWriter w = new PrintWriter(new FileWriter(PID, false))) {
            w.println(android.os.Process.myPid());
            return !w.checkError();
        } catch (IOException e) {
            return false;
        }
    }

    /** pid 파일이 아직 이 프로세스를 가리킬 때만 지운다. */
    private static void removePidIfMine() {
        try (BufferedReader r = new BufferedReader(new FileReader(PID))) {
            String line = r.readLine();
            if (line != null && line.trim().equals(String.valueOf(android.os.Process.myPid()))) PID.delete();
        } catch (IOException ignored) {
            // 이미 없으면 할 일 없음
        }
    }

    private Nrd() {
    }
}
