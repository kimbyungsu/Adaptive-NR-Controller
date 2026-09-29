package nrc;

/**
 * 상태 파일 규칙(Saved) 시험: 쓰기 결과 반영(컨트롤러가 남긴 값 기록). JDK만 필요.
 * §5.12부터 시작 때 원래 모드는 설정 키로 정한다 → UserModeTest.
 */
public final class SavedTest {
    static final long NR = 840583;
    static final long LTE = 316295;
    private static int failed;
    private static int passed;

    public static void main(String[] args) {
        okWriteIsTrace();
        failedRestoreKeepsLeftoverTrace();
        appliedBeforeCrashKeepsTrace();
        userChoiceClearsTrace();
        adoptedClearsTrace();
        System.out.println((failed == 0 ? "OK " : "FAILED " + failed + " / ") + passed + " checks passed");
        if (failed > 0) System.exit(1);
    }

    static void okWriteIsTrace() {
        Saved s = new Saved();
        s.original = NR;
        s.beginWrite(LTE, 9);
        check("쓰는 중: 시도 값 기록", s.pending, LTE);
        s.endWrite(Policy.Kind.OK, LTE, 9, LTE, false);
        check("성공: 컨트롤러가 남긴 값 = LTE, 시도 값 비움", s.lastWritten + "/" + s.pending, LTE + "/-1");
    }

    static void failedRestoreKeepsLeftoverTrace() {
        Saved s = new Saved();
        s.original = NR;
        s.beginWrite(LTE, 9);
        s.endWrite(Policy.Kind.OK, LTE, 9, LTE, false);
        s.beginWrite(NR, 26); // 종료 되돌리기 시도
        s.endWrite(Policy.Kind.FAILED, NR, 26, LTE, false);
        check("되돌리기 실패: 남긴 값 = LTE 그대로", s.lastWritten, LTE);
    }

    static void appliedBeforeCrashKeepsTrace() {
        Saved s = new Saved();
        s.original = NR;
        s.beginWrite(LTE, 9);
        s.applied(LTE, 9); // USER 적용 확인 직후 멈춤(endWrite 없음)
        check("적용 즉시 기록", s.lastWritten + "/" + s.pending, LTE + "/" + LTE);
    }

    static void userChoiceClearsTrace() {
        Saved s = new Saved();
        s.original = NR;
        s.beginWrite(LTE, 9);
        s.endWrite(Policy.Kind.OK, LTE, 9, LTE, false);
        s.userChose(LTE, 9); // 사용자가 설정에서 LTE 우선을 고름
        check("사용자 선택: 원래 모드 = LTE, 컨트롤러 흔적 없음", s.original + "/" + s.lastWritten, LTE + "/-1");
    }

    static void adoptedClearsTrace() {
        Saved s = new Saved();
        s.original = LTE;
        s.beginWrite(NR, 26);
        s.endWrite(Policy.Kind.ADOPTED, NR, 26, NR, true);
        check("쓰는 사이 사용자 선택 채택: 흔적 없음", s.lastWritten, -1L);
    }

    static void check(String what, Object got, Object want) {
        String g = String.valueOf(got);
        String w = String.valueOf(want);
        if (g.equals(w)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": got " + g + ", want " + w);
        }
    }

    private SavedTest() {
    }
}
