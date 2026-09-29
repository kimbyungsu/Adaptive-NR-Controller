package nrc.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 관문 ③·④: 재부팅 뒤 ADB 없이 스스로 올라와 통신사 권한이 남아 있는지 기록하고, 오래 사는 서비스를 띄운다. */
public final class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Probe.run(ctx, "check", 0, "boot:" + intent.getAction());
        ctx.startForegroundService(new Intent(ctx, Keeper.class));
    }
}
