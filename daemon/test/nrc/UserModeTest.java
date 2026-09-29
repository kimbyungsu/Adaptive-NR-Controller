package nrc;

/** 사용자 선택 = 설정 키 규칙(UserMode, DESIGN §5.12) 시험(JDK만 필요). */
public final class UserModeTest {
    static final long NR = 840583;   // 5G 우선 마스크(키 26)
    static final long LTE = 316295;  // LTE 우선 마스크(키 9)
    static final long NR_BIT = 1 << 19;
    /** 레퍼런스 기기의 변환 짝만 아는 가짜 변환. */
    static final UserMode.ModeOf M = mask -> mask == NR ? 26 : mask == LTE ? 9 : -1;
    private static int failed;
    private static int passed;

    public static void main(String[] args) {
        // 키에 맞는 USER 마스크
        check("키 26·USER 5G → 그대로", UserMode.maskForKey(26, NR, NR_BIT, M), NR);
        check("키 26·USER LTE(쉬는 중) → 5G 마스크", UserMode.maskForKey(26, LTE, NR_BIT, M), NR);
        check("키 9·USER 5G(경합) → LTE 마스크", UserMode.maskForKey(9, NR, NR_BIT, M), LTE);
        check("키 9·USER LTE → 그대로", UserMode.maskForKey(9, LTE, NR_BIT, M), LTE);
        check("모르는 모드 → -1", UserMode.maskForKey(3, LTE, NR_BIT, M), -1L);
        check("키 못 읽음 → -1", UserMode.maskForKey(-1, LTE, NR_BIT, M), -1L);

        // 시작 판단: 원래 모드 = 설정 키
        UserMode.Start a = UserMode.decide(NR, 26, NR_BIT, M);
        check("5G 우선·일치", a.original + "/" + a.leftoverLte + "/" + a.alignTo + "/" + a.source, NR + "/false/-1/consistent");
        UserMode.Start b = UserMode.decide(LTE, 9, NR_BIT, M);
        check("LTE 우선·일치", b.original + "/" + b.leftoverLte + "/" + b.alignTo, LTE + "/false/-1");
        UserMode.Start c = UserMode.decide(LTE, 26, NR_BIT, M);
        check("설정 5G 우선·실제 LTE(쉬는 중 멈춤·재부팅) → 원래 5G, 재시험 대상",
                c.original + "/" + c.leftoverLte + "/" + c.alignTo + "/" + c.source, NR + "/true/-1/key_nr_user_lte");
        UserMode.Start d = UserMode.decide(NR, 9, NR_BIT, M);
        check("설정 LTE 우선·실제 5G(데몬이 없는 사이 LTE를 고름 등) → 원래 LTE, LTE로 맞춤",
                d.original + "/" + d.leftoverLte + "/" + d.alignTo + "/" + d.source, LTE + "/false/" + LTE + "/key_lte_user_nr");
        UserMode.Start e = UserMode.decide(LTE, -1, NR_BIT, M);
        check("키 못 읽음 → 관찰만 표시", e.keyUnavailable + "/" + e.original, "true/" + LTE);
        UserMode.Start f = UserMode.decide(LTE, 3, NR_BIT, M);
        check("짝 없음 → 지금 값", f.original + "/" + f.source + "/" + f.alignTo, LTE + "/unpaired/-1");

        System.out.println((failed == 0 ? "OK " : "FAILED " + failed + " / ") + (passed + failed) + " checks passed");
        if (failed > 0) System.exit(1);
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

    private UserModeTest() {
    }
}
