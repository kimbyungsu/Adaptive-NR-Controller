package nrc.controller;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 앱 안 처음 설정(사용자 결정 2026-09-30: PC 없이 폰 자체 무선 디버깅, 직접 구현, 코드는 알림·분할 화면 둘 다, 끝나면 무선 디버깅만 끔).
 * 순서(모두 작업 스레드 하나에서 차례로):
 *  1. 이미 5G/LTE 전환 권한이 있으면 할 일 없음.
 *  2. 이 앱의 연결용 열쇠 준비(처음이면 만든다).
 *  3. 페어링 코드 창(포트)을 찾아 6자리 코드로 페어링.
 *  4. 무선 디버깅 접속 포트를 찾아 shell 권한으로 SetupTool 실행 → 폰이 인정하는 목록 끝에 이 앱 줄을 덧붙임(기존 줄 그대로).
 *  5. 앱이 직접 권한이 생겼는지 확인(목록에 들어간 것만으로 성공이라 하지 않는다).
 *  6. 무선 디버깅 끄기.
 * 진행 줄은 듣는 쪽(설정 화면·알림)에 보내고, 기록은 logs/setup.log에 남긴다. 한 번에 하나만 돈다.
 */
final class Setup {
    private Setup() {
    }

    interface Listener {
        void line(String text, boolean ok);

        void finished(boolean ok);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final List<String[]> history = new ArrayList<>();
    private static volatile boolean running;
    private static volatile Boolean lastResult;
    /** 설정 결과와 별개로 남은 경고(무선 디버깅이 켜진 채 등). 없으면 null. */
    private static volatile String warning;
    /** 이번 실행에서 무선 디버깅에 붙어 봤는지(경고를 낼지 정할 때). */
    private static volatile boolean touchedAdb;

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static boolean running() {
        return running;
    }

    /** 지난(또는 지금) 설정 진행 줄: {글, "1"|"0"}. */
    static synchronized List<String[]> history() {
        return new ArrayList<>(history);
    }

    /** 마지막 설정 결과(없으면 null). */
    static Boolean lastResult() {
        return lastResult;
    }

    /** 마지막 실행 뒤 남은 경고(없으면 null). 성공 알림이 이 경고를 덮지 않게 따로 둔다(외부 검증 지적). */
    static String warning() {
        return warning;
    }

    /**
     * 설정을 시작한다. pairPort·connectPort가 0 이하면 스스로 찾는다. 이미 도는 중이면 false.
     * code는 숫자 6자리만 받는다(다른 글자는 버린다).
     */
    static boolean start(Context ctx, String code, int pairPort, int connectPort) {
        return start(ctx, code, pairPort, connectPort, false);
    }

    /**
     * 기기 시험용(시험 명령에서만): 권한이 이미 있어도 페어링·연결까지 해 보고, 목록은 읽기만 한다(덧붙이지 않음).
     * 끝에 무선 디버깅 끄기도 해 본다.
     */
    static boolean probe(Context ctx, String code, int pairPort, int connectPort) {
        return start(ctx, code, pairPort, connectPort, true);
    }

    private static synchronized boolean start(Context ctx, String code, int pairPort, int connectPort, boolean probe) {
        if (running) return false;
        running = true;
        lastResult = null;
        warning = null;
        touchedAdb = false;
        history.clear();
        Context c = ctx.getApplicationContext();
        String digits = code == null ? "" : code.replaceAll("[^0-9]", "");
        new Thread(() -> {
            boolean ok = false;
            Journal j = new Journal(logDir(c), "setup.log");
            try {
                ok = run(c, j, digits, pairPort, connectPort, probe);
            } catch (Throwable t) {
                emit("오류로 멈췄어요: " + t.getClass().getSimpleName(), false);
                j.write("setup_error", "msg", String.valueOf(t));
            } finally {
                Boolean on = adbWifiOn(c);
                if (Boolean.TRUE.equals(on)) {
                    warning = ok ? "무선 디버깅이 아직 켜져 있어요 — 처음 설정 화면의 [무선 디버깅 끄기]를 누르거나 개발자 옵션에서 꺼 주세요"
                            : "무선 디버깅이 켜진 채예요 — 다시 하지 않을 거면 개발자 옵션에서 꺼 주세요";
                    emit(warning, false);
                } else if (on == null && touchedAdb) {
                    warning = "무선 디버깅이 꺼졌는지 앱이 확인하지 못했어요 — 개발자 옵션에서 확인해 주세요";
                    emit(warning, false);
                }
                lastResult = ok;
                running = false;
                j.write("setup_done", "ok", ok);
                for (Listener l : listeners) l.finished(ok);
            }
        }, "nrc-setup").start();
        return true;
    }

    private static File logDir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : c.getFilesDir();
    }

    private static void emit(String text, boolean ok) {
        synchronized (Setup.class) {
            history.add(new String[]{text, ok ? "1" : "0"});
        }
        for (Listener l : listeners) l.line(text, ok);
    }

