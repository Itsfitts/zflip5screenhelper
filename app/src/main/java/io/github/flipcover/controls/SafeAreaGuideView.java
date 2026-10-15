package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Passive, full-display overlay. The same path is also used by the settings preview. */
final class SafeAreaGuideView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour = new Path();
    private SafeAreaManager.Snapshot area;
    private int edge = -1;
    private float strokeScale = 1;
    void strokeScale(float scale) { strokeScale = scale; }
    SafeAreaGuideView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); setFocusable(false); }
    void geometry(SafeAreaManager.Snapshot value, DockGeometry.Placement dock, int selectedEdge) {
        area = value; edge = selectedEdge; contour.reset(); add(contour, area.frame());
        // Automatic mode retains the legacy notch-side Dock lane as part of the usable contour.
        if (!area.manual() && dock != null) { Path lane = new Path(); add(lane, dock.touch()); contour.op(lane, Path.Op.UNION); }
        for (DockGeometry.Box cut : area.cutouts()) { Path hole = new Path(); add(hole, cut); contour.op(hole, Path.Op.DIFFERENCE); }
        invalidate();
    }
    private static void add(Path path, DockGeometry.Box box) { path.addRect(box.x(), box.y(), box.right(), box.bottom(), Path.Direction.CW); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (area == null) return;
        float stroke = Math.max(2, area.density()) * strokeScale; paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(0xFF52F2EA);
        canvas.save(); canvas.clipRect(0, 0, getWidth(), getHeight()); canvas.drawPath(contour, paint);
        if (edge >= 0) {
            canvas.save(); canvas.clipPath(contour);
            DockGeometry.Box b = area.frame(); paint.setColor(0xFFFFFF60); paint.setStrokeWidth(stroke * 2);
            switch (edge) {
                case 0 -> canvas.drawLine(b.x(), b.y(), b.x(), b.bottom(), paint);
                case 1 -> canvas.drawLine(b.x(), b.y(), b.right(), b.y(), paint);
                case 2 -> canvas.drawLine(b.right(), b.y(), b.right(), b.bottom(), paint);
                default -> canvas.drawLine(b.x(), b.bottom(), b.right(), b.bottom(), paint);
            }
            canvas.restore();
        }
        canvas.restore();
    }
}
