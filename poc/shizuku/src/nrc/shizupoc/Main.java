package nrc.shizupoc;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.system.Os;
import android.system.OsConstants;
import android.telephony.SubscriptionManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Shizuku 경로 PoC(제품 아님). 2026-10-04.
 * 목적: 통신사 권한에 기대지 않고, 사용자가 설치·동의한 Shizuku(shell 신분)를 거쳐
 *   (1) 전화 설정(허용 망 사유별 값)을 '읽고'
 *   (2) POWER 사유로 5G(NR)만 '잠깐 막았다 되돌리는' 제어가 레퍼런스 폰에서 되는지 확인한다.
 * 안전 불변식:
 *   - USER(0) 사유는 절대 쓰지 않는다(설정 화면 값 불변). 쓰기는 POWER(1)만. (cmd ...for-users는 USER 고정이라 안 씀.)
 *   - 모든 특권 작업(읽기·쓰기시험·자동복구)을 프로세스 단일 스레드(EXEC)로 직렬화 → 겹침 없음.
 *   - 쓰기 전에 (sub,원값,우리가쓸값)을 '내구적으로'(temp→파일fsync→rename→디렉터리fsync) + 무결성(CRC) 기록하고,
 *     저장 확인 후에만 막는다. 저장 실패면 막지 않는다.
 *   - 되돌리기는 소유권 비교(best-effort): 현재 POWER가 '우리가 쓴 값(ours)'일 때만 원값으로 되돌린다.
 *     NR이 이미 허용돼 있으면(외부가 NR 켠 값으로 바꿈) 소유권을 내려놓고, NR이 막혔는데 ours도 원값도 아니면
 *     '충돌'로 보고 덮지 않고 기록을 남긴다(자동 복구 보류). 되읽어 일치할 때만 기록을 지운다.
 *   - 한계(명시): 플랫폼에 setAllowedNetworkTypesForReason의 원자적 CAS가 없어, 읽기→쓰기 사이 외부 변경을
 *     막지 못한다(TOCTOU). 값만으로는 작성자를 못 가린다(ABA). 이는 제품 데몬 §5.6.3과 동일한 best-effort 한계다.
 *   - 기록 저장·삭제는 디렉터리 fsync로 디스크에 확정한다(전원 차단 뒤 옛 기록 재등장·유실 방지).
 *   - 미해결(또는 손상) 복구 기록이 있으면 새 시험을 시작하지 않고 그 기록을 덮지도 않는다.
 *   - 끊김·크래시 시 기록이 남아 재연결/승인/다시읽기에서 복구 재개.
 * 호출: SystemServiceHelper.getSystemService("phone")을 ShizukuBinderWrapper로 감싸 ITelephony로 reflection.
 *   앱 프로세스의 숨은 API 제한은 LSPosed HiddenApiBypass로 완화(특권 상승 아님).
 * 관측(PCC 콜백)은 이 PoC 범위 아님(§5.15 방향 A에서 별도).
 */
public final class Main extends Activity {

    static final int USER = 0, POWER = 1, CARRIER = 2, ENABLE_2G = 3;
    static final long NR_BIT = 1L << 19; // TelephonyManager NR 비트(= 840583 - 316295, research §2.14)
    static final int REQ = 1001;
    static final String TAG = "NRC3"; // 복구 기록 포맷 선두 표지(소유권: sub,원값,우리가쓴값)

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean WRITING = new AtomicBoolean(false);