    private static boolean run(Context c, Journal j, String code, int pairPort, int connectPort, boolean probe) throws Exception {
        Radio r = Radio.open(c);
        if (r == null) {
            emit("SIM을 확인하지 못했어요. 잠시 뒤 다시 해 주세요", false);
            return false;
        }
        j.write("setup_start", "sub", r.sub, "priv", r.privileged(), "probe", probe);
        if (r.privileged() && !probe) {
            AppState.setSetupResult(c, SupportCheck.SETUP_WORKS);
            emit("이미 5G/LTE 전환 권한이 있어요 — 목록은 건드리지 않아요", true);
            // 지난 설정에서 무선 디버깅 끄기가 안 됐으면 여기서 다시 끈다(코드 없이, 페어링된 열쇠로)
            if (Boolean.TRUE.equals(adbWifiOn(c)) && AdbKey.exists(keyDir(c))) {
                turnOffAdbWifi(c, j, AdbKey.loadOrCreate(keyDir(c)), connectPort);
            }
            return true;
        }
        if (code.length() != 6) {
            emit("코드는 숫자 6자리예요", false);
            return false;
        }
        if (!onWifi(c)) {
            emit("Wi-Fi에 연결돼 있지 않아요 — 무선 디버깅은 Wi-Fi에서만 켜져요", false);
            return false;
        }

        AdbKey key = AdbKey.loadOrCreate(keyDir(c));
        String host = "127.0.0.1";

        // 3. 페어링
        int pp = pairPort > 0 ? pairPort : find(c, AdbFind.PAIRING, 10_000);
        if (pp <= 0) {
            emit("페어링 코드 창을 찾지 못했어요 — 무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 눌러 코드 창을 띄운 채로 해 주세요"
                    + "(그래도 안 되면 코드 창의 'IP 주소 및 포트'에서 콜론 뒤 숫자를 '코드 창 포트' 칸에 적어요)", false);
            return false;
        }
        emit("앱: 코드 창을 찾았어요(포트 " + pp + ")", true);
        touchedAdb = true;
        try {
            AdbPair.pair(key, host, pp, code);
        } catch (AdbPair.Failure f) {
            j.write("setup_pair", "ok", false, "port", pp, "err", String.valueOf(f.getCause()));
            emit(f.getMessage(), false);
            return false;
        }
        j.write("setup_pair", "ok", true, "port", pp);
        emit("앱: 폰과 페어링했어요(설정의 '페어링된 기기'에 " + AdbKey.NAME + "로 보여요)", true);

        // 4. 목록에 덧붙이기
        int cp = connectPort > 0 ? connectPort : find(c, AdbFind.CONNECT, 10_000);
        if (cp <= 0) {
            emit("무선 디버깅 접속 포트를 찾지 못했어요 — 무선 디버깅 화면(코드 창 말고 바탕 화면)의 'IP 주소 및 포트'에서 콜론 뒤 숫자를 "
                    + "'접속 포트' 칸에 적어 다시 해 주세요", false);
            return false;
        }
        String entry = entry(c);
        String apk = c.getApplicationInfo().sourceDir;
        if (entry == null || apk == null || apk.contains("'")) {
            emit("앱 정보를 읽지 못했어요", false);
            return false;
        }
        String out;
        try {
            out = AdbExec.run(key, host, cp, "CLASSPATH='" + apk + "' app_process /system/bin " + SetupTool.class.getName()
                    + (probe ? " has " : " add ") + r.sub + " " + entry);
        } catch (AdbExec.Failure f) {
            j.write("setup_exec", "ok", false, "port", cp, "err", String.valueOf(f.getCause()));
            emit(f.getMessage(), false);
            return false;
        }
        String result = lastLine(out, "RESULT ");
        j.write("setup_add", "result", result, "before", lastLine(out, "BEFORE "), "after", lastLine(out, "AFTER "), "entry", entry);
        // 이 폰에서 되는지 판정을 남긴다(점검 화면·보내기용). shell 차단(보안 잠금)은 다른 실패와 구분한다.
        if (!probe && result != null && !"ok".equals(result) && !"already".equals(result)) {
            boolean blockedByShell = result.contains("cannot be invoked by shell")
                    || (result.contains("SecurityException") && result.contains("shell"));
            AppState.setSetupResult(c, blockedByShell ? SupportCheck.SETUP_BLOCKED_PATCH : SupportCheck.SETUP_BLOCKED_OTHER);
            if (blockedByShell) {
                emit("이 폰은 보안 업데이트로 이 방식이 막혀 있어요(지원 안 함) — 아무것도 바꾸지 않았어요", false);
                return false;
            }
        }
        if (probe) {
            boolean read = "already".equals(result) || "absent".equals(result);
            emit(read ? "시험: shell 권한으로 목록을 읽었어요(앱 줄 " + ("already".equals(result) ? "있음" : "없음") + ", 바꾸지 않음)"
                    : "시험: 목록을 읽지 못했어요(" + result + ")", read);
            if (!read) return false;
        } else if ("ok".equals(result)) {
            AppState.setSetupResult(c, SupportCheck.SETUP_WORKS);
            emit("앱: 폰이 인정하는 목록 끝에 이 앱 줄을 덧붙였어요(기존 줄은 그대로)", true);
            ControllerService.timeline(c).add(Timeline.Cat.ACT, "처음 설정: 폰이 인정하는 목록에 이 앱 줄을 덧붙임(무선 디버깅)");
        } else if ("already".equals(result)) {
            AppState.setSetupResult(c, SupportCheck.SETUP_WORKS);
            emit("앱 줄은 이미 목록에 있어요", true);
        } else if ("fail not_ready".equals(result)) {
            emit("폰이 통신사 설정을 아직 다 읽지 않아서 목록을 건드리지 않았어요 — 잠시 뒤 다시 해 주세요", false);
            return false;
        } else {
            emit("목록에 덧붙이지 못했어요(" + (result == null ? "응답 없음" : result) + ")", false);
            return false;
        }

        // 5. 권한 확인
        boolean priv = probe && r.privileged();
        for (int i = 0; i < 30 && !priv && !probe; i++) {
            Thread.sleep(500);
            priv = r.privileged();
        }
        j.write("setup_priv", "ok", priv);
        if (probe) {
            emit("시험: 5G/LTE 전환 권한 " + (priv ? "있음" : "없음"), true);
        } else if (priv) {
            emit("앱: 5G/LTE 전환 권한이 생긴 것을 확인했어요", true);
            ControllerService.timeline(c).add(Timeline.Cat.JUDGE, "처음 설정 끝: 5G/LTE 전환 권한 확인");
        } else {
            emit("목록에는 들어갔지만 권한이 아직 보이지 않아요 — 잠시 뒤 앱을 다시 열어 확인해 주세요", false);
        }

        // 6. 무선 디버깅 끄기(권한 등록과 따로 확인해 경고로 남긴다)
        turnOffAdbWifi(c, j, key, cp);
        return probe || priv;
    }

