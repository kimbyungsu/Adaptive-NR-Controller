package nrc.controller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "시작하기" 체크리스트(사용자 결정 2026-10-01: 한 장짜리 목록, 끝난 단계는 폰 상태를 읽어 ✓, 지금 할 단계만 펼침,
 * 원리는 맨 위 3줄 + [자세히]로 도움말의 "처음 설정은 왜 필요한가").
 * 1~4 = 처음 설정(PC 없이 폰 자체 무선 디버깅, 2026-09-30 결정), 5~7 = 마무리. 단계 판정은 StartSteps(PC 시험).
 * 누가 할 일인지(사용자/앱) 적고, 1초마다 폰 상태를 다시 읽는다. 코드 창·무선 디버깅은 이 화면이 떠 있는 동안 mDNS로 찾는다.
 */
public final class SetupActivity extends Activity implements Setup.Listener {
    private static final int ACCENT = 0xFF2563EB, OK = 0xFF16A34A, MUTED = 0xFF8A8A8A;

    private final Handler main = new Handler(Looper.getMainLooper());
    private float d;
    private TextView summary;
    private final TextView[] heads = new TextView[StartSteps.COUNT];
    private final LinearLayout[] bodies = new LinearLayout[StartSteps.COUNT];
    private final Set<Integer> opened = new HashSet<>();
    private final Set<Integer> closed = new HashSet<>();
    private TextView codeWindow;
    private TextView progress;
    private TextView modeLine;
    private String noteText;
    private EditText codeField;
    private EditText portField;
    private EditText connectPortField;
    private LinearLayout portBox;
    private Button connectButton;
    private Button offButton;
    private Button autoButton;
    private LinearLayout doneBox;
    private Button laterButton;
    private AdbFind pairFind;
    private AdbFind connectFind;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, 1000);
        }
    };

    /** 시작하기 판정에 쓰는 폰 상태. 찾기(mDNS)가 없는 화면은 null을 넘긴다. */
    static StartSteps.Facts facts(Context c, AdbFind pair, AdbFind connect) {
        StartSteps.Facts f = new StartSteps.Facts();
        Radio r = Radio.open(c);
        f.privileged = r != null && r.privileged();
        f.wifi = Setup.onWifi(c);
        f.devOptions = Setup.devOptionsOn(c);
        f.adbWifi = Setup.adbWifiOn(c);
        f.pairingSeen = pair != null && pair.port() > 0;
        f.connectSeen = connect != null && connect.port() > 0;
        f.tileAdded = AppState.tileAdded(c);
        PowerManager pm = c.getSystemService(PowerManager.class);
        f.batteryExempt = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        f.auto = AppState.auto(c);
        if (f.privileged) {
            long u = r.read(Radio.USER);
            f.userNr = u < 0 ? null : CarrierPlan.hasNr(u);
        }
        return f;
    }

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

        TextView title = text("시작하기 — 처음 한 번", 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);
        body.addView(text("이 앱은 5G가 자꾸 끊기는 곳에서 폰을 잠깐 LTE로 쉬게 했다가 다시 5G를 확인해요.\n"
                + "그러려면 폰이 이 앱을 '5G/LTE를 바꿀 수 있는 앱'으로 인정해야 해요. 아래 1~4가 그 등록이고, "
                + "처음 한 번만 하면 돼요(PC는 필요 없어요).\n"
                + "끝난 단계는 앱이 폰을 읽어 ✓로 바꿔요.", 14));
        body.addView(link("자세히: 처음 설정은 왜 필요한가", v -> showHelp("setup")));
        summary = text("", 15);
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(summary);

        // 1. Wi-Fi
        LinearLayout s = step(body, StartSteps.WIFI);
        s.addView(text("사용자: 폰을 Wi-Fi에 연결해요. 인터넷이 안 되는 Wi-Fi여도 괜찮아요. "
                + "핫스팟만 켠 상태나 모바일 데이터로는 다음 단계(무선 디버깅)가 켜지지 않아요.", 14));
        s.addView(mock("설정 → 연결 → Wi-Fi", "Wi-Fi   ● 켜짐", "우리집 Wi-Fi   연결됨   ← 아무 Wi-Fi나"));
        s.addView(button("Wi-Fi 설정 열기", v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS))));

        // 2. 개발자 옵션
        s = step(body, StartSteps.DEV);
        s.addView(text("사용자: 설정 → 휴대전화 정보 → 소프트웨어 정보에서 '빌드번호'를 7번 눌러요. "
                + "잠금 비밀번호를 물으면 넣어요. '개발자 모드를 켰습니다'가 뜨면 끝이에요.", 14));
        s.addView(mock("휴대전화 정보 → 소프트웨어 정보", "빌드번호   ← 7번 톡톡톡"));
        s.addView(button("휴대전화 정보 열기", v -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))));

        // 3. 무선 디버깅
        s = step(body, StartSteps.ADB);
        s.addView(text("사용자: 설정 → 개발자 옵션 → '무선 디버깅' 스위치를 켜요. "
                + "처음 붙은 Wi-Fi면 폰이 '이 네트워크에서 허용할까요?'를 물으니 '허용'을 눌러요.", 14));
        s.addView(mock("개발자 옵션", "무선 디버깅   ● 켜기   ←", "(처음 Wi-Fi면 '허용')"));
        s.addView(button("개발자 옵션 열기", v -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))));

        // 4. 코드
        s = step(body, StartSteps.CODE);
        s.addView(text("사용자: '무선 디버깅' 글자를 눌러 들어가 '페어링 코드로 기기 페어링'을 눌러요. "
                + "6자리 코드 창이 뜨면 그 창을 띄운 채로 아래 둘 중 하나로 넣어요. "
                + "창이 닫히면 코드가 바뀌어요(다른 앱으로 넘어가도 닫혀요).", 14));
        s.addView(mock("무선 디버깅", "페어링 코드로 기기 페어링   ←", "[ 코드 창: 482913 ]  띄운 채로!"));
        codeWindow = text("", 14);
        s.addView(codeWindow);
        s.addView(text("방법 가 · 알림으로: 아래 버튼을 먼저 누르고, 코드 창을 띄운 화면에서 위쪽 알림창을 내려 "
                + "이 앱 알림의 [코드 입력]에 6자리를 적어요.", 14));
        s.addView(button("코드 입력 알림 띄우기", v -> showNotice()));
        s.addView(button("이 앱 알림 설정 열기", v -> open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
        s.addView(text("방법 나 · 화면 나누기: 최근 앱 화면에서 이 앱 아이콘을 눌러 '분할 화면으로 열기'로 설정과 함께 띄우고, "
                + "여기에 적어요.", 14));
        codeField = new EditText(this);
        codeField.setHint("6자리 코드");
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        s.addView(codeField);
        connectButton = button("연결", v -> startFromField());
        s.addView(connectButton);
        s.addView(link("포트를 못 찾는다고 나올 때만 ▼", v -> portBox.setVisibility(
                portBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        portBox = new LinearLayout(this);
        portBox.setOrientation(LinearLayout.VERTICAL);
        portField = new EditText(this);
        portField.setHint("코드 창 포트: 코드 창의 'IP 주소 및 포트'에서 콜론 뒤 숫자");
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portBox.addView(portField);
        connectPortField = new EditText(this);
        connectPortField.setHint("접속 포트: 무선 디버깅 바탕 화면의 'IP 주소 및 포트'에서 콜론 뒤 숫자");
        connectPortField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portBox.addView(connectPortField);
        portBox.setVisibility(View.GONE);
        s.addView(portBox);
        s.addView(text("앱이 하는 일: 코드로 폰 자신과 연결 → 인정 목록 끝에 이 앱 줄 하나(기존 줄은 그대로) "
                + "→ 권한이 생겼는지 확인 → 무선 디버깅 끄기.", 13));
        progress = text("", 14);
        s.addView(progress);
        offButton = button("무선 디버깅 끄기(앱이 끔)", v -> {
            noteText = null;
            Setup.start(this, "", -1, number(connectPortField));
            render();
        });
        s.addView(offButton);

        // 5. 타일
        s = step(body, StartSteps.TILE);
        s.addView(text("사용자: 아래 버튼을 누르고 '추가'를 골라요. 이후 빠른 설정 패널의 '5G 자동' 타일로 "
                + "상태를 보고, 눌러서 자동 제어를 켜고 꺼요.", 14));
        s.addView(mock("빠른 설정 패널", "[ 5G 자동 · 5G 관리 중 ]   ←"));
        s.addView(button("타일 추가", v -> NrTile.requestAdd(this, this::note)));

        // 6. 배터리
        s = step(body, StartSteps.BATTERY);
        s.addView(text("사용자: 아래 버튼을 누르고 '허용'을 골라요. 폰이 이 앱을 재우지 않아 오래 살아 있어요.", 14));
        s.addView(button("배터리 최적화에서 빼기", v -> requestBatteryExemption()));

        // 7. 자동 제어
        s = step(body, StartSteps.AUTO);
        s.addView(text("타일을 누르거나 아래 버튼으로 켜요. 켜져 있고 삼성 설정의 네트워크 모드가 '5G 우선'이면 "
                + "앱이 알아서 지켜봐요. 'LTE 우선'이면 앱은 아무것도 하지 않아요.", 14));
        autoButton = button("자동 제어 켜기", v -> {
            AppState.setAuto(this, true);
            ControllerService.ensure(this);
            render();
        });
        s.addView(autoButton);
        s.addView(button("네트워크 모드 설정 열기", v -> open(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS))));
        modeLine = text("", 13);
        body.addView(modeLine);

        doneBox = new LinearLayout(this);
        doneBox.setOrientation(LinearLayout.VERTICAL);
        TextView done = text("다 됐어요 ✓ 이제 평소처럼 쓰시면 돼요.", 16);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        doneBox.addView(done);
        doneBox.addView(button("평소 화면으로", v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        }));
        doneBox.addView(text("개발자 옵션은 켜 둔 채예요. 은행·결제 앱이 '개발자 옵션을 꺼 달라'며 막으면 개발자 옵션 화면 맨 위 "
                + "스위치를 끄세요. 이 앱 동작에는 영향이 없어요.", 13));
        doneBox.addView(button("개발자 옵션 화면 열기(끄려면)", v -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))));
        doneBox.addView(text("코드를 알림으로 넣으려고 앱 알림을 켰다면, 이제 다시 꺼도 돼요.", 13));
        body.addView(doneBox);
        laterButton = button("나중에 하기", v -> finish());
        body.addView(laterButton);

        pairFind = new AdbFind(this, AdbFind.PAIRING);
        connectFind = new AdbFind(this, AdbFind.CONNECT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Setup.addListener(this);
        pairFind.start();
        connectFind.start();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Setup.removeListener(this);
        main.removeCallbacks(tick);
        // 분할 화면에서 설정 화면을 누르는 동안 이 화면이 멈춤 상태가 되므로 찾기는 끄지 않는다(화면을 닫을 때 끈다)
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pairFind.stop();
        connectFind.stop();
    }

    // ================================================================ Setup.Listener(작업 스레드)

    @Override
    public void line(String text, boolean ok) {
        main.post(this::render);
    }

    @Override
    public void finished(boolean ok) {
        main.post(this::render);
    }

    // ================================================================ 그리기

    private void render() {
        StartSteps.Facts f = facts(this, pairFind, connectFind);
        StartSteps st = StartSteps.of(f);
        summary.setText(st.remaining == 0 ? "모든 단계가 끝났어요 ✓" : "남은 단계 " + st.remaining + "개 — ▶ 표시가 지금 할 단계예요");
        for (int i = 0; i < StartSteps.COUNT; i++) {
            String mark = st.done[i] ? "✓" : i == st.current ? "▶" : "○";
            heads[i].setText(mark + " " + (i + 1) + ". " + StartSteps.TITLES[i] + " — " + st.state[i]);
            heads[i].setTextColor(st.done[i] ? OK : i == st.current ? ACCENT : MUTED);
            boolean open = opened.contains(i) || (i == st.current && !closed.contains(i));
            bodies[i].setVisibility(open ? View.VISIBLE : View.GONE);
        }
        int port = pairFind.port();
        codeWindow.setText(f.privileged ? "" : "코드 창: " + (port > 0 ? "열려 있어요(앱이 찾음)" : "안 보여요"));

        StringBuilder p = new StringBuilder();
        List<String[]> h = Setup.history();
        for (String[] l : h) p.append("1".equals(l[1]) ? "✓ " : "✗ ").append(l[0]).append('\n');
        if (Setup.running()) p.append("… 진행 중(코드 창은 그대로 두세요)\n");
        String w = Setup.running() ? null : Setup.warning();
        if (w != null) p.append("⚠ ").append(w).append('\n');
        String shown = p.toString().trim();
        progress.setText(noteText != null ? (shown.isEmpty() ? noteText : noteText + "\n\n" + shown) : shown);
        connectButton.setEnabled(!Setup.running());
        offButton.setVisibility(f.privileged && Boolean.TRUE.equals(f.adbWifi) && !Setup.running() ? View.VISIBLE : View.GONE);
        autoButton.setVisibility(f.auto ? View.GONE : View.VISIBLE);
        modeLine.setText(st.modeLine);
        doneBox.setVisibility(st.remaining == 0 ? View.VISIBLE : View.GONE);
        laterButton.setVisibility(st.remaining == 0 ? View.GONE : View.VISIBLE);
    }

    /** 단계 머리(누르면 펼치기·접기)와 본문 칸을 만든다. 본문은 지금 할 단계일 때 저절로 펼쳐진다. */
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

    /** 설정 화면을 흉내 낸 작은 그림(테두리 상자). "←"가 있는 줄은 눌러야 할 곳이라 강조한다. */
    private View mock(String... rows) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), MUTED);
        box.setBackground(bg);
        for (int i = 0; i < rows.length; i++) {
            TextView t = new TextView(this);
            t.setText(rows[i]);
            t.setTextSize(i == 0 ? 12 : 14);
            if (i == 0) t.setTextColor(MUTED);
            if (rows[i].contains("←")) {
                t.setTextColor(ACCENT);
                t.setTypeface(Typeface.DEFAULT_BOLD);
            }
            t.setPadding(0, dp(2), 0, dp(2));
            box.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        box.setLayoutParams(lp);
        return box;
    }

    /** 도움말의 한 절을 이 화면 위에 띄운다(읽고 닫으면 체크리스트로 돌아온다). */
    private void showHelp(String anchor) {
        WebView w = new WebView(this);
        w.getSettings().setJavaScriptEnabled(false);
        w.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                return "nrc".equals(req.getUrl().getScheme()); // [시작하기] 열기 링크: 이미 이 화면이라 무시, 문서 안 이동만 허용
            }
        });
        w.loadUrl("file:///android_asset/help.html#" + anchor);
        new AlertDialog.Builder(this).setView(w).setPositiveButton("닫기", null).show();
    }

    // ================================================================ 동작

    private void startFromField() {
        String code = codeField.getText().toString().trim();
        int port = number(portField);
        if (port <= 0) port = pairFind.port();
        noteText = null;
        if (!Setup.start(this, code, port, number(connectPortField))) return;
        render();
    }

    /** [코드 입력 알림 띄우기]: 알림 허락이 없으면(안드로이드 13+) 한 번 묻는다. 거절되면 다시 묻지 않고 다른 방법을 안내한다. */
    private void showNotice() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        postNotice();
    }

    private void postNotice() {
        if (!SetupNotice.showInput(this, "설정 화면에 코드 창을 띄운 채 여기서 [코드 입력]을 눌러 6자리를 적어 주세요.")) {
            note("앱 알림이 꺼져 있어요 — [이 앱 알림 설정 열기]에서 켠 뒤 다시 누르거나, 방법 나(화면 나누기)로 해 주세요.");
        } else {
            note("알림을 띄웠어요. 이제 무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 누르고, 알림창을 내려 코드를 적어 주세요.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            postNotice();
        } else {
            note("알림을 허락하지 않았어요 — 방법 나(화면 나누기)로 하거나, [이 앱 알림 설정 열기]에서 켠 뒤 다시 눌러 주세요.");
        }
    }

    /** 단계 4 진행 칸 위의 한 줄 안내(설정 진행 줄과 따로). */
    private void note(String s) {
        noteText = s;
        render();
    }

    private static int number(EditText f) {
        try {
            String s = f.getText().toString().trim();
            return s.isEmpty() ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void requestBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            render();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            note("그 화면을 열지 못했어요. 설정 앱에서 직접 찾아 주세요.");
        }
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
