package nrc.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 폰이 켜졌을 때와 이 앱이 업데이트됐을 때 상주 서비스를 다시 띄운다(두 경우 모두 안드로이드가 전면 서비스 시작을 허용한다). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            ControllerService.ensure(ctx);
        }
    }
}
