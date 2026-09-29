package nrc.controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 상단바 표시 한 개(원 또는 모서리가 살짝 둥근 사각형). */
final class MarkView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private MarkStyle style = MarkStyle.of(true, false, false);

    MarkView(Context c) {
        super(c);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(MarkStyle.OUTLINE);
        edge.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 0.75f));
    }

    void setStyle(MarkStyle s) {
        if (s.equals(style)) return;
        style = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float inset = style.outlined() ? edge.getStrokeWidth() / 2f : 0f;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        fill.setColor(style.fillColor());
        if (style.shape == MarkStyle.Shape.CIRCLE) {
            c.drawOval(box, fill);
            if (style.outlined()) c.drawOval(box, edge);
        } else {
            float r = getWidth() * 0.15f;
            c.drawRoundRect(box, r, r, fill);
            if (style.outlined()) c.drawRoundRect(box, r, r, edge);
        }
    }
}
