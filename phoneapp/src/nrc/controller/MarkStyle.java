package nrc.controller;

/**
 * 상단바 표시의 모양과 색(DESIGN §5.13 "상단바 표시 규칙", 2026-09-29 사용자 결정). 안드로이드 의존 없음.
 * 모양 = 사용자가 고른 모드(원 = 5G 우선, 사각형 = LTE 우선).
 * 색 = 문제(PC 필요)면 빨강, 아니면 Wi-Fi면 흰색(얇은 회색 테두리), 모바일 데이터면 초록.
 */
final class MarkStyle {
    enum Shape { CIRCLE, SQUARE }

    enum Tone { GREEN, WHITE, RED }

    static final int GREEN = 0xFF2ECC40;
    static final int WHITE = 0xFFFFFFFF;
    static final int RED = 0xFFE53935;
    static final int OUTLINE = 0xFF9E9E9E;

    final Shape shape;
    final Tone tone;

    MarkStyle(Shape shape, Tone tone) {
        this.shape = shape;
        this.tone = tone;
    }

    /** 컨트롤러 상태에서 표시를 정한다. userNr = 사용자가 5G 우선을 골랐는지. */
    static MarkStyle of(boolean userNr, boolean wifi, boolean problem) {
        Shape s = userNr ? Shape.CIRCLE : Shape.SQUARE;
        Tone t = problem ? Tone.RED : (wifi ? Tone.WHITE : Tone.GREEN);
        return new MarkStyle(s, t);
    }

    int fillColor() {
        switch (tone) {
            case RED:
                return RED;
            case WHITE:
                return WHITE;
            default:
                return GREEN;
        }
    }

    /** 흰색은 밝은 화면에서 사라지므로 얇은 회색 테두리를 두른다. */
    boolean outlined() {
        return tone == Tone.WHITE;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MarkStyle)) return false;
        MarkStyle m = (MarkStyle) o;
        return m.shape == shape && m.tone == tone;
    }

    @Override
    public int hashCode() {
        return shape.hashCode() * 31 + tone.hashCode();
    }

    @Override
    public String toString() {
        return shape + "/" + tone;
    }
}
