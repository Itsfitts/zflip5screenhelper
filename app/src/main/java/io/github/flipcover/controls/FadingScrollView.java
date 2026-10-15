package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.widget.ScrollView;

/** A reading edge follows the viewport, never the page or elastically displaced content. */
class FadingScrollView extends ScrollView {
    private final Paint mask = new Paint();
    private int maskBottom = -1, maskBand = -1;
    FadingScrollView(Context context) { super(context); }
    protected boolean fadeBottom() { return canScrollVertically(1); }
    @Override protected final void dispatchDraw(Canvas canvas) {
        boolean fade = fadeBottom();
        int bottom = getHeight() - getPaddingBottom(), band = Math.min(Ui.dp(getContext(), 16), getHeight());
        int layer = fade ? canvas.saveLayer(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight(), null) : 0;
        drawScrolledContent(canvas);
        if (fade) {
            if (maskBottom != bottom || maskBand != band) { maskBottom = bottom; maskBand = band; mask.setShader(new LinearGradient(0, bottom - band, 0, bottom, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)); mask.setBlendMode(BlendMode.DST_IN); }
            canvas.translate(getScrollX(), getScrollY()); canvas.drawRect(0, 0, getWidth(), getHeight(), mask); canvas.restoreToCount(layer);
        }
    }
    protected void drawScrolledContent(Canvas canvas) { super.dispatchDraw(canvas); }
}
