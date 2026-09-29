package nrc.controller;

/** 상단바 표시 규칙 시험(DESIGN §5.13 표). 안드로이드 없이 PC에서 돈다: bash phoneapp/test.sh */
public final class MarkStyleTest {
    private static int fails;

    public static void main(String[] a) {
        // 사용자 모드 × 인터넷 × 문제 여부 8가지
        check(true, false, false, "CIRCLE/GREEN", false);
        check(true, true, false, "CIRCLE/WHITE", true);
        check(true, false, true, "CIRCLE/RED", false);
        check(true, true, true, "CIRCLE/RED", false);
        check(false, false, false, "SQUARE/GREEN", false);
        check(false, true, false, "SQUARE/WHITE", true);
        check(false, false, true, "SQUARE/RED", false);
        check(false, true, true, "SQUARE/RED", false);
        if (MarkStyle.of(true, false, false).fillColor() != MarkStyle.GREEN) fail("green color");
        if (MarkStyle.of(true, true, false).fillColor() != MarkStyle.WHITE) fail("white color");
        if (MarkStyle.of(false, false, true).fillColor() != MarkStyle.RED) fail("red color");
        System.out.println(fails == 0 ? "MarkStyleTest OK (11)" : "MarkStyleTest FAILED " + fails);
        if (fails != 0) System.exit(1);
    }

    private static void check(boolean userNr, boolean wifi, boolean problem, String want, boolean outlined) {
        MarkStyle s = MarkStyle.of(userNr, wifi, problem);
        if (!s.toString().equals(want)) fail("of(" + userNr + "," + wifi + "," + problem + ")=" + s + " want " + want);
        if (s.outlined() != outlined) fail("outlined " + s + " want " + outlined);
    }

    private static void fail(String m) {
        fails++;
        System.out.println("FAIL " + m);
    }
}
