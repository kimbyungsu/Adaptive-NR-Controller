package nrc.shizupoc;

/**
 * Shizuku UserService(관측 프로세스) — Shizuku가 이 클래스를 shell 신분(uid 2000) 별도 프로세스로 띄운다(§5.15 방향 A).
 * Step 1: 호스트가 살아 있고 신분·기본 정보를 돌려주는지만 확인(snapshot). 실제 관찰(TelephonyCallback 등록)은 Step 2에서.
 * 생성자는 공개여야 하고(Shizuku가 리플렉션으로 생성), Context 생성자도 선택적으로 지원된다(v13+).
 */
public final class NrObserverService extends INrObserver.Stub {

    public NrObserverService() {
    }

    @SuppressWarnings("unused")
    public NrObserverService(android.content.Context context) {
    }

    @Override
    public void destroy() {
        android.util.Log.i("NRSHIZUOBS", "destroy");
        System.exit(0);
    }

    @Override
    public String snapshot() {
        int uid = android.os.Process.myUid();
        int pid = android.os.Process.myPid();
        String s = "hosted uid=" + uid + " (2000=shell) pid=" + pid
                + " model=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT;
        android.util.Log.i("NRSHIZUOBS", "snapshot: " + s);
        return s;
    }
}
