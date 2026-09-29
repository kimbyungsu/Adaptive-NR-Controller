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
        show();
    }

    @Override
    public void onStartListening() {
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

    /** 타일을 길게 눌렀을 때 시스템이 여는 화면과 같은 곳. */
    static Intent details(android.content.Context c) {
        return new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
