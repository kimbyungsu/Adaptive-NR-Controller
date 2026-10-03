package nrc.shizupoc;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

/**
 * Shizuku 호스트(shell uid) 프로세스에서 전화 서비스가 "호출 패키지가 호출 uid 소유인가"를 통과하도록
 * 시스템 컨텍스트를 com.android.shell로 위장한다(데몬 ShellContext와 같은 FakeContext 방식, §5.15 관문 d).
 * 호스트 프로세스엔 Shizuku ServiceStarter가 만든 ActivityThread/메인 루퍼가 이미 있어 그걸 재사용한다.
 */
final class FakeShellContext extends ContextWrapper {
    static final String PACKAGE = "com.android.shell";

    private FakeShellContext(Context base) {
        super(base);
    }

    static Context create() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        // app_process/호스트에선 모듈 서비스 등록기 준비가 없을 수 있다 → TelephonyManager null 방지(데몬 실측).
        try {
            at.getDeclaredMethod("initializeMainlineModules").invoke(null);
        } catch (Throwable ignore) {
            // 이미 준비된 경우/비공개 변경 시 무시하고 진행
        }
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) {
            thread = at.getMethod("systemMain").invoke(null);
        }
        Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
        return new FakeShellContext(sys);
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
