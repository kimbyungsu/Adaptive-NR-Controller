package nrc.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 관문 ③ 보조: 부팅 직후엔 통신사 설정이 아직 안 올라와 있을 수 있어, 설정이 올라온 시점에도 권한을 기록한다. */
public final class CcChanged extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Probe.run(ctx, "check", 0, "cc_changed");
    }
}
