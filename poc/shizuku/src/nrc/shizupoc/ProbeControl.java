package nrc.shizupoc;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Typeface;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * 'Wi-Fi 없이 재부팅 시험' 시작하기 화면(아이콘 이름 'NR no-wifi probe'). 제품 아님 — DESIGN §5.18 '새 후보 탐색 2차' PoC.
 * phoneapp의 시작하기(SetupActivity·StartSteps)와 같은 모양: 끝난 단계는 폰 상태를 읽어 ✓, 지금 할 단계만 펼침, 1초마다 다시 읽음.
 * 단계 판정·재부팅 결과 판정은 ProbeSteps(PC 시험).
 *
 * 경계: 이 화면도 전화 설정을 쓰지 않는다(Main의 쓰기 버튼과 분리). Shizuku는 '살아 있나·허락됐나'만 읽고,
 * 허락 요청은 사용자가 3단계 버튼을 누를 때만 한다. 관측(BootProbe)이 켜져 있으면 화면을 열 때마다 'event: ui-open'을
 * 기록에 남겨, 재부팅 뒤 통로가 살아나기 전에 이 화면을 열었는지(=무개입이 아님)를 판정에 쓴다.
 */
public final class ProbeControl extends Activity {
    private static final int ACCENT = 0xFF2563EB, OK = 0xFF16A34A, MUTED = 0xFF8A8A8A, WARN = 0xFFDC2626;
    private static final int REQ = 2002;
    /**
     * Shizuku-Next 패키지 이름과 받는 곳(오픈소스 GitHub Releases, 2026-10-07 조회: rushiranpise/Shizuku-Next가 릴리스를 내는 원본).
     * 패키지 이름이 공식 Shizuku와 같고 서명 키만 달라 둘은 같이 못 깔린다 → 깔린 앱이 Shizuku-Next인지는 서명 인증서로 가린다.
     * 인증서 SHA-256 = v14.0.17-next 릴리스 APK를 apksigner로 확인한 값(GitHub 빌드 출처 증명도 통과, DESIGN §5.18).
     */
    static final String NEXT_PKG = "moe.shizuku.privileged.api";
    static final String NEXT_RELEASES = "https://github.com/rushiranpise/Shizuku-Next/releases";
    static final String NEXT_CERT_SHA256 = "06360bb800d1a117c3a22c91a58e638bcd37281cb4d644fcfac5d7238c654293";
    /** 4단계 '다 맞췄어요' 확인 표시(다른 앱 설정이라 이 앱이 직접 못 읽음). */
    static final String CONFIRM = "nowifi_probe.nowifi_confirmed";
    /** 2단계 '페어링까지 했어요' 확인 표시(페어링 기록은 이 앱이 못 읽음 — PC가 연결돼 있으면 개발자가 PC로 따로 확인). */
    static final String PAIRED = "nowifi_probe.paired_confirmed";
    private static final int REQ_NOTIF = 2003;

