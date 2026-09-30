package nrc.controller;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 처음 설정 화면(PC 없이, 사용자 결정 2026-09-30). 누가 무엇을 하는지(사용자/폰/앱) 적고, 지금 상태를 1초마다 다시 읽는다.
 * 코드 입력은 두 가지: 알림(설정 화면 위에서 알림창을 내려 입력) · 이 화면 입력칸(화면을 둘로 나눴을 때).
 * 코드 창이 열려 있는지는 이 화면이 떠 있는 동안 mDNS로 계속 찾는다.
 */
public final class SetupActivity extends Activity implements Setup.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private float d;
    private TextView status;
    private TextView progress;
    private EditText codeField;
    private EditText portField;
    private Button connectButton;
    private LinearLayout doneBox;
    private AdbFind pairFind;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, 1000);
        }
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

        TextView title = text("처음 설정 (PC 없이)", 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);
        body.addView(text("앱이 폰이 인정하는 목록에 이 앱 줄 하나를 덧붙여, 폰이 이 앱을 '5G/LTE를 바꿀 수 있는 앱'으로 대하게 해요. "
                + "기존 줄은 그대로 둬요. 한 번 하면 재부팅해도 다시 할 필요가 없어요(SIM 교체·삼성 업데이트 뒤에만 다시).", 14));

        body.addView(section("지금 상태"));
        status = text("", 15);
        body.addView(status);

        body.addView(section("1. 개발자 옵션 켜기 (처음 한 번)"));
        body.addView(text("사용자: 설정 → 휴대전화 정보 → 소프트웨어 정보에서 '빌드번호'를 7번 눌러요. "
                + "이미 켜져 있으면 넘어가요.", 14));
        body.addView(button("휴대전화 정보 열기", v -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))));

        body.addView(section("2. 무선 디버깅 켜기"));
        body.addView(text("사용자: 폰을 Wi-Fi에 연결한 뒤, 설정 → 개발자 옵션 → '무선 디버깅'을 켜요(처음이면 '허용'). "
                + "핫스팟만 켠 상태나 모바일 데이터로는 켜지지 않아요.", 14));
        body.addView(button("개발자 옵션 열기", v -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))));

        body.addView(section("3. 6자리 코드 넣기"));
        body.addView(text("사용자: 무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 눌러 코드 창을 띄워요. "
                + "코드 창이 닫히면 그 코드는 못 써요(다른 앱으로 넘어가도 닫혀요). 그래서 코드 창을 띄운 채로 아래 둘 중 하나로 넣어요.", 14));
        body.addView(text("방법 가 · 알림으로: 먼저 아래 버튼을 눌러 알림을 띄워 두고, 코드 창을 띄운 채 화면 위에서 알림창을 내려 "
                + "이 앱 알림의 [코드 입력]에 6자리를 적어요.", 14));
        body.addView(button("코드 입력 알림 띄우기", v -> showNotice()));
        body.addView(text("방법 나 · 화면 나누기: 최근 앱 화면에서 이 앱 아이콘을 눌러 '분할 화면으로 열기'를 고른 뒤 설정 화면을 함께 띄우고, "
                + "코드를 여기에 적어요.", 14));
        codeField = new EditText(this);
        codeField.setHint("6자리 코드");
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        body.addView(codeField);
        portField = new EditText(this);
        portField.setHint("코드 창 포트(앱이 못 찾을 때만: 'IP 주소 및 포트'의 콜론 뒤 숫자)");
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        body.addView(portField);
        connectButton = button("연결", v -> startFromField());
        body.addView(connectButton);

        body.addView(section("진행"));
        progress = text("", 14);
        body.addView(progress);

        doneBox = new LinearLayout(this);
        doneBox.setOrientation(LinearLayout.VERTICAL);
        doneBox.addView(section("마무리"));
        doneBox.addView(text("앱: 무선 디버깅은 설정이 끝나면 앱이 꺼요. 개발자 옵션은 그대로 둬요.", 14));
        doneBox.addView(button("배터리 최적화에서 빼기(오래 살아 있게)", v -> requestBatteryExemption()));
        doneBox.addView(text("은행·결제 앱이 '개발자 옵션을 꺼 달라'며 실행을 막으면: 아래 버튼으로 개발자 옵션 화면을 열어 맨 위 스위치를 끄세요. "
                + "이 앱의 동작에는 영향이 없어요(권한은 개발자 옵션과 따로 남아요).", 14));
        doneBox.addView(button("개발자 옵션 화면 열기(끄려면)", v -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))));
        doneBox.addView(text("알림으로 코드를 넣으려고 앱 알림을 켰다면, 이제 다시 꺼도 돼요.", 14));
        doneBox.addView(button("이 앱 알림 설정 열기", v -> open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
        doneBox.setVisibility(View.GONE);
        body.addView(doneBox);

        pairFind = new AdbFind(this, AdbFind.PAIRING);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Setup.addListener(this);
        pairFind.start();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Setup.removeListener(this);
        main.removeCallbacks(tick);
        // 분할 화면에서는 설정 화면을 누르는 동안 이 화면이 멈춤 상태가 되므로 찾기는 끄지 않는다(화면을 닫을 때 끈다)
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pairFind.stop();
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

    // ================================================================

    private void render() {
        Radio r = Radio.open(this);
        boolean priv = r != null && r.privileged();
        Boolean adbWifi = Setup.adbWifiOn(this);
        int port = pairFind.port();
        StringBuilder sb = new StringBuilder();
        sb.append("5G/LTE 전환 권한: ").append(r == null ? "SIM 확인 중" : priv ? "있음 ✓ (설정할 필요 없음)" : "없음");
        sb.append("\nWi-Fi: ").append(Setup.onWifi(this) ? "연결됨" : "연결 안 됨");
        sb.append("\n개발자 옵션: ").append(Setup.devOptionsOn(this) ? "켜짐" : "꺼짐");
        sb.append("\n무선 디버깅: ").append(adbWifi == null ? "알 수 없음" : adbWifi ? "켜짐" : "꺼짐");
        sb.append("\n코드 창: ").append(port > 0 ? "열려 있음(앱이 찾음, 포트 " + port + ")" : "안 보임");
        status.setText(sb);

        StringBuilder p = new StringBuilder();
        List<String[]> h = Setup.history();
        for (String[] l : h) p.append("1".equals(l[1]) ? "✓ " : "✗ ").append(l[0]).append('\n');
        if (Setup.running()) p.append("… 진행 중\n");
        progress.setText(h.isEmpty() && !Setup.running() ? "아직 시작하지 않았어요" : p.toString().trim());
        connectButton.setEnabled(!Setup.running());
        doneBox.setVisibility(priv ? View.VISIBLE : View.GONE);
    }

    private void startFromField() {
        String code = codeField.getText().toString().trim();
        int port = -1;
        try {
            String ps = portField.getText().toString().trim();
            if (!ps.isEmpty()) port = Integer.parseInt(ps);
        } catch (NumberFormatException ignored) {
            port = -1;
        }
        if (port <= 0) port = pairFind.port();
        if (!Setup.start(this, code, port, -1)) return;
        render();
    }

    private void showNotice() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        if (!SetupNotice.showInput(this, "설정 화면에 코드 창을 띄운 채 여기서 [코드 입력]을 눌러 6자리를 적어 주세요.")) {
            progress.setText("앱 알림이 꺼져 있어요 — 여는 화면에서 이 앱 알림(또는 '처음 설정' 알림)을 켠 뒤 다시 눌러 주세요.");
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else {
            progress.setText("알림을 띄웠어요. 이제 무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 누르고, 알림창을 내려 코드를 적어 주세요.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) showNotice();
    }

    private void requestBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            progress.setText("이미 배터리 최적화에서 빠져 있어요 ✓");
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
            progress.setText("그 화면을 열지 못했어요. 설정 앱에서 직접 찾아 주세요.");
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

    private TextView section(String s) {
        TextView t = text(s, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(2));
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
