package nrc.controller;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 상세 화면(타일을 길게 누르거나 앱을 열면 보인다). 지금 상태, 자동 제어 켜기·끄기, 타일 추가, 알림 설정.
 * 알림 허락은 먼저 묻지 않는다(사용자가 앱 알림을 꺼 두면 알림 창에 줄이 없는 것이 이 앱의 기본 모습이다).
 */
public final class MainActivity extends Activity {
    private TextView status;
    private Button autoButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("5G 자동 제어");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        autoButton = button("", v -> {
            AppState.setAuto(this, !AppState.auto(this));
            ControllerService.ensure(this);
            refresh();
        });
        root.addView(autoButton);
        root.addView(button("빠른 설정 패널에 '5G 자동' 타일 추가", v -> requestTile()));
        root.addView(button("이 앱 알림 설정 열기", v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
        TextView tip = new TextView(this);
        tip.setText("앱 알림을 꺼 두면 알림 창에 이 앱의 줄이 보이지 않고, 동작은 그대로 계속됩니다.\n"
                + "5G 우선/LTE 우선은 삼성 설정(연결 → 모바일 네트워크 → 네트워크 모드)에서 고릅니다.");
        tip.setPadding(0, pad, 0, 0);
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ControllerService.ensure(this); // 화면이 보이는 동안이라 전면 서비스 시작이 허용된다
        refresh();
    }

    private void refresh() {
        TileText t = AppState.tile(this);
        status.setText("지금 상태: " + t.subtitle);
        autoButton.setText(AppState.auto(this) ? "자동 제어 끄기" : "자동 제어 켜기");
    }

    /** 사용자에게 타일 추가를 묻는 시스템 창을 띄운다(안드로이드 13+ 공식 방법, 추가 여부는 사용자가 정한다). */
    private void requestTile() {
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
                    status.setText(msg + "\n지금 상태: " + AppState.tile(this).subtitle);
                });
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }
}
