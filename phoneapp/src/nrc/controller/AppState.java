package nrc.controller;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

/**
 * 서비스가 쓰고 타일·상세 화면이 읽는 현재 상태(앱 전용 저장소). 바뀌면 타일에 갱신을 요청한다
 * (활성 타일이라 패널이 닫혀 있어도 요청할 수 있다).
 */
final class AppState {
    static final String FILE = "state";
    static final String AUTO = "auto";
    static final String MODE = "mode";
    static final String WIFI = "wifi";
    static final String PHASE = "phase";
    static final String PROBLEM = "problem";
    /** 서비스가 뜨거나 내려간 횟수. 값 자체는 쓰지 않고, 바뀌었다는 신호로 화면(저장소 구독)을 다시 그리게 한다. */
    static final String ALIVE_SEQ = "aliveSeq";

    private AppState() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean auto(Context c) {
        return prefs(c).getBoolean(AUTO, true);
    }

    static TileText tile(Context c) {
        SharedPreferences p = prefs(c);
        return TileText.of(p.getBoolean(AUTO, true), p.getString(PROBLEM, null), p.getInt(MODE, TileText.MODE_UNKNOWN),
                p.getBoolean(WIFI, false), p.getString(PHASE, null), ControllerService.running);
    }

    static void setAuto(Context c, boolean on) {
        prefs(c).edit().putBoolean(AUTO, on).apply();
        refreshTile(c);
    }

    /** 서비스가 관찰한 값을 한꺼번에 쓴다. 바뀐 것이 있을 때만 타일 갱신을 요청한다. */
    static void observed(Context c, int mode, boolean wifi, String phase, String problem) {
        SharedPreferences p = prefs(c);
        boolean same = p.getInt(MODE, TileText.MODE_UNKNOWN) == mode && p.getBoolean(WIFI, false) == wifi
                && eq(p.getString(PHASE, null), phase) && eq(p.getString(PROBLEM, null), problem);
        if (same) return;
        p.edit().putInt(MODE, mode).putBoolean(WIFI, wifi).putString(PHASE, phase).putString(PROBLEM, problem).apply();
        refreshTile(c);
    }

    /**
     * 서비스가 뜨거나 내려갔다. 생존 여부는 저장하지 않는다(프로세스가 죽으면 낡으므로 ControllerService.running을 본다).
     * 대신 저장소에 신호를 남겨 상세 화면이 다시 그리게 하고, 타일에도 갱신을 요청한다.
     */
    static void aliveChanged(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putInt(ALIVE_SEQ, p.getInt(ALIVE_SEQ, 0) + 1).apply();
        refreshTile(c);
    }

    static void refreshTile(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, NrTile.class));
        } catch (RuntimeException ignored) {
            // 타일이 패널에 없으면 요청이 거절될 수 있다. 패널에 추가되면 그때 최신 상태를 읽는다
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
