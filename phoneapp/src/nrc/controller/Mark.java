package nrc.controller;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

/**
 * 상단바 표시를 "다른 앱 위에 표시" 창으로 띄운다(DESIGN §5.13).
 * - 누를 수 없는 창이다(터치는 아래로 지나간다). 안드로이드 12부터 다른 앱 위 창은 불투명도 0.8 이하일 때만
 *   터치가 지나가므로 창 불투명도를 0.8로 둔다.
 * - 상단바가 사라지는 전체 화면에서는 숨긴다(창이 받는 화면 가장자리 정보로 판단).
 */
final class Mark {
    static final float WINDOW_ALPHA = 0.8f;

    private final Context ctx;
    private final WindowManager wm;
    private MarkView view;
    private WindowManager.LayoutParams lp;
    private boolean barVisible = true;

    Mark(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** 표시를 띄우거나 고친다. 허락이 없으면 false. */
    boolean show(MarkStyle style, int sizeDp, int xDp, int yDp) {
        if (!allowed(ctx)) {
            hide();
            return false;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        int size = Math.max(2, Math.round(sizeDp * d));
        int x = Math.round(xDp * d);
        int y = yDp >= 0 ? Math.round(yDp * d) : Math.max(0, (statusBarHeight() - size) / 2);
        if (view == null) {
            view = new MarkView(ctx);
            lp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.alpha = WINDOW_ALPHA;
            lp.setTitle("nrc-mark");
            lp.x = x;
            lp.y = y;
            view.setStyle(style);
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                barVisible = insets.isVisible(WindowInsets.Type.statusBars());
                v.setVisibility(barVisible ? View.VISIBLE : View.INVISIBLE);
                return insets;
            });
            wm.addView(view, lp);
            return true;
        }
        view.setStyle(style);
        if (lp.width != size || lp.height != size || lp.x != x || lp.y != y) {
            lp.width = size;
            lp.height = size;
            lp.x = x;
            lp.y = y;
            wm.updateViewLayout(view, lp);
        }
        return true;
    }

    void hide() {
        if (view == null) return;
        try {
            wm.removeView(view);
        } catch (RuntimeException ignored) {
            // 이미 떨어진 창이면 무시
        }
        view = null;
        lp = null;
    }

    boolean shown() {
        return view != null;
    }

    /** 상단바가 보이는지(마지막으로 받은 화면 가장자리 정보 기준). 시험 화면 표시용. */
    boolean barVisible() {
        return barVisible;
    }

    private int statusBarHeight() {
        int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? ctx.getResources().getDimensionPixelSize(id) : Math.round(24 * ctx.getResources().getDisplayMetrics().density);
    }
}