    private TextView tv;
    private final StringBuilder buf = new StringBuilder();
    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final Shizuku.OnRequestPermissionResultListener permL =
            (requestCode, grantResult) -> { EXEC.submit(this::recoverIfPending); runOnUiThread(this::refresh); };
    private final Shizuku.OnBinderReceivedListener recvL =
            () -> { EXEC.submit(this::recoverIfPending); runOnUiThread(this::refresh); };
    private final Shizuku.OnBinderDeadListener deadL = () -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        allowHiddenApis();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad, pad, pad);

        Button read = new Button(this);
        read.setText("다시 읽기");
        read.setOnClickListener(v -> refresh());
        col.addView(read);

        Button test = new Button(this);
        test.setText("5G 잠깐 막았다 되돌리기 시험 (POWER)");
        test.setOnClickListener(this::onTest);
        col.addView(test);

        Button bind = new Button(this);
        bind.setText("관측 프로세스 연결 시험 (bindUserService)");
        bind.setOnClickListener(v -> bindObserver());
        col.addView(bind);

        tv = new TextView(this);
        tv.setTextIsSelectable(true);
        col.addView(tv);

        ScrollView sc = new ScrollView(this);
        sc.addView(col);
        setContentView(sc);

        Shizuku.addBinderReceivedListenerSticky(recvL);
        Shizuku.addBinderDeadListener(deadL);
        Shizuku.addRequestPermissionResultListener(permL);
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(recvL);
        Shizuku.removeBinderDeadListener(deadL);
        Shizuku.removeRequestPermissionResultListener(permL);
        unbindObserver();
    }

    private void log(String s) {
        android.util.Log.i("NRSHIZU", s);
        buf.append(ts.format(new Date())).append("  ").append(s).append('\n');
        final String out = buf.toString();
        runOnUiThread(() -> tv.setText(out));
    }

    private void head(String s) {
        android.util.Log.i("NRSHIZU", "=== " + s);
        buf.setLength(0);
        buf.append(s).append("\n\n");
        if (pendingFile().exists()) {
            buf.append("[주의] 미완료 복구 기록이 있어요 — Shizuku 연결+승인되면 자동으로 되돌리고,"
                    + " 안 되면 [다시 읽기]로 다시 시도해요.\n\n");
        }
        final String out = buf.toString();
        runOnUiThread(() -> tv.setText(out));
    }

    private void refresh() {
        if (!Shizuku.pingBinder()) {
            head("Shizuku가 안 떠 있어요.\nShizuku 앱을 열어 '시작'한 뒤 [다시 읽기]를 눌러요.");
            return;
        }
        if (Shizuku.isPreV11()) {
            head("이 Shizuku는 옛 권한 방식(Shizuku API v11 이전)이에요. 이 PoC는 Shizuku API v11+ 방식만 다뤄요.");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                head("Shizuku 사용 권한이 거부돼 있어요. Shizuku 앱에서 이 앱을 허용해 주세요.");
            } else {
                head("Shizuku 사용 권한을 요청할게요. 뜨는 창에서 '허용'을 눌러요.");
                Shizuku.requestPermission(REQ);
            }
            return;
        }
        head("Shizuku 권한 OK.");
        if (pendingFile().exists()) EXEC.submit(this::recoverIfPending);
        EXEC.submit(this::readAll);
    }

    private void readAll() {
        try {
            int sub = SubscriptionManager.getDefaultDataSubscriptionId();
            Object tel = telephony();
            log("기본 데이터 SIM sub=" + sub + " (Shizuku 경유 호출)");
            log("Shizuku 원격 신분 uid=" + Shizuku.getUid() + " (2000=shell), 버전=" + Shizuku.getVersion()
                    + ", SELinux=" + safeSelinux());
            for (int r = 0; r < 4; r++) {
                long v = getReason(tel, sub, r);
                log("사유 " + name(r) + " = " + v + (hasNr(v) ? "  (NR 있음)" : "  (NR 없음)"));
            }
            log("\n읽기 성공 = Shizuku 통로로 전화 설정을 '통신사 권한 없이' 읽었다는 뜻.");
        } catch (Throwable t) {
            log("읽기 실패: " + unwrap(t));
        }
    }

    // ---- Step 1: 관측 프로세스(bindUserService) 연결 시험 ----

    private Shizuku.UserServiceArgs obsArgs; // bind/unbind가 같은 인스턴스를 쓰도록 1회 생성(onCreate)
    private final AtomicBoolean obsBound = new AtomicBoolean(false);

    private Shizuku.UserServiceArgs obsArgs() {
        if (obsArgs == null) {
            obsArgs = new Shizuku.UserServiceArgs(
                    new android.content.ComponentName(getPackageName(), NrObserverService.class.getName()))
                    .daemon(false).processNameSuffix("obs").debuggable(false).version(1).tag("nrobs");
        }
        return obsArgs;
    }

    private final android.content.ServiceConnection obsConn = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName n, IBinder binder) {
            EXEC.submit(() -> {
                try {
                    if (binder == null || !binder.pingBinder()) {
                        log("관측 프로세스 binder가 비었거나 죽었어요.");
                        return;
                    }
                    INrObserver obs = INrObserver.Stub.asInterface(binder);
                    log("관측 프로세스 연결됨 → snapshot: " + obs.snapshot());
                    log("= Shizuku가 우리 코드를 shell 프로세스로 띄워 그 안에서 값을 받아왔다는 뜻(방향 A의 토대).");
                    String watch = obs.startWatch();
                    log("관측 시작 요청 → " + watch);
                    if (watch != null && watch.contains("성공")) {
                        log("이제 그 프로세스가 5G 상태 변화를 지켜봐요(기록 NRSHIZUOBS). 5G가 붙었다 끊기면 로그에 남아요.");
                    } else {
                        log("관측 등록이 안 됐어요(위 메시지 참고) — 지켜보기는 시작되지 않았어요.");
                    }
                } catch (Throwable t) {
                    log("snapshot 실패: " + unwrap(t));
                }
            });
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName n) {
            runOnUiThread(() -> log("관측 프로세스 연결 끊김."));
        }
    };

    private void bindObserver() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            refresh();
            return;
        }
        head("Shizuku가 우리 '관측 프로세스'를 shell 신분으로 띄우도록 요청해요(bindUserService)…");
        try {
            Shizuku.bindUserService(obsArgs(), obsConn);
            obsBound.set(true);
        } catch (Throwable t) {
            log("bindUserService 실패: " + unwrap(t));
        }
    }

    /** Activity 종료 시 관측 프로세스 연결을 해제(연결 캐시·콜백 누수 방지). daemon(false)라 remove=true. */
    private void unbindObserver() {
        if (!obsBound.getAndSet(false)) return;
        try {
            Shizuku.unbindUserService(obsArgs(), obsConn, true);
        } catch (Throwable ignored) {
        }
    }

    private void onTest(View v) {
        if (!WRITING.compareAndSet(false, true)) {
            log("[무시] 쓰기 시험이 이미 진행/대기 중이에요.");
            return;
        }
        head("POWER 사유로 5G만 잠깐 막았다 되돌리는 시험 (USER는 안 건드림)…");
        EXEC.submit(() -> {
            try {
                writeTest();
            } finally {
                WRITING.set(false);
            }
        });
    }

    private void writeTest() {
        // 0) 미해결 복구 기록이 있으면 먼저 처리하고, 못 지우면 새 시험을 시작하지 않는다(기록 덮지 않음).
        if (pendingFile().exists()) {
            long[] old = readPendingValidated();
            if (old == null) {
                log("[중단] 미완료 복구 기록이 손상돼 있어요 — 새 시험을 하지 않아요. PC로 확인 필요.");
                return;
            }
            log("먼저 지난 미완료 복구부터 처리해요: POWER 원값 " + old[1]);
            restoreAndClear((int) old[0], old[1], old[2], "기존 기록 복구");
            if (pendingFile().exists()) {
                log("[중단] 기존 복구가 끝나지 않아 새 시험을 하지 않아요.");
                return;
            }
        }

        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        Object tel;
        long original;
        try {
            tel = telephony();
            original = getReason(tel, sub, POWER);
            log("시작 POWER = " + original + (hasNr(original) ? " (NR 있음)" : " (NR 없음)"));
        } catch (Throwable t) {
            log("준비 실패(쓰기 안 함): " + unwrap(t));
            return;
        }
        if (!hasNr(original)) {
            log("원래 POWER에 NR이 없어요 — 이미 (다른 사유나 이전에) 막혀 있어 '전환 시험'은 생략해요.");
            log("이건 '이번 쓰기 성공'이 아니라 '기존부터 차단' 상태예요(구분).");
            return;
        }
        long noNr = original & ~NR_BIT; // 우리가 쓸 값(소유권 표식)
        // 1) 쓰기 전에 (sub,원값,우리가쓸값)을 내구적으로+무결성 저장. 실패하면 전화 설정을 건드리지 않는다.
        if (!savePendingDurable(sub, original, noNr)) {
            log("[중단] 복구 기록을 안전히 저장하지 못했어요 — 5G 막기를 하지 않아요.");
            return;
        }
        try {
            setReason(tel, sub, POWER, noNr);
            log("POWER ← " + noNr + " (NR 제거) 요청함");
            Thread.sleep(400);
            long after = getReason(tel, sub, POWER);
            log("다시 읽은 POWER = " + after + (hasNr(after) ? " (NR 있음)" : " (NR 없음)"));
            log(hasNr(after) ? "주의: NR이 안 빠졌어요(쓰기 거부?)." : "확인: 5G가 POWER 사유로 막혔어요.");
        } catch (Throwable t) {
            log("쓰기 중 오류: " + unwrap(t) + " — 반영됐을 수 있어 아래에서 CAS 되돌리기 해요(기록 있음).");
        }
        // 2) CAS 되돌리기(항상 시도). 현재 값이 우리가 쓴 값일 때만 원값으로 복원.
        if (!restoreAndClear(sub, original, noNr, "되돌림")) {
            log("[경고] 되돌리기를 확정 못 했어요. 복구 기록을 남겨 둬요 — [다시 읽기] 또는 다시 열 때 자동 복구해요.");
        }
        log("\nUSER 사유는 한 번도 쓰지 않았어요(설정 화면 값 불변).");
    }

    private void recoverIfPending() {
        File f = pendingFile();
        if (!f.exists()) return;
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
        long[] rec = readPendingValidated();
        if (rec == null) {
            log("[경고] 복구 기록이 손상됐어요 — 자동으로 되돌리지 않고 남겨 둬요. 알려 주시면 PC로 확인할게요.");
            return;
        }
        log("지난 시험의 미완료 복구를 시도해요(CAS): POWER 원값 " + rec[1]);
        restoreAndClear((int) rec[0], rec[1], rec[2], "자동 복구");
    }

    /**
     * 소유권 비교 되돌리기(best-effort). 현재 POWER(cur)에 따라:
     *  - cur == original            → 이미 원래 값. 기록 확정 삭제.
     *  - cur == ours                → 우리 막음 그대로 → original로 복원(되읽어 일치 시 기록 확정 삭제).
     *  - hasNr(cur) (그 외 NR 허용)  → 외부가 NR 켠 값으로 바꿈. 우리 막음 없음 → 덮지 않고 소유권 내려놓음(확정 삭제).
     *  - 그 외(!hasNr(cur))          → NR은 막혔는데 ours도 original도 아님 = 충돌. 덮지 않고 기록 보존(복구 보류).
     * 한계: 읽기→쓰기 비원자(TOCTOU)·값만으론 작성자 불명(ABA). 플랫폼에 원자적 CAS 없음(제품 §5.6.3과 동일).
     * EXEC 스레드에서만 호출. 더 손댈 것이 없으면 true(충돌·복구실패는 false로 기록 유지).
     */
    private boolean restoreAndClear(int sub, long original, long ours, String label) {
        try {
            Object tel = telephony();
            long cur = getReason(tel, sub, POWER);
            if (cur == original) {
                log(label + ": 이미 원래 값(" + original + ") — 손댈 것 없음.");
                clearConfirmed();
                return true;
            }
            if (cur == ours) {
                // 우리 막음이 그대로로 보임 → 복원. (주의: 읽기~쓰기 사이 외부 변경은 못 막음 = TOCTOU/ABA, best-effort)
                setReason(tel, sub, POWER, original);
                Thread.sleep(300);
                long back = getReason(tel, sub, POWER);
                log(label + ": POWER = " + back + (hasNr(back) ? " (NR 있음)" : " (NR 없음)"));
                if (back == original) {
                    log("원래 값으로 복구 완료(best-effort).");
                    clearConfirmed();
                    return true;
                }
                log("경고: 복구 값이 원래와 달라요(기록 유지).");
                return false;
            }
            if (hasNr(cur)) {
                // NR이 이미 허용됨 = 외부가 NR 켠 값으로 바꿈 → 우리 막음은 더 이상 유효하지 않음 → 소유권 내려놓음.
                log(label + ": 현재 POWER=" + cur + "에 NR이 있어요(외부가 바꿈) — 우리 막음 없음, 덮지 않고 소유권 내려놓음.");
                clearConfirmed();
                return true;
            }
            // !hasNr(cur) && cur != ours && cur != original → NR은 막혔는데 우리/원 값 모두 아님 = 충돌
            log("[충돌] 현재 POWER=" + cur + ": NR은 막혀 있으나 우리가 쓴 값도 원값도 아니에요 — 누가 막았는지 불명.");
            log("덮어쓰지 않고 복구 기록을 남겨요(자동 복구 보류). PC로 확인이 필요해요.");
            return false; // 기록 보존(clear 안 함)
        } catch (Throwable t) {
            log("되돌리기 오류(기록 유지): " + unwrap(t));
            return false;
        }
    }

    /** 복구가 끝난 경우에만 호출: 기록을 디스크에 확정 삭제(디렉터리 fsync). */
    private void clearConfirmed() {
        if (!clearPending()) log("(복구 기록 삭제 미확정 — 다음 연결에서 한 번 더 복구 시도될 수 있음)");
    }

    // ---- 복구 기록(내구적 저장 + 무결성/범위/소유권 검증) ----

    private File pendingFile() {
        return new File(getFilesDir(), "pending_power_restore.txt");
    }

    /**
     * temp에 쓰고 파일 fsync → rename(원자적) → 부모 디렉터리 fsync 까지 성공해야 저장 성공으로 본다.
     * CRC로 무결성 보장, 되읽어 검증될 때만 true. 기존 기록이 있으면 거부(덮지 않음).
     */
    private boolean savePendingDurable(int sub, long original, long ours) {
        try {
            File dir = getFilesDir();
            File dst = pendingFile();
            if (dst.exists()) return false; // 미해결 기록 덮지 않음(writeTest가 먼저 비움)
            String payload = TAG + " " + sub + " " + original + " " + ours;
            CRC32 crc = new CRC32();
            crc.update(payload.getBytes(StandardCharsets.UTF_8));
            String full = payload + " " + crc.getValue();
            File tmp = new File(dir, "pending_power_restore.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(full.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync(); // 파일 내용을 디스크까지
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return false;
            }
            if (!fsyncDir(dir)) { // rename(디렉터리 항목)을 디스크까지 — 없으면 전원차단 시 이름 유실 가능
                return false;
            }
            long[] rb = readPendingValidated();
            return rb != null && rb[0] == sub && rb[1] == original && rb[2] == ours;
        } catch (Throwable t) {
            log("복구 기록 저장 오류: " + unwrap(t));
            return false;
        }
    }

    /** 부모 디렉터리를 fsync(rename 내구성). 성공 true. */
    private boolean fsyncDir(File dir) {
        FileDescriptor fd = null;
        try {
            fd = Os.open(dir.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            Os.fsync(fd);
            return true;
        } catch (Throwable t) {
            log("디렉터리 fsync 실패: " + unwrap(t));
            return false;
        } finally {
            if (fd != null) {
                try { Os.close(fd); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 완전한 포맷 "NRC3 &lt;sub&gt; &lt;original&gt; &lt;ours&gt; &lt;crc&gt;" + CRC 일치 + sub 범위 +
     * original에 NR 있음 + ours == original&~NR_BIT(우리 연산) 일 때만 {sub,original,ours} 반환. 아니면 null.
     */
    private long[] readPendingValidated() {
        File f = pendingFile();
        if (!f.exists()) return null;
        try {
            byte[] bytes = readAllBytes(f);
            String content = new String(bytes, StandardCharsets.UTF_8).trim();
            String[] a = content.split("\\s+");
            if (a.length != 5 || !TAG.equals(a[0])) return null;
            long sub = Long.parseLong(a[1]);
            long original = Long.parseLong(a[2]);
            long ours = Long.parseLong(a[3]);
            long crcStored = Long.parseLong(a[4]);
            CRC32 crc = new CRC32();
            crc.update((TAG + " " + sub + " " + original + " " + ours).getBytes(StandardCharsets.UTF_8));
            if (crc.getValue() != crcStored) return null;          // 손상(비트 변형·부분 기록)
            if (sub < 0 || sub > Integer.MAX_VALUE) return null;    // sub는 int 범위
            if (!hasNr(original)) return null;                      // 저장은 'NR 있음'일 때만
            if (ours != (original & ~NR_BIT)) return null;          // 우리 연산(= NR만 제거)과 일치해야
            return new long[]{sub, original, ours};
        } catch (Throwable t) {
            return null; // 읽기/파싱 실패 = 손상으로 취급(호출자가 삭제하지 않음)
        }
    }

    private static byte[] readAllBytes(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[512];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /** 삭제를 디스크에 확정(delete 후 디렉터리 fsync)해야 true. 안 그러면 전원차단 뒤 옛 기록이 되살아날 수 있음. */
    private boolean clearPending() {
        File f = pendingFile();
        if (!f.exists()) return true;
        if (!f.delete()) {
            f.deleteOnExit();
            return false;
        }
        return fsyncDir(getFilesDir()); // 삭제(디렉터리 항목 변경)를 디스크까지
    }

    // ---- Android 숨은 API 완화 ----

    /**
     * Android 9+의 숨은 API 접근 제한을 이 앱 프로세스에 한해 완화한다(LSPosed HiddenApiBypass, Unsafe 기반 구현).
     * ITelephony의 숨은 getter/setter를 앱 프로세스가 '찾을' 수 있게 할 뿐이고, 실제 특권 호출은 Shizuku(shell)로 대행된다.
     */
    private static void allowHiddenApis() {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        try {
            boolean ok = org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
            android.util.Log.i("NRSHIZU", "hidden API 차단 해제 결과=" + ok);
        } catch (Throwable t) {
            android.util.Log.w("NRSHIZU", "hidden API 해제 실패: " + t);
        }
    }

    // ---- Shizuku 경유 전화 서비스 ----

    private static Object telephony() throws Exception {
        IBinder raw = SystemServiceHelper.getSystemService("phone");
        if (raw == null) throw new IllegalStateException("phone 서비스 binder를 못 얻음");
        IBinder wrapped = new ShizukuBinderWrapper(raw);
        Class<?> stub = Class.forName("com.android.internal.telephony.ITelephony$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, wrapped);
    }

    private static long getReason(Object tel, int sub, int reason) throws Exception {
        Method m = tel.getClass().getMethod("getAllowedNetworkTypesForReason", int.class, int.class);
        return (Long) m.invoke(tel, sub, reason);
    }

    private static void setReason(Object tel, int sub, int reason, long mask) throws Exception {
        if (reason == USER) throw new IllegalArgumentException("USER 사유는 쓰지 않는다");
        Method m = tel.getClass().getMethod("setAllowedNetworkTypesForReason", int.class, int.class, long.class);
        m.invoke(tel, sub, reason, mask);
    }

    private static String safeSelinux() {
        try {
            return Shizuku.getSELinuxContext();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean hasNr(long mask) {
        return (mask & NR_BIT) != 0;
    }

    private static String name(int r) {
        switch (r) {
            case USER: return "USER";
            case POWER: return "POWER";
            case CARRIER: return "CARRIER";
            case ENABLE_2G: return "ENABLE_2G";
            default: return "?" + r;
        }
    }

    private static String unwrap(Throwable t) {
        Throwable c = t;
        while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
