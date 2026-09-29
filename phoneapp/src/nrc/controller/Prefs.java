package nrc.controller;

import android.content.Context;
import android.content.SharedPreferences;

/** 사용자가 고른 표시 크기·위치와, 시험 화면에서 미리 보는 모양·색. */
final class Prefs {
    static final String FILE = "mark";
    static final String SIZE = "sizeDp";
    static final String X = "xDp";
    static final String Y = "yDp";
    static final String PREVIEW_SHAPE = "previewShape";
    static final String PREVIEW_TONE = "previewTone";

    static final int DEFAULT_SIZE = 6;
    static final int DEFAULT_X = 14;
    /** -1 = 상단바 높이의 가운데. */
    static final int DEFAULT_Y = -1;

    private Prefs() {
    }

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int size(SharedPreferences p) {
        return p.getInt(SIZE, DEFAULT_SIZE);
    }

    static int x(SharedPreferences p) {
        return p.getInt(X, DEFAULT_X);
    }

    static int y(SharedPreferences p) {
        return p.getInt(Y, DEFAULT_Y);
    }

    static MarkStyle preview(SharedPreferences p) {
        MarkStyle.Shape s = MarkStyle.Shape.valueOf(p.getString(PREVIEW_SHAPE, MarkStyle.Shape.CIRCLE.name()));
        MarkStyle.Tone t = MarkStyle.Tone.valueOf(p.getString(PREVIEW_TONE, MarkStyle.Tone.GREEN.name()));
        return new MarkStyle(s, t);
    }
}
