package nrc.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 개발 시험 명령(PC에서만): 매니페스트에서 보내는 쪽에 DUMP 권한을 요구한다(shell은 보유, 일반 앱은 없음).
 * op=cooldown: 끊김 조건 없이 2분 쉬기(가드·예산·정착 규칙은 평소와 같다, daemon의 test-cooldown과 같음).
 * op=status: 지금 상태 한 줄.
 * 사용자 칸(USER)을 쓰는 명령은 없다.
 */
public final class TestCommand extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String op = intent.getStringExtra("op");
        if ("cooldown".equals(op)) {
            setResultData(ControllerService.testCooldown() ? "cooldown requested" : "no engine");
        } else if ("status".equals(op) || op == null) {
            Radio r = Radio.open(ctx);
            setResultData("tile=" + AppState.tile(ctx) + " engine=" + ControllerService.engineRunning()
                    + (r == null ? " sim=none" : " sub=" + r.sub + " priv=" + r.privileged() + " user=" + r.read(Radio.USER)
                    + " carrier=" + r.read(Radio.CARRIER) + " own=" + Engine.ownMask(ctx, r.sub)));
        } else {
            setResultData("rejected op=" + op);
        }
    }
}
