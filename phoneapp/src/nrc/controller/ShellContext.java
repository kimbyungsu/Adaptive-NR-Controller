package nrc.controller;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

/**
 * 도우미 프로세스(HostService, shell uid) 안에서 전화 서비스의 "호출 패키지가 호출 uid 소유인가" 검사를 통과하도록
 * 시스템 컨텍스트를 com.android.shell로 감싼다(예전 데몬 ShellContext와 같은 방식, §5.15 관문 d — poc/shizuku에서 실기기 확인).
 * 도우미 프로세스엔 Shizuku가 만든 ActivityThread가 이미 있어 그것을 다시 쓴다. 앱 프로세스에서는 쓰지 않는다.
 */
final class ShellContext extends ContextWrapper {
    static final String PACKAGE = "com.android.shell";

    private ShellContext(Context base) {
        super(base);
    }

    static Context create() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        // 모듈 서비스 등록기가 아직 준비 안 됐을 수 있다 → 전화 서비스 핸들 null 방지(데몬 실측)
        try {
            at.getDeclaredMethod("initializeMainlineModules").invoke(null);
        } catch (Throwable ignore) {
            // 이미 준비됐거나 이름이 바뀐 경우 그대로 진행
        }
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = at.getMethod("systemMain").invoke(null);
        Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
        return new ShellContext(sys);
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
