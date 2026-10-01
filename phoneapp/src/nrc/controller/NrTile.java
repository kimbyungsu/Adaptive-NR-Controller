package nrc.controller;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 빠른 설정 타일 "5G 자동"(DESIGN §5.13). 탭 = 자동 제어 켜기·끄기. 길게 누르면 상세 화면(시스템이 MainActivity를 연다).
 * 5G 우선/LTE 우선 선택은 여기서 바꾸지 않는다(삼성 설정에서만, USER 무쓰기 원칙).
 */
public final class NrTile extends TileService {
    /**
     * 패널에 추가된 순간. 활성 타일은 앱이 부를 때만 다시 칠해지므로(패널을 내린다고 저절로 칠하지 않음) 여기서 현재 상태를 칠한다.
     * 기기 확인(09-30): 이게 없을 때 추가 직후 타일이 앱 상태(켜짐)와 달리 꺼짐으로 보였다.
     */
    @Override
    public void onTileAdded() {
        AppState.setTileAdded(this, true);
        show();
    }

    /** 패널에서 빠졌다(시작하기 체크리스트의 타일 단계를 다시 "아직"으로). */
    @Override
    public void onTileRemoved() {
        AppState.setTileAdded(this, false);
    }

    /**
     * 패널에 있는 타일을 칠할 때 불린다(패널을 볼 때, 앱이 갱신을 요청할 때). 어느 쪽이든 타일이 패널에 있을 때만이라
     * 추가된 것으로 기록한다. 패널을 내린다고 늘 불리는 것은 아니라(활성 타일), 확실한 확인은 '타일 추가' 요청 결과로 한다.
     */
    @Override
    public void onStartListening() {
        AppState.setTileAdded(this, true);
        show();
    }

    @Override
    public void onClick() {
        if (AppState.auto(this) && !ControllerService.running) {
            ControllerService.ensure(this); // "멈춤" 상태의 탭 = 다시 시작(끄기 아님)
        } else {
            AppState.setAuto(this, !AppState.auto(this));
            ControllerService.ensure(this);
        }
        show();
    }

    private void show() {
        Tile t = getQsTile();
        if (t == null) return;
        TileText s = AppState.tile(this);
        t.setLabel(TileText.LABEL);
        t.setSubtitle(s.subtitle);
        t.setState(s.look == TileText.Look.ACTIVE ? Tile.STATE_ACTIVE
                : s.look == TileText.Look.INACTIVE ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.updateTile();
    }

    /**
     * 사용자에게 타일 추가를 묻는 시스템 창(안드로이드 13+ 공식 방법, 추가 여부는 사용자가 정한다). 결과 글을 done에 준다.
     * 안드로이드 12에는 이 창이 없어 패널 편집 안내만 준다.
     */
    static void requestAdd(android.app.Activity a, java.util.function.Consumer<String> done) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            done.accept("이 폰에서는 추가 창을 띄울 수 없어요. 빠른 설정 패널의 편집(연필) 버튼에서 '5G 자동'을 끌어다 놓아 주세요.");
            return;
        }
        android.app.StatusBarManager sbm = a.getSystemService(android.app.StatusBarManager.class);
        sbm.requestAddTileService(new android.content.ComponentName(a, NrTile.class), TileText.LABEL,
                android.graphics.drawable.Icon.createWithResource(a, R.drawable.nrc_tile), a.getMainExecutor(), result -> {
                    String msg;
                    switch (result) {
                        case android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED:
                            AppState.setTileAdded(a, true);
                            msg = "타일을 추가했어요.";
                            break;
                        case android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED:
                            AppState.setTileAdded(a, true);
                            msg = "타일이 이미 있어요.";
                            break;
                        case android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED:
                            msg = "추가하지 않았어요.";
                            break;
                        default:
                            msg = "이 폰에서는 추가 창을 띄울 수 없어요(코드 " + result
                                    + "). 빠른 설정 패널의 편집(연필) 버튼에서 '5G 자동'을 끌어다 놓아 주세요.";
                    }
                    AppState.refreshTile(a);
                    done.accept(msg);
                });
    }

    /** 타일을 길게 눌렀을 때 시스템이 여는 화면과 같은 곳. */
    static Intent details(android.content.Context c) {
        return new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