    private final Handler main = new Handler(Looper.getMainLooper());
    private float d;
    private TextView summary;
    private TextView noteView;
    private String noteText;
    private final TextView[] heads = new TextView[ProbeSteps.COUNT];
    private final LinearLayout[] bodies = new LinearLayout[ProbeSteps.COUNT];
    private final Set<Integer> opened = new HashSet<>();
    private final Set<Integer> closed = new HashSet<>();
    private Button permButton;
    private Button pairButton;
    private Button probeButton;
    private Button notifButton;
    private Button confirmButton;
    private TextView adbLine;
    private TextView logView;
    private LinearLayout logBox;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, 1000);
        }
    };
    private final Shizuku.OnRequestPermissionResultListener permL = (code, result) -> {
        if (code != REQ) return;
        main.post(() -> note(result == PackageManager.PERMISSION_GRANTED ? "허락됐어요 ✓"
                : "허락하지 않았어요 — 다시 누르면 다시 물어요"));
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        int pad = dp(16);
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);
        sv.addView(body);
        setContentView(sv);

        TextView title = text("Wi-Fi 없이 재부팅 시험", 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);
        body.addView(text("알고 싶은 것: 폰을 다시 켰을 때, Wi-Fi도 없고 아무도 안 건드려도 'Shizuku 통로'(이 앱이 5G를 다룰 때 "
                + "쓰는 길)가 스스로 되살아나는지.\n"
                + "이 앱은 지켜보고 적기만 해요. 전화 설정(5G/LTE)은 바꾸지 않아서, 시험이 실패해도 5G가 막힌 채 남지 않아요.\n"
                + "끝난 단계는 앱이 폰을 읽어 ✓로 바꿔요.", 14));
        summary = text("", 15);
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(summary);
        noteView = text("", 14);
        noteView.setTextColor(ACCENT);
        body.addView(noteView);

        // 1. Shizuku-Next 깔기
        LinearLayout s = step(body, ProbeSteps.NEXT);
        s.addView(text("Shizuku-Next는 Shizuku를 고쳐 만든 오픈소스 앱이에요. 'Wi-Fi 없이 시작'이라는 실험 기능이 들어 있어요.\n"
                + "누가: PC가 연결돼 있으면 개발자가 대신 깔아요. 혼자 깔 때는 아래 버튼으로 GitHub의 Releases 페이지를 열어 "
                + "맨 위 버전의 shizuku-…-next.apk 파일(이름에 debug가 없는 것)을 받아 열면 돼요.\n"
                + "주의: 공식 Shizuku와 앱 이름표가 같아서, 공식 Shizuku가 깔려 있으면 먼저 지워야 깔려요. "
                + "지우면 공식 Shizuku에 해 둔 앱 허락 목록도 같이 지워져요.", 14));
        s.addView(button("Shizuku-Next 받는 곳 열기(GitHub Releases)", v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(NEXT_RELEASES)))));

        // 2. 한 번 켜기
        s = step(body, ProbeSteps.RUN);
        s.addView(text("재부팅 뒤 Shizuku-Next가 혼자 켜지려면, 그 전에 한 번 '무선 디버깅 페어링'(폰과 Shizuku-Next가 서로 "
                + "알아보게 짝 맺기)을 해 둬야 해요. PC로 켜는 것만으로는 이 짝이 안 생겨요.\n"
                + "사용자:\n"
                + "① Wi-Fi를 켜고 아무 Wi-Fi에 연결해요(페어링은 Wi-Fi가 있어야 돼요. 6단계에서 다시 꺼요).\n"
                + "② Shizuku-Next 앱을 열어 '홈'의 '무선 디버깅으로 시작' 칸에서 '시작'을 눌러요.\n"
                + "③ 처음이면 '기기와 페어링' 안내가 떠요. 안내대로 설정 → 개발자 옵션 → '무선 디버깅' 글자를 눌러 들어가 "
                + "'페어링 코드로 기기 페어링'을 누르고, 뜬 6자리 코드를 알림창의 Shizuku-Next 알림에 적어요.\n"
                + "④ Shizuku-Next가 켜지고 페어링까지 했으면 아래 [페어링까지 했어요]를 눌러요. "
                + "(통로가 켜진 것만으로는 ✓가 안 돼요 — PC로 켠 경우엔 페어링 짝이 없어서 재부팅 뒤 혼자 못 켜지거든요.)", 14));
        s.addView(button("Shizuku-Next 열기", v -> openApp(NEXT_PKG)));
        s.addView(button("Wi-Fi 스위치 열기", v -> {
            if (!open(new Intent(Settings.Panel.ACTION_WIFI))) open(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }));
        pairButton = button("페어링까지 했어요", v -> {
            if (!pingSafe()) {
                note("Shizuku-Next가 아직 안 켜져 있어요 — 위 ②~③을 먼저 해 주세요");
                return;
            }
            touch(PAIRED);
            render();
        });
        s.addView(pairButton);

        // 3. 사용 허락
        s = step(body, ProbeSteps.PERM);
        s.addView(text("사용자: 아래 버튼을 누르면 Shizuku-Next가 이 앱을 허락할지 물어요 → '항상 허용'을 눌러요.\n"
                + "이 허락은 재부팅 뒤 통로가 살아났을 때 그 통로가 어떤 권한으로 떴는지 적는 데만 써요.", 14));
        permButton = button("사용 허락 요청", v -> requestPerm());
        s.addView(permButton);

        // 4. Wi-Fi 없이 시작 켜기
        s = step(body, ProbeSteps.NOWIFI);
        s.addView(text("사용자: Shizuku-Next 앱 아래쪽 'Settings' 탭 → 'Startup' 칸에서 세 가지를 맞춰요.\n"
                + "① 'Start without Wi-Fi (experimental)' 켜기 — 재부팅 뒤 Wi-Fi가 없어도 스스로 켜 보는 실험 기능이에요.\n"
                + "② '부팅 시 시작' 켜기 — 배터리 최적화를 꺼 달라고 물으면 허용해요.\n"
                + "③ 'TCP 모드' 끄기 — 이 폰과 같은 기종에서 이걸 켜 둔 채 자동 시작이 멈췄다는 보고가 있고, 켜 두면 "
                + "폰에 문 하나(5555번)가 더 열려 있게 돼요.\n"
                + "이 앱은 다른 앱의 스위치를 직접 읽을 수 없어서, 다 맞췄으면 아래 [다 맞췄어요]를 눌러 주세요.\n"
                + "알아둘 점: ①은 실험용이라 무선 디버깅이 켜진 채 남을 수 있어요(이 화면 맨 아래에 지금 상태가 보여요).", 14));
        s.addView(button("Shizuku-Next 열기", v -> openApp(NEXT_PKG)));
        confirmButton = button("다 맞췄어요", v -> {
            touch(CONFIRM);
            render();
        });
        s.addView(confirmButton);

        // 5. 관측 켜기
        s = step(body, ProbeSteps.PROBE);
        s.addView(text("아래 버튼을 누르면, 이 앱이 폰이 켜질 때마다 저절로 깨어나 10초마다 '통로가 살아났나'를 적어요(30분 동안). "
                + "알림창에 'Shizuku 통로 관측 중(시험)' 알림이 떠 있는 동안이 관측 중이고, 결과도 그 알림 글로 보여요.\n"
                + "사용자: 알림을 허용할지 물으면 '허용'을 눌러요(안 하면 결과를 알림창에서 못 봐요).", 14));
        probeButton = button("관측 켜기", v -> turnOn());
        s.addView(probeButton);
        notifButton = button("이 앱 알림 켜기", v -> askNotif());
        s.addView(notifButton);

        // 6. Wi-Fi 끄고 모바일 데이터 켜기
        s = step(body, ProbeSteps.AIR);
        s.addView(text("사용자: Wi-Fi 스위치를 끄고 모바일 데이터를 켜요(외부 Wi-Fi 없이 되살아나는지 보는 시험이라서요).", 14));
        s.addView(button("Wi-Fi·모바일 데이터 스위치 열기", v -> {
            if (!open(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) open(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }));

        // 7. 재부팅
        s = step(body, ProbeSteps.REBOOT);
        s.addView(text("사용자:\n"
                + "① 알림창을 두 번 내려 오른쪽 위 전원(⏻) 아이콘 → '다시 시작'.\n"
                + "② 켜지면 잠금만 풀어요. 그 다음엔 어떤 앱도 열지 마세요(이 앱도, Shizuku-Next도).\n"
                + "③ 5분쯤 기다렸다가 알림창만 내려 'Shizuku 통로 관측 중(시험)' 알림의 글을 봐요.\n"
                + "폰이 스스로: 잠금이 풀리면 이 앱이 저절로 깨어나 10초마다 적고, 통로가 살아나면 알림 글이 바뀌어요.\n"
                + "④ 알림 글을 본 뒤에 이 앱을 열면 여기에 판정이 떠요(살아나기 전에 열면 '무개입'으로 칠 수 없어요).", 14));

        // 시험 뒤 안전 확인 + 기록
        adbLine = text("", 14);
        body.addView(adbLine);
        body.addView(button("개발자 옵션 열기(무선 디버깅 끄기)", v -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))));
        body.addView(link("관측 기록 보기 ▼", v -> {
            logBox.setVisibility(logBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            render();
        }));
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logBox.setVisibility(View.GONE);
        logView = text("", 12);
        logView.setTextIsSelectable(true);
        logBox.addView(logView);
        logBox.addView(text("PC로 끌어오기(개발용): adb exec-out run-as " + getPackageName() + " cat files/" + BootProbe.LOG, 12));
        body.addView(logBox);
        body.addView(button("새 시험 시작(지금 기록은 보관하고 처음부터)", v -> newTest()));
        body.addView(button("관측 끄기(재부팅 때 더는 안 깨어남)", v -> turnOff()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 관측이 켜져 있으면 '이 화면을 연 때'를 남긴다 — 재부팅 뒤 통로가 살아나기 전에 열었으면 무개입 판정에서 뺀다
        if (BootProbe.enabled(this)) {
            BootProbe.appendLine(this, "event: ui-open boot=" + BootProbe.bootCount(this)
                    + " sinceBootMs=" + SystemClock.elapsedRealtime());
        }
        try {
            Shizuku.addRequestPermissionResultListener(permL);
        } catch (Throwable ignored) {
        }
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        try {
            Shizuku.removeRequestPermissionResultListener(permL);
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ 폰 상태 읽기·그리기

    private ProbeSteps.Facts facts() {
        ProbeSteps.Facts f = new ProbeSteps.Facts();
        Boolean next = isNext();
        f.nextInstalled = Boolean.TRUE.equals(next);
        f.otherShizuku = Boolean.FALSE.equals(next);
        f.alive = pingSafe();
        if (f.alive) {
            try {
                f.permitted = !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable t) {
                f.permitted = null;
            }
        }
        f.pairConfirmed = new File(getFilesDir(), PAIRED).exists();
        f.noWifiConfirmed = new File(getFilesDir(), CONFIRM).exists();
        f.probeOn = BootProbe.enabled(this);
        f.notifOk = notifOk();
        f.wifiOn = BootProbe.wifiSwitchOn(this);
        f.cell = BootProbe.transportPresent(this, NetworkCapabilities.TRANSPORT_CELLULAR);
        f.log = readLines(new File(getFilesDir(), BootProbe.LOG));
        return f;
    }

    private void render() {
        ProbeSteps.Facts f = facts();
        ProbeSteps st = ProbeSteps.of(f);
        ProbeSteps.Verdict v = st.verdict;
        summary.setText(st.remaining == 0 ? "시험 끝 — 결과: " + v.text
                : st.current == ProbeSteps.REBOOT ? "준비 끝 ✓ — 이제 ▶ 7단계(재부팅)만 남았어요"
                : "남은 단계 " + st.remaining + "개 — ▶ 표시가 지금 할 단계예요");
        for (int i = 0; i < ProbeSteps.COUNT; i++) {
            String mark = st.done[i] ? "✓" : i == st.current ? "▶" : "○";
            heads[i].setText(mark + " " + (i + 1) + ". " + ProbeSteps.TITLES[i] + " — " + st.state[i]);
            heads[i].setTextColor(st.done[i] ? OK : i == st.current ? ACCENT : MUTED);
            boolean open = opened.contains(i) || (i == st.current && !closed.contains(i));
            bodies[i].setVisibility(open ? View.VISIBLE : View.GONE);
        }
        // 초록=표본상 깨끗(최종은 PC 대조) / 파랑=살아났지만 기록 빈틈(PC 대조 필요) / 빨강=실패·판정 불가
        if (v.result == ProbeSteps.Result.ALIVE_CLEAN) heads[ProbeSteps.REBOOT].setTextColor(OK);
        else if (v.result == ProbeSteps.Result.ALIVE_GAP) heads[ProbeSteps.REBOOT].setTextColor(ACCENT);
        else if (v.finished()) heads[ProbeSteps.REBOOT].setTextColor(WARN);
        permButton.setEnabled(f.alive);
        pairButton.setVisibility(f.pairConfirmed ? View.GONE : View.VISIBLE);
        probeButton.setVisibility(f.probeOn ? View.GONE : View.VISIBLE);
        notifButton.setVisibility(f.probeOn && !f.notifOk ? View.VISIBLE : View.GONE);
        confirmButton.setVisibility(f.noWifiConfirmed ? View.GONE : View.VISIBLE);
        Boolean adb = adbWifiOn();
        adbLine.setText(adb == null ? "무선 디버깅: 상태 모름"
                : adb ? "⚠ 무선 디버깅: 켜져 있어요 — 시험이 끝나면 아래 버튼으로 개발자 옵션에서 꺼 주세요"
                : "무선 디버깅: 꺼져 있어요 ✓");
        adbLine.setTextColor(Boolean.TRUE.equals(adb) ? WARN : MUTED);
        noteView.setText(noteText == null ? "" : noteText);
        noteView.setVisibility(noteText == null ? View.GONE : View.VISIBLE);
        if (logBox.getVisibility() == View.VISIBLE) logView.setText(tail(f.log, 60));
    }

    // ================================================================ 동작

    private void requestPerm() {
        if (!pingSafe()) {
            note("Shizuku-Next가 아직 안 켜져 있어요 — 2단계를 먼저 해 주세요");
            return;
        }
        try {
            if (Shizuku.isPreV11()) {
                note("이 Shizuku는 너무 옛 버전이라 허락을 물을 수 없어요");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                note("이미 허락돼 있어요 ✓");
                return;
            }
            Shizuku.requestPermission(REQ);
        } catch (Throwable t) {
            note("허락 요청 실패: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 관측 켜기 = (안드로이드 13+면 먼저 알림 허락을 묻고) 켜기 표시 파일 + 지금 한 번 시작(재부팅 전 기준 기록).
     * 알림 허락은 결과를 알림창에서 보기 위한 것 — 거절해도 관측 자체는 켠다(5단계는 알림이 보일 때까지 ✓ 안 됨).
     */
    private void turnOn() {
        if (needNotifPermission()) {
            turnOnAfterAsk = true;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        turnOnNow();
    }

    private boolean turnOnAfterAsk;

    private void turnOnNow() {
        if (!touch(BootProbe.FLAG)) {
            note("관측 켜기 표시를 못 만들었어요 — 다시 눌러 주세요");
            return;
        }
        try {
            startForegroundService(new Intent(this, BootProbe.class).putExtra("cause", "manual"));
            note("관측을 켰어요 ✓ 이제 재부팅 때마다 저절로 깨어나 적어요");
        } catch (Throwable t) {
            note("관측 시작 실패: " + t.getClass().getSimpleName() + " — 표시는 켜져 있어 재부팅 때는 시작돼요");
        }
        render();
    }

    /** 이 화면에서 알림 허락을 한 번 거절당했는지(그 뒤엔 다시 묻지 않고 알림 설정 화면을 연다). */
    private boolean notifDenied;

    /** [이 앱 알림 켜기]: 아직 안 물었으면 묻고, 거절됐거나 앱·채널 알림이 꺼진 경우는 이 앱 알림 설정을 연다. */
    private void askNotif() {
        if (needNotifPermission() && !notifDenied) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIF) return;
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (turnOnAfterAsk) {
            turnOnAfterAsk = false;
            turnOnNow();
        }
        if (!ok) {
            notifDenied = true;
            note("알림을 허용하지 않았어요 — 결과를 알림창에서 보려면 [이 앱 알림 켜기]를 눌러 알림 설정에서 켜 주세요");
        }
        render();
    }

    private boolean needNotifPermission() {
        return android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED;
    }

    /** 관측 알림이 실제로 보일 수 있는지: 알림 허락 + 앱 알림 켜짐 + (채널이 이미 있으면) 그 채널이 꺼지지 않음. */
    private boolean notifOk() {
        try {
            if (needNotifPermission()) return false;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null || !nm.areNotificationsEnabled()) return false;
            NotificationChannel ch = nm.getNotificationChannel(BootProbe.CHANNEL);
            return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (Throwable t) {
            return false;
        }
    }

    private void turnOff() {
        File flag = new File(getFilesDir(), BootProbe.FLAG);
        if (flag.exists() && !flag.delete()) {
            note("관측 끄기 실패(다음 재부팅에도 깨어날 수 있어요) — 다시 눌러 주세요");
            return;
        }
        BootProbe.appendLine(this, "=== probe off (user) boot=" + BootProbe.bootCount(this)
                + " sinceBootMs=" + SystemClock.elapsedRealtime());
        try {
            stopService(new Intent(this, BootProbe.class));
        } catch (Throwable ignored) {
        }
        BootProbe.clearAnchor(this);
        note("관측을 껐어요. 재부팅해도 더는 깨어나지 않아요");
        render();
    }

    /** 지금 기록을 날짜 붙은 이름으로 보관하고 처음부터(관측 켜기 표시는 그대로). */
    private void newTest() {
        File log = new File(getFilesDir(), BootProbe.LOG);
        if (log.exists()) {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            if (!log.renameTo(new File(getFilesDir(), "nowifi_probe." + stamp + ".log"))) {
                note("기록 보관 실패 — 다시 눌러 주세요");
                return;
            }
        }
        try {
            stopService(new Intent(this, BootProbe.class));
        } catch (Throwable ignored) {
        }
        BootProbe.clearAnchor(this);
        note("지난 기록은 보관했어요. 6단계까지 확인한 뒤 재부팅하면 새로 판정해요");
        render();
    }

    private boolean touch(String name) {
        try (FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), name), false)) {
            fos.write("on".getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void note(String s) {
        noteText = s;
        render();
    }

    // ================================================================ 읽기 도우미

    /**
     * Shizuku 이름표의 앱이 Shizuku-Next인지: 서명 인증서가 Shizuku-Next 것이면 true, 다른 서명(공식판 등)이면 false,
     * 안 깔렸으면 null. 서명이 여럿이면(키 교체 이력) 하나라도 맞으면 Shizuku-Next로 본다.
     */
    private Boolean isNext() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(NEXT_PKG, PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null) return false;
            Signature[] sigs = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners()
                    : pi.signingInfo.getSigningCertificateHistory();
            if (sigs == null) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs) {
                StringBuilder hex = new StringBuilder();
                for (byte x : md.digest(s.toByteArray())) hex.append(String.format(Locale.US, "%02x", x));
                if (NEXT_CERT_SHA256.equals(hex.toString())) return true;
            }
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean pingSafe() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private Boolean adbWifiOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) out.add(l);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String tail(List<String> lines, int n) {
        if (lines.isEmpty()) return "(아직 기록 없음)";
        StringBuilder sb = new StringBuilder();
        if (lines.size() > n) sb.append("…(앞부분 생략)\n");
        for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }

    private void openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            note("그 앱이 아직 안 깔려 있어요 — 1단계를 먼저 해 주세요");
            return;
        }
        open(i);
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Throwable t) {
            note("그 화면을 열지 못했어요. 설정 앱에서 직접 찾아 주세요.");
            return false;
        }
    }

    // ================================================================ 화면 부품(SetupActivity와 같은 모양)

    private LinearLayout step(LinearLayout parent, int i) {
        TextView head = text("", 16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(0, dp(14), 0, dp(4));
        head.setOnClickListener(v -> {
            boolean open = bodies[i].getVisibility() == View.VISIBLE;
            if (open) {
                closed.add(i);
                opened.remove(i);
            } else {
                opened.add(i);
                closed.remove(i);
            }
            render();
        });
        parent.addView(head);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(14), 0, 0, dp(4));
        parent.addView(b);
        heads[i] = head;
        bodies[i] = b;
        return b;
    }

    private int dp(int v) {
        return Math.round(v * d);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private TextView link(String s, View.OnClickListener l) {
        TextView t = text(s, 14);
        t.setTextColor(ACCENT);
        t.setOnClickListener(l);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }
}
