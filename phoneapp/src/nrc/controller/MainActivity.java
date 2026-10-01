package nrc.controller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
    private static final int TAB_NOW = 0, TAB_LOG = 1, TAB_DETAIL = 2, TAB_SETTINGS = 3, TAB_HELP = 4;
    private static final String[] TAB_NAMES = {"지금", "활동 기록", "상세", "설정", "도움말"};
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
    private final Button[] tabs = new Button[TAB_NAMES.length];

    // 지금 칸
    private TextView headline;
    private TextView nowLines;
    private TextView nowNext;
    private TextView today;
    private Button selfTestButton;
    private TextView selfTestOut;
    private Button liftButton;
    private Button setupButton;
    private TextView liftOut;
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
        for (int i = 0; i < TAB_NAMES.length; i++) {
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
        if (!AppState.startShown(this)) {
            AppState.markStartShown(this);
            if (StartSteps.of(SetupActivity.facts(this, null, null)).remaining > 0) {
                startActivity(new Intent(this, SetupActivity.class));
            }
        }
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
        for (int i = 0; i < TAB_NAMES.length; i++) tabs[i].setTypeface(i == which ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
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
            case TAB_HELP:
                buildHelp();
                break;
            default:
                buildSettings();
                break;
        }
        render();
    }

    /** 도움말: 경우별 그림 설명(앱 안에 담긴 help.html, 인터넷 없이 보인다). 배포용 설명서와 같은 원본. */
    private void buildHelp() {
        WebView w = new WebView(this);
        w.getSettings().setJavaScriptEnabled(false);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if ("nrc".equals(req.getUrl().getScheme()) && "start".equals(req.getUrl().getHost())) {
                    startActivity(new Intent(MainActivity.this, SetupActivity.class));
                    return true;
                }
                return false; // 문서 안 이동(#절)만 허용
            }
        });
        w.loadUrl("file:///android_asset/help.html");
        body.addView(w, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void buildNow() {
        setupButton = button("", v -> startActivity(new Intent(this, SetupActivity.class)));
        body.addView(setupButton);
        headline = text("", 22);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(headline);
        nowLines = text("", 15);
        body.addView(nowLines);
        nowNext = text("", 15);
        nowNext.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(nowNext);
        liftButton = button("남은 5G 막음 풀기", v -> confirmLiftExternal());
        body.addView(liftButton);
        liftOut = text("", 14);
        body.addView(liftOut);
        body.addView(section("오늘의 활동(자정부터)"));
        today = text("", 15);
        body.addView(today);
        body.addView(section("자가 점검"));
        body.addView(text("5G를 잠깐 막았다가 되돌려, 이 폰에서 앱이 실제로 5G/LTE를 바꿀 수 있는지 확인해요. "
                + "연결이 1~2초씩 두 번 끊길 수 있어요.", 14));
        selfTestButton = button("자가 점검 시작", v -> confirmSelfTest());
        body.addView(selfTestButton);
        selfTestOut = text("", 14);
        selfTestOut.setText(selfTestText);
        body.addView(selfTestOut);
        body.addView(text("참고: 상단바의 5G/LTE 표시는 폰이 스스로 바꾸기도 해요"
                + "(예: 폰을 안 쓸 때도 5G로 보이고, 막 쓰기 시작하면 잠깐 LTE로 보임). 앱이 바꾼 것은 활동 기록에 [앱이 바꿈]으로만 표시돼요.", 13));
    }

    private void buildLog() {
        body.addView(text("최근 24시간 · 최신 것이 위. [폰 상태] 폰·기지국이 한 일 · [앱 판단] 앱이 내린 결론 · [앱이 바꿈] 앱이 5G/LTE를 바꾼 일과 그 결과(바꾸려다 실패한 것도 '실패'로 적어요). "
                + "화면이 꺼진 동안의 5G 붙음·끊김은 셈에 넣지 않아 목록에도 싣지 않아요.", 13));
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
        body.addView(button("시작하기 다시 보기(처음 설정·마무리)", v -> startActivity(new Intent(this, SetupActivity.class))));
        body.addView(button("이 폰에서 되는지 점검·결과 보내기", v -> showSupportReport()));
        body.addView(button("빠른 설정 패널에 '5G 자동' 타일 추가", v -> requestTile()));
        body.addView(button("배터리 최적화에서 빼기(오래 살아 있게)", v -> requestBatteryExemption()));
        body.addView(button("이 앱 알림 설정 열기", v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
        body.addView(text("앱 알림을 꺼 두면 알림 창에 이 앱의 줄이 보이지 않고, 동작은 그대로 계속돼요.\n"
                + "5G 우선/LTE 우선은 삼성 설정(연결 → 모바일 네트워크 → 네트워크 모드)에서 골라요.", 13));
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
            case TAB_HELP:
                break; // 도움말은 고정 내용
            default:
                renderSettings();
                break;
        }
    }

    private void renderNow() {
        Live l = ControllerService.live();
        Radio r = Radio.open(this);
        long own = r == null ? -1 : Engine.ownMask(this, r.sub);
        boolean leftover = own >= 0; // 엔진이 없어도 남은 막음이 있을 수 있다
        // 앱이 건 것이 아닌 5G 막음: 엔진이 있으면 엔진의 주기 판정, 없으면 지금 읽어서
        boolean external = l != null ? l.carrierExternal
                : r != null && r.privileged()
                && CarrierPlan.classify(r.read(Radio.CARRIER), own) == CarrierPlan.Carrier.EXTERNAL;
        NowText t = NowText.of(l, AppState.tile(this), SystemClock.elapsedRealtime(), leftover, external);
        liftButton.setVisibility(external ? View.VISIBLE : View.GONE);
        int left = StartSteps.of(SetupActivity.facts(this, null, null)).remaining;
        setupButton.setText("시작하기 — " + left + "단계 남음(눌러서 이어 하기)");
        setupButton.setVisibility(left > 0 ? View.VISIBLE : View.GONE);
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
        b.append("· LTE로 쉰 횟수: ").append(s.rests).append("번 · 모두 ").append(rest / 60_000).append("분 ")
                .append(rest / 1000 % 60).append("초\n");
        b.append("· 5G 다시 확인: ").append(s.probes).append("번(통과 ").append(s.pass).append(" · 실패 ").append(s.fail)
                .append(" · 판정 못 함 ").append(s.undecided).append(")\n");
        b.append("· 쉬기 기준에 넣은 5G 끊김: ").append(s.countedDrops).append("번\n");
        if (lastAction < 0) {
            b.append("· 앱이 5G/LTE를 바꾼 적: 아직 없음");
        } else {
            long ago = Math.max(0, (nowWall - lastAction) / 60_000);
            b.append("· 마지막으로 앱이 바꾼 때: ").append(NowText.clock(lastAction)).append(lastAction < dayStart ? "(어제 이전)" : "")
                    .append(" · ").append(ago).append("분 전 · ").append(lastText);
        }
        return b.toString();
    }

    private void renderLog() {
        List<Timeline.Entry> es = ControllerService.timeline(this).recent(System.currentTimeMillis() - 24L * 3_600_000);
        if (es.isEmpty()) {
            logText.setText("아직 기록이 없어요.");
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
            b.append("통신사 인정(5G/LTE 전환 권한): ").append(r.privileged() ? "예" : "아니요(처음 설정 필요)").append('\n');
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
                + "\n5G/LTE 전환 권한: " + (r == null ? "SIM 확인 중" : (r.privileged() ? "있음" : "없음 → 처음 설정 필요"))
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
                .setMessage("5G를 잠깐 막았다가 되돌려요. 그동안 연결이 1~2초씩 두 번 끊길 수 있어요. 진행할까요?")
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
            addTestLine("앱이 떠 있지 않아 점검할 수 없음", false);
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

    // ================================================================ 남은 5G 막음 풀기(사용자 요청)

    private void confirmLiftExternal() {
        new AlertDialog.Builder(this)
                .setTitle("남은 5G 막음 풀기")
                .setMessage("이 막음은 앱이 건 것으로 확인되지 않아요. 통신사 앱이 건 막음일 수도 있고, 앱을 지웠다 다시 설치해 "
                        + "앱의 기록이 없어진 막음일 수도 있어요. 풀면 5G가 다시 허용돼요(연결이 1~2초 끊길 수 있어요). 풀까요?")
                .setPositiveButton("풀기", (dlg, w) -> startLiftExternal())
                .setNegativeButton("취소", null)
                .show();
    }

    private void startLiftExternal() {
        liftOut.setText("");
        liftButton.setEnabled(false);
        boolean posted = ControllerService.liftExternal(new Engine.TestSink() {
            @Override
            public void line(String text, boolean ok) {
                main.post(() -> liftOut.setText((ok ? "✓ " : "✗ ") + text));
            }

            @Override
            public void done(boolean pass) {
                main.post(() -> {
                    liftButton.setEnabled(true);
                    render();
                });
            }
        });
        if (!posted) {
            liftOut.setText("✗ 앱이 떠 있지 않아 풀 수 없어요");
            liftButton.setEnabled(true);
        }
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
    /** 이 폰에서 되는지 점검 결과를 한 화면에 모아 보여 주고, [복사]로 보낼 수 있게 한다(개인정보 없음). */
    private void showSupportReport() {
        SupportCheck.Info f = new SupportCheck.Info();
        try {
            f.appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // 버전 못 읽으면 모름
        }
        f.manufacturer = Build.MANUFACTURER;
        f.model = Build.MODEL;
        f.device = Build.DEVICE;
        f.androidRelease = Build.VERSION.RELEASE;
        f.sdk = Build.VERSION.SDK_INT;
        f.securityPatch = Build.VERSION.SECURITY_PATCH;
        f.oneUi = oneUiVersion();
        f.setupResult = AppState.setupResult(this);
        Radio r = Radio.open(this);
        f.privileged = r != null && r.privileged();
        f.simCount = r == null ? -1 : r.activeSims();
        android.telephony.TelephonyManager tm = getSystemService(android.telephony.TelephonyManager.class);
        if (tm != null) {
            String op = tm.getSimOperatorName();
            if (op == null || op.isEmpty()) op = tm.getNetworkOperatorName();
            f.carrier = op;
            // 허용망 비트마스크 방식 지원 여부는 공개 상수가 없어 넣지 않는다(보고문에 "모름")
        }
        SupportCheck sc = SupportCheck.of(f);
        TextView tv = text(sc.report, 13);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(sc.known ? (sc.supported ? "이 폰: 됩니다 ✓" : "이 폰: 안 됩니다") : "이 폰: 아직 모름")
                .setView(sv)
                .setPositiveButton("복사", (dlg, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("5G 자동 제어 점검", sc.report));
                        if (settingsStatus != null) settingsStatus.setText("점검 결과를 복사했어요. 붙여넣어 보내 주세요(개인정보 없음).");
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    /** 삼성 One UI 버전(ro.build.version.oneui, 있으면 "6.1"처럼; 못 읽으면 null). */
    private static String oneUiVersion() {
        try {
            @SuppressWarnings("unchecked")
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String raw = (String) sp.getMethod("get", String.class).invoke(null, "ro.build.version.oneui");
            if (raw == null || raw.isEmpty()) return null;
            int v = Integer.parseInt(raw.trim());
            // 삼성 표기: 60101 → 6.1.1, 60000 → 6.0
            int major = v / 10000, minor = (v / 100) % 100, patch = v % 100;
            StringBuilder b = new StringBuilder().append(major).append('.').append(minor);
            if (patch != 0) b.append('.').append(patch);
            return b.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void requestTile() {
        NrTile.requestAdd(this, msg -> {
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
