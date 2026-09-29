package nrc.controller;

/** 타일 표시 규칙 시험(DESIGN §5.13 상태 표시 구조). 안드로이드 없이 PC에서 돈다: bash phoneapp/test.sh */
public final class TileTextTest {
    private static int fails;
    private static int n;

    public static void main(String[] a) {
        int NR = TileText.MODE_NR, LTE = TileText.MODE_LTE, UNK = TileText.MODE_UNKNOWN;
        // 문제가 있으면 무엇보다 먼저(자동 제어가 꺼져 있어도)
        check(TileText.of(true, "PC 연결 필요", NR, false, null), "UNAVAILABLE:PC 연결 필요");
        check(TileText.of(false, "PC 연결 필요", NR, false, null), "UNAVAILABLE:PC 연결 필요");
        // 자동 제어 꺼짐은 모드·인터넷과 상관없이
        check(TileText.of(false, null, NR, false, TileText.PHASE_RESTING), "INACTIVE:자동 제어 꺼짐");
        check(TileText.of(false, null, LTE, true, null), "INACTIVE:자동 제어 꺼짐");
        // 켜짐
        check(TileText.of(true, null, UNK, false, null), "ACTIVE:확인 중");
        check(TileText.of(true, null, LTE, false, null), "ACTIVE:LTE 우선 · 대기");
        check(TileText.of(true, null, LTE, true, null), "ACTIVE:LTE 우선 · 대기");
        check(TileText.of(true, null, NR, true, TileText.PHASE_RESTING), "ACTIVE:Wi-Fi · 대기");
        check(TileText.of(true, null, NR, false, TileText.PHASE_RESTING), "ACTIVE:LTE로 쉬는 중");
        check(TileText.of(true, null, NR, false, TileText.PHASE_PROBING), "ACTIVE:5G 확인 중");
        check(TileText.of(true, null, NR, false, null), "ACTIVE:5G 관리 중");
        System.out.println(fails == 0 ? "TileTextTest OK (" + n + ")" : "TileTextTest FAILED " + fails);
        if (fails != 0) System.exit(1);
    }

    private static void check(TileText t, String want) {
        n++;
        if (!t.toString().equals(want)) {
            fails++;
            System.out.println("FAIL got " + t + " want " + want);
        }
    }
}