    /** 페어링된 열쇠로 무선 디버깅을 끈다. 이 명령으로 연결이 끊겨 응답이 없을 수 있어 결과는 설정값으로 확인한다. */
    private static void turnOffAdbWifi(Context c, Journal j, AdbKey key, int cp) throws InterruptedException {
        touchedAdb = true;
        int port = cp > 0 ? cp : find(c, AdbFind.CONNECT, 10_000);
        if (port <= 0) {
            j.write("setup_adb_wifi_off", "port", -1);
            emit("접속 포트를 찾지 못해 무선 디버깅 끄기 명령을 보내지 못했어요", false);
            return;
        }
        try {
            AdbExec.run(key, "127.0.0.1", port, "settings put global adb_wifi_enabled 0");
        } catch (AdbExec.Failure ignored) {
            // 끄는 순간 연결이 끊겨 응답이 없을 수 있다
        }
        Boolean on = adbWifiOn(c);
        for (int i = 0; i < 6 && Boolean.TRUE.equals(on); i++) {
            Thread.sleep(500);
            on = adbWifiOn(c);
        }
        j.write("setup_adb_wifi_off", "port", port, "stillOn", on);
        if (Boolean.FALSE.equals(on)) emit("앱: 무선 디버깅을 껐어요", true);
        else if (on == null) emit("무선 디버깅을 끄라고 보냈어요(꺼졌는지 앱이 읽을 수는 없어요 — 개발자 옵션에서 확인해 주세요)", false);
        else emit("무선 디버깅을 끄지 못했어요", false);
    }

    static File keyDir(Context c) {
        File d = new File(c.getNoBackupFilesDir(), "adb");
        d.mkdirs();
        return d;
    }

    private static int find(Context c, String type, long ms) throws InterruptedException {
        AdbFind f = new AdbFind(c, type);
        f.start();
        try {
            return f.await(ms);
        } finally {
            f.stop();
        }
    }

    /** "이 앱 서명 인증서 SHA-256(소문자 16진수):패키지" — 목록의 한 줄(PC 시험 때와 같은 형식). */
    static String entry(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] s = pi.signingInfo.getApkContentsSigners();
            if (s == null || s.length != 1) return null;
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s[0].toByteArray());
            String e = Ed25519.hex(h) + ":" + c.getPackageName();
            return SetupTool.validEntry(e) ? e : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String lastLine(String out, String prefix) {
        String found = null;
        if (out == null) return null;
        for (String line : out.split("\n")) {
            String l = line.trim();
            if (l.startsWith(prefix)) found = l.substring(prefix.length());
        }
        return found;
    }

    static boolean onWifi(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    /** 무선 디버깅이 켜져 있는지(설정값을 읽을 수 없으면 null). */
    static Boolean adbWifiOn(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean devOptionsOn(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
