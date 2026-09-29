package nrc.controller;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 표시 설정 화면: 사용자가 상단바 표시의 크기·위치를 직접 고르고, 모양·색을 미리 본다(사용자 요청 2026-09-30).
 * 바꾸는 즉시 상단바 표시가 따라 바뀐다(이 화면은 전체 화면이 아니라 상단바가 보인다).
 * 시스템이 띄우는 "다른 앱 위에 표시됨" 안내를 끄는 설정 화면도 열어 준다(끄는 것은 사용자가 직접 한다).
 */
public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView status;
    private TextView sizeText;
    private TextView xText;
    private TextView yText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.of(this);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("NR 컨트롤러 — 표시 설정");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        root.addView(button("① '다른 앱 위에 표시' 허락 화면 열기", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))));
        root.addView(button("② 표시 켜기", v -> {
            startForegroundService(new Intent(this, StatusService.class));
            refresh();
        }));

        root.addView(button("③ 시스템 안내 '다른 앱 위에 표시됨' 끄는 화면 열기", v -> openOverlayNoticeSettings()));
        root.addView(button("④ 이 앱 알림 설정 열기", v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
        TextView tip = label("알림 창의 그 안내를 그냥 누르면 표시 허락을 끄는 화면이 열립니다. 거기서 끄면 상단바 표시도 사라지니, "
                + "③ 버튼을 쓰거나 안내를 길게 눌러 알림만 끄세요.");
        root.addView(tip);

        root.addView(label("미리 보기 — 모양"));
        root.addView(row(
                button("원 (5G 우선)", v -> put(Prefs.PREVIEW_SHAPE, MarkStyle.Shape.CIRCLE.name())),
                button("사각형 (LTE 우선)", v -> put(Prefs.PREVIEW_SHAPE, MarkStyle.Shape.SQUARE.name()))));
        root.addView(label("미리 보기 — 색"));
        root.addView(row(
                button("초록 (모바일)", v -> put(Prefs.PREVIEW_TONE, MarkStyle.Tone.GREEN.name())),
                button("흰색 (Wi-Fi)", v -> put(Prefs.PREVIEW_TONE, MarkStyle.Tone.WHITE.name())),
                button("빨강 (문제)", v -> put(Prefs.PREVIEW_TONE, MarkStyle.Tone.RED.name()))));

        sizeText = label("");
        root.addView(sizeText);
        root.addView(row(button("작게", v -> step(Prefs.SIZE, Prefs.size(prefs), -1, 2, 24)),
                button("크게", v -> step(Prefs.SIZE, Prefs.size(prefs), +1, 2, 24))));
        xText = label("");
        root.addView(xText);
        root.addView(row(button("◀ 10", v -> step(Prefs.X, Prefs.x(prefs), -10, 0, 400)),
                button("◀ 1", v -> step(Prefs.X, Prefs.x(prefs), -1, 0, 400)),
                button("1 ▶", v -> step(Prefs.X, Prefs.x(prefs), +1, 0, 400)),
                button("10 ▶", v -> step(Prefs.X, Prefs.x(prefs), +10, 0, 400))));
        yText = label("");
        root.addView(yText);
        root.addView(row(button("▲ 1", v -> step(Prefs.Y, currentY(), -1, 0, 60)),
                button("▼ 1", v -> step(Prefs.Y, currentY(), +1, 0, 60)),
                button("가운데", v -> {
                    prefs.edit().putInt(Prefs.Y, -1).apply();
                    refresh();
                })));

        root.addView(button("표시 끄기", v -> {
            stopService(new Intent(this, StatusService.class));
            refresh();
        }));
        root.addView(button("크기·위치 처음값으로", v -> {
            prefs.edit().remove(Prefs.SIZE).remove(Prefs.X).remove(Prefs.Y).apply();
            refresh();
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 알림 허락이 바뀌었을 수 있다: 서비스가 떠 있으면 알림 한 줄을 다시 올리게 한다
        if (StatusService.running) startForegroundService(new Intent(this, StatusService.class));
        refresh();
    }

    /**
     * 시스템(안드로이드)이 이 앱 몫으로 만든 "다른 앱 위에 표시됨" 안내 채널의 설정 화면을 연다.
     * 그 화면이 안 열리면 안드로이드 시스템 앱의 알림 설정 화면을, 그것도 안 되면 안내 문구를 보여 준다.
     */
    private void openOverlayNoticeSettings() {
        Intent ch = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, "android")
                .putExtra(Settings.EXTRA_CHANNEL_ID, OVERLAY_NOTICE_CHANNEL_PREFIX + getPackageName());
        Intent app = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, "android");
        for (Intent i : new Intent[]{ch, app}) {
            try {
                startActivity(i);
                return;
            } catch (RuntimeException ignored) {
                // 다음 방법으로
            }
        }
        status.setText("설정 화면을 열 수 없습니다. 알림 창의 '다른 앱 위에 표시됨' 안내를 길게 눌러 알림을 꺼 주세요.");
    }

    /** 안드로이드 WindowManager가 만드는 안내 채널 이름 앞부분(AOSP AlertWindowNotification, 기기 dumpsys로 확인). */
    static final String OVERLAY_NOTICE_CHANNEL_PREFIX = "com.android.server.wm.AlertWindowNotification - ";

    /** 세로 위치가 "가운데"(-1)면 1씩 옮기기 전에 지금 가운데 값에서 시작한다. */
    private int currentY() {
        int y = Prefs.y(prefs);
        if (y >= 0) return y;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        float d = getResources().getDisplayMetrics().density;
        int barDp = id > 0 ? Math.round(getResources().getDimensionPixelSize(id) / d) : 24;
        return Math.max(0, (barDp - Prefs.size(prefs)) / 2);
    }

    private void step(String key, int cur, int delta, int min, int max) {
        prefs.edit().putInt(key, Math.max(min, Math.min(max, cur + delta))).apply();
        refresh();
    }

    private void put(String key, String value) {
        prefs.edit().putString(key, value).apply();
        refresh();
    }

    private void refresh() {
        boolean overlay = Mark.allowed(this);
        boolean notes = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        status.setText("다른 앱 위에 표시 허락: " + (overlay ? "켜짐" : "꺼짐 → ①을 눌러 켜 주세요")
                + "\n알림 허락: " + (notes ? "켜짐" : "꺼짐(표시는 알림과 상관없이 뜹니다)")
                + "\n미리 보기: " + Prefs.preview(prefs));
        sizeText.setText("크기: " + Prefs.size(prefs) + "dp");
        xText.setText("가로 위치(왼쪽 끝부터): " + Prefs.x(prefs) + "dp");
        int y = Prefs.y(prefs);
        yText.setText("세로 위치(위 끝부터): " + (y < 0 ? "상단바 가운데" : y + "dp"));
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, Math.round(12 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }
}
