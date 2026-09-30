package nrc.controller;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 앱 화면(관측 화면, 2026-09-30 사용자 결정): 평소 상단바에는 아무것도 없고, 앱을 열면 컨트롤러가 무엇을 보고·판단하고·했는지 보인다.
 * 네 칸: 지금(상태·이유·다음 동작·오늘의 활동·자가 점검) / 활동 기록(관찰·판단·조치) / 상세(값) / 설정.
 * 화면이 보이는 동안 1초마다 다시 그린다(남은 시간·진행). 알림 허락은 먼저 묻지 않는다.
 */
public final class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int TAB_NOW = 0, TAB_LOG = 1, TAB_DETAIL = 2, TAB_SETTINGS = 3;
    private static final String[] TAB_NAMES = {"지금", "활동 기록", "상세", "설정"};
    private static final int C_OBS = 0xFF8A8A8A, C_JUDGE = 0xFF3B82F6, C_ACT = 0xFFF59E0B, C_OK = 0xFF16A34A, C_BAD = 0xFFDC2626;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, tab == TAB_LOG ? 5_000 : 1_000);
        }
    };
    private int tab = TAB_NOW;
    private float d;
    private LinearLayout body;
    private final Button[] tabs = new Button[4];

    // 지금 칸
    private TextView headline;
    private TextView nowLines;
    private TextView nowNext;
    private TextView today;
    private Button selfTestButton;
    private TextView selfTestOut;
    private SpannableStringBuilder selfTestText = new SpannableStringBuilder();
    private boolean selfTestRunning;
    // 활동 기록·상세·설정 칸
    private TextView logText;
    private TextView detailText;
    private TextView settingsStatus;
    private Button autoButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);
        TextView title = new TextView(this);
        title.setText("5G 자동 제어");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final int which = i;
            tabs[i] = button(TAB_NAMES[i], v -> show(which));
            tabRow.addView(tabs[i], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabRow);
        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, pad);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        show(TAB_NOW);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppState.prefs(this).registerOnSharedPreferenceChangeListener(this);
        ControllerService.ensure(this); // 화면이 보이는 동안이라 전면 서비스 시작이 허용된다
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override
    protected void onPause() {
        AppState.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        render();
    }

    // ================================================================ 칸 만들기

    private void show(int which) {
        tab = which;
        for (int i = 0; i < 4; i++) tabs[i].setTypeface(i == which ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        body.removeAllViews();
        switch (which) {
            case TAB_NOW:
                buildNow();
                break;
            case TAB_LOG:
                buildLog();
                break;
            case TAB_DETAIL:
                buildDetail();
                break;
            default:
                buildSettings();
                break;
        }
        render();
    }

    private void buildNow() {
        headline = text("", 22);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(headline);
        nowLines = text("", 15);
        body.addView(nowLines);
        nowNext = text("", 15);
        nowNext.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(nowNext);
        body.addView(section("오늘의 활동(자정부터)"));
        today = text("", 15);
        body.addView(today);
        body.addView(section("자가 점검"));
        body.addView(text("5G를 잠깐 막았다가 되돌려, 이 폰에서 컨트롤러가 실제로 5G/LTE를 바꿀 수 있는지 확인합니다. "
                + "연결이 1~2초씩 두 번 끊길 수 있습니다.", 14));
        selfTestButton = button("자가 점검 시작", v -> confirmSelfTest());
        body.addView(selfTestButton);
        selfTestOut = text("", 14);
        selfTestOut.setText(selfTestText);
        body.addView(selfTestOut);
        body.addView(text("참고: 상단바의 5G/LTE 표시는 폰이 데이터 사용 여부에 따라 스스로 바꾸기도 합니다"
                + "(예: 데이터를 안 쓸 때도 5G로 보이고, 막 쓰기 시작하면 잠깐 LTE로 보임). 앱이 바꾼 것은 활동 기록의 '조치'로만 표시됩니다.", 13));
    }

    private void buildLog() {
        body.addView(text("최근 24시간 · 최신 것이 위. [관찰] 폰·망이 한 일 · [판단] 앱이 내린 결론 · [조치] 앱이 실제로 바꾼 것. "
                + "화면이 꺼진 동안의 5G 붙음·떨어짐은 판단에 쓰지 않으므로 목록에 싣지 않습니다.", 13));
        logText = text("", 14);
        body.addView(logText);
    }

    private void buildDetail() {
        detailText = text("", 14);
        detailText.setTypeface(Typeface.MONOSPACE);
        body.addView(detailText);
    }

    private void buildSettings() {
        settingsStatus = text("", 15);
        body.addView(settingsStatus);
        autoButton = button("", v -> {
            AppState.setAuto(this, !AppState.auto(this));
            ControllerService.ensure(this);
            render();
        });
        body.addView(autoButton);
        body.addView(button("빠른 설정 패널에 '5G 자동' 타일 추가", v -> requestTile()));
        body.addView(button("배터리 최적화에서 빼기(오래 살아 있게)", v -> requestBatteryExemption()));
        body.addView(button("이 앱 알림 설정 열기", v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
        body.addView(text("앱 알림을 꺼 두면 알림 창에 이 앱의 줄이 보이지 않고, 동작은 그대로 계속됩니다.\n"
                + "5G 우선/LTE 우선은 삼성 설정(연결 → 모바일 네트워크 → 네트워크 모드)에서 고릅니다.", 13));
    }

    // ================================================================ 그리기

    private void render() {
        if (body == null) return;
        switch (tab) {
            case TAB_NOW:
                renderNow();
                break;
            case TAB_LOG:
                renderLog();
                break;
            case TAB_DETAIL:
                renderDetail();
                break;
            default:
                renderSettings();
                break;
        }
    }

    private void renderNow() {
        Live l = ControllerService.live();
        NowText t = NowText.of(l, AppState.tile(this), SystemClock.elapsedRealtime());
        headline.setText(t.headline);
        StringBuilder sb = new StringBuilder();
        for (String s : t.lines) sb.append("· ").append(s).append('\n');
        nowLines.setText(sb.toString().trim());
        nowNext.setText("다음: " + t.next);
        today.setText(todayText());
        selfTestButton.setEnabled(!selfTestRunning);
    }

    private String todayText() {
        DaySummary s = DaySummary.load(getSharedPreferences(Engine.SUMMARY_STORE, MODE_PRIVATE)
                .getString(Engine.SUMMARY_KEY, null));
        Calendar c = Calendar.getInstance();
        long nowWall = c.getTimeInMillis();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(nowWall));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long dayStart = c.getTimeInMillis();
        long lastAction = s.lastActionWall;
        String lastText = s.lastAction;
        s.roll(day, dayStart); // 어제 셈이 오늘로 보이지 않게(화면용 사본만)
        long rest = s.restMsNow(nowWall);
        StringBuilder b = new StringBuilder();
        b.append("· LTE로 쉰 횟수: ").append(s.rests).append("회 · 총 ").append(rest / 60_000).append("분 ")
                .append(rest / 1000 % 60).append("초\n");
        b.append("· 5G 재시험: ").append(s.probes).append("회(통과 ").append(s.pass).append(" · 실패 ").append(s.fail)
                .append(" · 판정 못 함 ").append(s.undecided).append(")\n");
        b.append("· 판단에 센 5G 끊김: ").append(s.countedDrops).append("번\n");
        if (lastAction < 0) {
            b.append("· 앱이 망을 바꾼 적: 아직 없음");
        } else {
            long ago = Math.max(0, (nowWall - lastAction) / 60_000);
            b.append("· 마지막 개입: ").append(NowText.clock(lastAction)).append(lastAction < dayStart ? "(어제 이전)" : "")
                    .append(" · ").append(ago).append("분 전 · ").append(lastText);
        }
        return b.toString();
    }

    private void renderLog() {
        List<Timeline.Entry> es = ControllerService.timeline(this).recent(System.currentTimeMillis() - 24L * 3_600_000);
        if (es.isEmpty()) {
            logText.setText("아직 기록이 없습니다.");
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int n = 0;
        for (Timeline.Entry e : es) {
            if (n++ >= 400) break;
            sb.append(NowText.clock(e.wall)).append("  ");
            int start = sb.length();
            sb.append("[").append(e.cat.label).append("]");
            int color = e.cat == Timeline.Cat.ACT ? C_ACT : e.cat == Timeline.Cat.JUDGE ? C_JUDGE : C_OBS;
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" ").append(e.text).append('\n');
        }
        logText.setText(sb);
    }

    private void renderDetail() {
        StringBuilder b = new StringBuilder();
        Radio r = Radio.open(this);
        if (r == null) {
            b.append("SIM: 아직 확인 안 됨\n");
        } else {
            b.append("SIM 번호(sub): ").append(r.sub).append('\n');
            b.append("통신사 인정: ").append(r.privileged() ? "예" : "아니요(처음 설정 필요)").append('\n');
            b.append("사용자 칸(설정 화면 값): ").append(mask(r.read(Radio.USER))).append('\n');
            b.append("통신사 칸(앱이 쉬기에 씀): ").append(mask(r.read(Radio.CARRIER))).append('\n');
            b.append("절전 칸: ").append(mask(r.read(Radio.POWER))).append('\n');
            b.append("2G 칸: ").append(mask(r.read(Radio.ENABLE_2G))).append('\n');
            long own = Engine.ownMask(this, r.sub);
            b.append("우리 막음 기록: ").append(own < 0 ? "없음" : String.valueOf(own)).append('\n');
        }
        Live l = ControllerService.live();
        long now = SystemClock.elapsedRealtime();
        if (l == null) {
            b.append("\n판단 엔진: 돌지 않음\n");
        } else {
            b.append("\n판단 상태: ").append(Words.state(l.state)).append(" (").append(l.state).append(")\n");
            b.append("상태 이유: ").append(Words.why(l.stateWhy)).append('\n');
            b.append("상태 시작: ").append(NowText.clock(l.stateSinceWall)).append('\n');
            b.append("판단 쉼: ").append(l.hold == null ? "없음" : Words.hold(l.hold)).append('\n');
            b.append("제어 불가: ").append(l.blocked == null ? "없음" : Words.blocked(l.blocked)).append('\n');
            b.append("화면: ").append(l.screen ? "켜짐" : "꺼짐").append(" · Wi-Fi: ").append(l.wifi ? "예" : "아니요").append('\n');
            b.append("실제 연결 5G 칸: ").append(l.nrActual ? "붙음" : "없음").append(" · 상단바 표시: ")
                    .append(NowText.display(l.display)).append('\n');
            b.append("LTE 신호(RSRP): ").append(sig(l.lteRsrp)).append(" dBm\n");
            b.append("5G 신호(RSRP/SINR): ").append(sig(l.nrRsrp)).append(" dBm / ").append(sig(l.nrSinr)).append(" dB\n");
            b.append("판단 창 안 센 끊김: ").append(l.drops).append(" / ").append(l.nDrop).append('\n');
            b.append("최근 1시간 전환: ").append(l.switchesHour).append(" / ").append(l.bHour).append("회\n");
            if (l.nextSwitchAt > now) b.append("다음 전환 가능까지: ").append(Words.mmss(l.nextSwitchAt - now)).append('\n');
            b.append("쉬는 시간 단계(길어짐): ").append(l.level).append('\n');
            b.append("문제: ").append(l.problem == null ? "없음" : l.problem).append('\n');
        }
        b.append("\n원본 기록 파일: /sdcard/Android/data/").append(getPackageName()).append("/files/logs/nrc.log");
        detailText.setText(b.toString());
    }

    private void renderSettings() {
        Radio r = Radio.open(this);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        settingsStatus.setText("지금 상태: " + AppState.tile(this).subtitle
                + "\n통신사 인정: " + (r == null ? "SIM 확인 중" : (r.privileged() ? "예" : "아니요 → 처음 설정 필요"))
                + "\n배터리 최적화 제외: " + (exempt ? "예" : "아니요"));
        autoButton.setText(AppState.auto(this) ? "자동 제어 끄기" : "자동 제어 켜기");
    }

    private static String mask(long m) {
        if (m < 0) return "읽기 실패";
        return m + (CarrierPlan.hasNr(m) ? " (5G 허용)" : " (5G 없음)");
    }

    private static String sig(int v) {
        return v == Integer.MAX_VALUE ? "-" : String.valueOf(v);
    }

    // ================================================================ 자가 점검

    private void confirmSelfTest() {
        new AlertDialog.Builder(this)
                .setTitle("자가 점검")
                .setMessage("5G를 잠깐 막았다가 되돌립니다. 그동안 연결이 1~2초씩 두 번 끊길 수 있습니다. 진행할까요?")
                .setPositiveButton("진행", (dlg, w) -> startSelfTest())
                .setNegativeButton("취소", null)
                .show();
    }

    private void startSelfTest() {
        selfTestRunning = true;
        selfTestText = new SpannableStringBuilder();
        selfTestOut.setText(selfTestText);
        render();
        boolean posted = ControllerService.selfTest(new Engine.TestSink() {
            @Override
            public void line(String text, boolean ok) {
                main.post(() -> addTestLine(text, ok));
            }

            @Override
            public void done(boolean pass) {
                main.post(() -> {
                    selfTestRunning = false;
                    render();
                });
            }
        });
        if (!posted) {
            addTestLine("앱의 상주 서비스가 떠 있지 않아 점검할 수 없음", false);
            selfTestRunning = false;
        }
    }

    private void addTestLine(String text, boolean ok) {
        int start = selfTestText.length();
        selfTestText.append(ok ? "✓ " : "✗ ");
        selfTestText.setSpan(new ForegroundColorSpan(ok ? C_OK : C_BAD), start, selfTestText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        selfTestText.append(text).append('\n');
        if (selfTestOut != null) selfTestOut.setText(selfTestText);
    }

    // ================================================================ 설정 칸 동작

    /** 안드로이드 공식 창으로 배터리 최적화 제외를 묻는다(허락 여부는 사용자가 정한다). */
    private void requestBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            render();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    /** 사용자에게 타일 추가를 묻는 시스템 창을 띄운다(안드로이드 13+ 공식 방법, 추가 여부는 사용자가 정한다). */
    private void requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // 안드로이드 12에는 추가 요청 창이 없다
            settingsStatus.setText("이 폰에서는 추가 창을 띄울 수 없습니다. 빠른 설정 패널의 편집(연필) 버튼에서 '5G 자동'을 끌어다 놓아 주세요.");
            return;
        }
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(new ComponentName(this, NrTile.class), TileText.LABEL,
                Icon.createWithResource(this, R.drawable.nrc_tile), getMainExecutor(), result -> {
                    String msg;
                    switch (result) {
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED:
                            msg = "타일을 추가했습니다.";
                            break;
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED:
                            msg = "타일이 이미 있습니다.";
                            break;
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED:
                            msg = "추가하지 않았습니다.";
                            break;
                        default:
                            msg = "이 폰에서는 추가 창을 띄울 수 없습니다(코드 " + result
                                    + "). 빠른 설정 패널의 편집(연필) 버튼에서 '5G 자동'을 끌어다 놓아 주세요.";
                    }
                    AppState.refreshTile(this);
                    if (settingsStatus != null) settingsStatus.setText(msg);
                });
    }

    // ================================================================ 작은 도구

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
