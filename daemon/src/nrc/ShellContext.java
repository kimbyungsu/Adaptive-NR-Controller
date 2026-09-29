package nrc;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * app_process(shell uid)에는 앱 컨텍스트가 없다. 시스템 컨텍스트를 만들고 패키지 이름을
 * com.android.shell로 보이게 감싼다(scrcpy의 FakeContext와 같은 방식).
 * 전화 서비스는 호출 패키지가 호출 uid 소유인지 확인하므로 shell 패키지 이름이어야 한다.
 */
final class ShellContext extends ContextWrapper {
    static final String PACKAGE = "com.android.shell";

    private ShellContext(Context base) {
        super(base);
    }

    /** 메인 스레드에서 한 번만 호출한다. */
    static ShellContext create() throws Exception {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        // 앱 프로세스는 시작 때 전화·통계 등 모듈 서비스 등록기를 준비한다(ActivityThread.main).
        // app_process에서는 이 준비가 없어 TelephonyManager의 서비스 조회가 null로 실패한다(레퍼런스 기기 실측) → 직접 호출.
        activityThread.getDeclaredMethod("initializeMainlineModules").invoke(null);
        Constructor<?> ctor = activityThread.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();

        Field current = activityThread.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);

        Field systemThread = activityThread.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(thread, true);

        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        return new ShellContext((Context) getSystemContext.invoke(thread));
    }

    @Override
    public String getPackageName() {
        return PACKAGE;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE;
    }

    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.myUid()).setPackageName(PACKAGE).build();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
