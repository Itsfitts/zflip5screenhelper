package io.github.flipcover.controls;

import android.view.WindowInsets;

/** One full-screen window sample, scoped to the exact display geometry; no polling or persistence. */
final class CoverWindowInsets {
    record Geometry(int displayId, int rotation, int width, int height, float density, String cutout) { }
    private Geometry geometry;
    private WindowInsets actual;
    private String observation = "尚未收到完整外屏窗口边衬";

    WindowInsets resolve(Geometry current, WindowInsets maximum) {
        if (!current.equals(geometry)) reset();
        return actual == null ? maximum : actual;
    }
    boolean observe(Geometry mounted, Geometry current, int viewDisplayId, float viewDensity, DockGeometry.Box frame, WindowInsets insets) {
        // A late old host must not invalidate a newer display/rotation sample.
        if (!mounted.equals(current)) return false;
        WindowInsets previous = actual;
        boolean differentGeometry = !current.equals(geometry);
        if (differentGeometry) actual = null;
        String rejected = current.displayId() <= 0 || viewDisplayId != current.displayId() ? "显示器不匹配"
            : Float.compare(viewDensity, current.density()) != 0 ? "密度不匹配"
            : insets != null && insets.isVisible(WindowInsets.Type.ime()) ? "输入法显示期间暂缓更新"
            : !frame.equals(new DockGeometry.Box(0, 0, current.width(), current.height())) ? "窗口尚未覆盖完整外屏"
            : insets == null || insets.isConsumed() ? "窗口边衬尚不可用" : null;
        // IME resize and an incomplete first layout on a replacement host must not
        // oscillate between max/actual and recreate the editor. Retain only same-geometry evidence.
        if (rejected == null) actual = new WindowInsets(insets);
        geometry = current;
        observation = (rejected == null ? "完整外屏窗口已校验" : (actual == null ? "回退原因=" : "保留同几何上次有效实测；当前采样未采用：") + rejected)
            + ", frame=" + frame + ", viewDisplay=" + viewDisplayId + ", density=" + viewDensity;
        return !same(previous, actual) || differentGeometry && actual != null;
    }
    String source() { return actual == null ? "最大窗口回退" : "全屏实测"; }
    String diagnostics() {
        return "边衬来源=" + source() + "\n全屏采样=" + observation + (actual == null ? "" : "\n全屏强制手势=" + actual.getInsets(WindowInsets.Type.mandatorySystemGestures())
            + ", 可见导航=" + actual.getInsets(WindowInsets.Type.navigationBars()) + ", 忽略可见性的导航=" + actual.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()) + ", 缺口=" + actual.getDisplayCutout());
    }
    void reset() { geometry = null; actual = null; observation = "尚未收到当前显示器／方向的完整窗口边衬"; }
    private static boolean same(WindowInsets a, WindowInsets b) {
        if (a == null || b == null) return a == b;
        int[] types = {WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout(), WindowInsets.Type.mandatorySystemGestures(), WindowInsets.Type.navigationBars()};
        for (int type : types) if (!a.getInsets(type).equals(b.getInsets(type))) return false;
        return a.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).equals(b.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()));
    }
}
