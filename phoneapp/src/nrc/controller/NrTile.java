package nrc.controller;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 빠른 설정 타일 "5G 자동"(DESIGN §5.13). 탭 = 자동 제어 켜기·끄기. 길게 누르면 상세 화면(시스템이 MainActivity를 연다).
 * 5G 우선/LTE 우선 선택은 여기서 바꾸지 않는다(삼성 설정에서만, USER 무쓰기 원칙).
 */
public final class NrTile extends TileService {
    @Override
    public void onStartListening() {
        show();
    }

    @Override
    public void onClick() {
        AppState.setAuto(this, !AppState.auto(this));
        ControllerService.ensure(this);
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

    /** 타일을 길게 눌렀을 때 시스템이 여는 화면과 같은 곳. */
    static Intent details(android.content.Context c) {
        return new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
