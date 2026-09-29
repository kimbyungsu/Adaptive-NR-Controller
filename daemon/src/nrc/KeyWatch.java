package nrc;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * 삼성 설정 화면 키(`preferred_network_mode<subId>`) 변경 감시(DESIGN §5.12). 컨트롤러는 이 키를 쓰지 않으므로
 * 키 변경 = 사용자가 설정 화면(또는 사용자 루틴)에서 모드를 고른 것이다.
 * 등록에 실패해도 USER 알림 뒤 재확인과 30초 주기 확인으로 같은 판단을 한다(늦게 알 뿐).
 */
final class KeyWatch {
    // GC로 풀리지 않게 잡아 둔다
    private static ContentObserver observer;

    private KeyWatch() {
    }

    /** 등록 성공이면 true. onChange는 메인 루퍼에서 불리므로 받는 쪽이 작업 스레드로 넘긴다. */
    static boolean start(Context ctx, String key, Runnable onChange) {
        try {
            Uri uri = Settings.Global.getUriFor(key);
            observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    onChange.run();
                }
            };
            ctx.getContentResolver().registerContentObserver(uri, false, observer);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
