package nrc.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 개발 시험 명령(PC에서만): 매니페스트에서 보내는 쪽에 DUMP 권한을 요구한다(shell은 보유, 일반 앱은 없음).
 * op=cooldown: 끊김 조건 없이 2분 쉬기(가드·예산·정착 규칙은 평소와 같다, daemon의 test-cooldown과 같음).
 * op=status: 지금 상태 한 줄.
 * op=setup(code, pport, cport 선택): 처음 설정을 시작한다(화면의 [연결]과 같음, 기기 시험용).
 * op=setup_probe(같은 인자): 페어링·연결·목록 읽기만(덧붙이지 않음), 끝에 무선 디버깅 끄기. op=setup_log: 진행 줄.
 * 개발 시험 명령: PC가 코드 창의 6자리를 읽어 넣을 때만 쓴다.
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
        } else if ("setup".equals(op)) {
            boolean started = Setup.start(ctx, intent.getStringExtra("code"),
                    intent.getIntExtra("pport", -1), intent.getIntExtra("cport", -1));
            setResultData(started ? "setup started" : "setup busy");
        } else if ("setup_probe".equals(op)) {
            boolean started = Setup.probe(ctx, intent.getStringExtra("code"),
                    intent.getIntExtra("pport", -1), intent.getIntExtra("cport", -1));
            setResultData(started ? "probe started" : "setup busy");
        } else if ("setup_log".equals(op)) {
            StringBuilder sb = new StringBuilder(Setup.running() ? "running" : "idle result=" + Setup.lastResult());
            for (String[] l : Setup.history()) sb.append(" | ").append("1".equals(l[1]) ? "OK " : "NG ").append(l[0]);
            setResultData(sb.toString());
        } else {
            setResultData("rejected op=" + op);
        }
    }
}
