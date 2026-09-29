package nrc.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 시험 명령 수신: op=check|block|unblock|keep, reason=1(POWER)|2(CARRIER). 실행은 이 앱 프로세스가 한다.
 * 보낼 수 있는 쪽은 매니페스트의 DUMP 권한 보유자다(shell은 보유, 일반 앱은 없음, pm grant로 받은 앱은 가능).
 * USER(0)·ENABLE_2G(3) 사유는 쓰지 않는다.
 */
public final class Cmd extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String op = intent.getStringExtra("op");
        if (op != null && !"check".equals(op) && !"block".equals(op) && !"unblock".equals(op) && !"keep".equals(op)) {
            setResultData("rejected op=" + op);
            return;
        }
        if ("keep".equals(op)) {
            try {
                ctx.startForegroundService(new Intent(ctx, Keeper.class));
                setResultData("keep started");
            } catch (Throwable e) {
                setResultData("keep failed=" + e);
            }
            return;
        }
        int reason = intent.getIntExtra("reason", 2);
        if (reason != 1 && reason != 2) {
            setResultData("rejected reason=" + reason);
            return;
        }
        String line = Probe.run(ctx, op == null ? "check" : op, reason, "cmd");
        setResultData(line);
    }
}
