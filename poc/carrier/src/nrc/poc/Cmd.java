package nrc.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 시험 명령 수신: op=check|block|unblock|keep, reason=1(POWER)|2(CARRIER). 실행은 이 앱 프로세스가 한다. */
public final class Cmd extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String op = intent.getStringExtra("op");
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
        String line = Probe.run(ctx, op == null ? "check" : op, reason, "cmd");
        setResultData(line);
    }
}
