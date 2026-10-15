package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.util.ArrayList;
import java.util.List;

/** Owns display measurements, automatic/manual safety and display-to-window conversion.
 * UI consumers use contentBounds/Layout; capture and full-screen hosts explicitly use rawDisplaySize.
 * View-local measured sizes remain local, never replaced with a display's dimensions. */
final class SafeAreaManager {
    private static final java.util.concurrent.atomic.AtomicLong revisions = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Set<View> hosts = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private SafeAreaManager() { }
    /** Uncropped extent in this Display's coordinate space, including cutout/system-bar areas. */
    static Point rawDisplaySize(Display display) { Point size = new Point(); display.getRealSize(size); return size; }
    /** Hardware identity only. This is unrotated and is not a UI viewport. */
    static Point physicalSize(Display display) { Display.Mode mode = display.getMode(); return new Point(mode.getPhysicalWidth(), mode.getPhysicalHeight()); }
    static int rotation(Display display) { return display.getRotation(); }
    static DisplayCutout cutout(Display display) { return display.getCutout(); }
    static WindowMetrics maximumWindowMetrics(WindowManager windows) { return windows.getMaximumWindowMetrics(); }
    private static WindowMetrics currentWindowMetrics(Context context) { return context.getSystemService(WindowManager.class).getCurrentWindowMetrics(); }
    /** Actual host coordinates for diagnostics, raw MotionEvent coordinates and View-to-View deltas.
     * They already include the host's placement; subtracting safe-area insets here would apply them twice. */
    static void screenLocation(View view, int[] result) { view.getLocationOnScreen(result); }
    /** Visible Activity hosts reapply the same snapshot when runtime chrome or local preferences change. */
    static void observe(View root) {
        Prefs prefs = new Prefs(root.getContext());
        android.content.SharedPreferences.OnSharedPreferenceChangeListener changed = (values, key) -> {
            if (key == null || key.startsWith("safe_area_") || key.equals("display")) root.requestApplyInsets();
        };
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { hosts.add(view); prefs.data.registerOnSharedPreferenceChangeListener(changed); view.requestApplyInsets(); }
            public void onViewDetachedFromWindow(View view) { hosts.remove(view); prefs.data.unregisterOnSharedPreferenceChangeListener(changed); }
        });
        if (root.isAttachedToWindow()) { hosts.add(root); prefs.data.registerOnSharedPreferenceChangeListener(changed); root.requestApplyInsets(); }
    }
    static void geometryChanged() { for (View root : List.copyOf(hosts)) if (root.isAttachedToWindow()) root.requestApplyInsets(); }
    record Snapshot(int displayId, String identity, int rotation, int width, int height, float density,
                    boolean manual, boolean mismatch, Insets automatic, Insets effective, List<DockGeometry.Box> cutouts, long revision) {
        Snapshot(int displayId, String identity, int rotation, int width, int height, float density, boolean manual, boolean mismatch, Insets automatic, Insets effective, List<DockGeometry.Box> cutouts) {
            this(displayId, identity, rotation, width, height, density, manual, mismatch, automatic, effective, cutouts, revisions.incrementAndGet());
        }
        Snapshot { cutouts = List.copyOf(cutouts); }
        DockGeometry.Box rawFrame() { return new DockGeometry.Box(0, 0, width, height); }
        DockGeometry.Box frame() { return new SafeAreaGeometry.Edges(effective.left, effective.top, effective.right, effective.bottom).frame(width, height); }
        DockGeometry.Box contentFrame() { return frame().intersect(DockGeometry.physicalContent(width, height, cutouts)); }
        SafeAreaGeometry.Edges automaticEdges() { return new SafeAreaGeometry.Edges(automatic.left, automatic.top, automatic.right, automatic.bottom); }
        String diagnostics() { return "安全区=" + (manual ? "手动" : "自动") + (mismatch ? "（校准尺寸或外屏不匹配，手动值已保留）" : "") + ", revision=" + revision + ", rotation=" + rotation + ", size=" + width + "×" + height + "\n安全区自动边衬=" + automatic + "\n安全区生效边衬=" + effective + "\n安全区矩形=" + frame(); }
    }
    record Layout(DockGeometry.Placement dock, DockGeometry.Placement entry, DockGeometry.Box card, DockGeometry.Box status,
                  DockGeometry.Box controlStatus, DockGeometry.Box hub, DockGeometry.Box hubAvailable, DockGeometry.Box tasks, DockGeometry.Box widget) { }
    record Chrome(DockGeometry.Placement dock, DockGeometry.Placement anchor, int homeInset, int navigationInset, Insets handleInsets) { }
    static Chrome chrome(Context context, Prefs prefs, Snapshot area, Insets mandatory, Insets navigation, Insets navigationBounds) {
        DockGeometry.Placement anchor = DockGeometry.resolve(area.width(), area.height(), area.cutouts(), area.density(), prefs.corner(area.rotation()), prefs.widthRatio(), prefs.heightRatio(), prefs.data.getBoolean("auto_placement", true), area.rotation(), prefs.reverseLandscapeLayout(area.rotation()));
        DockGeometry.Placement dock = anchor; int navigationInset = 0;
        if (area.manual()) {
            dock = SafeAreaGeometry.outside(DockGeometry.edgeTouch(anchor, area.width(), area.height()), area.contentFrame(), area.width(), area.height(), area.cutouts(), area.density());
            return new Chrome(dock, dock, area.effective().bottom, 0, area.effective());
        }
        DockGeometry.Box allowed = area.rawFrame();
        if (prefs.avoidNavigation()) {
            Insets safe = Insets.max(mandatory, navigationBounds);
            navigationInset = switch (dock.edge()) { case DockGeometry.TOP -> safe.top; case DockGeometry.LEFT -> safe.left; case DockGeometry.RIGHT -> safe.right; default -> safe.bottom; };
            int minimum = Ui.dp(context, 16), gap = Ui.dp(context, prefs.navigationGap());
            allowed = new SafeAreaGeometry.Edges(Math.max(safe.left, minimum) + gap, Math.max(safe.top, minimum) + gap, Math.max(safe.right, minimum) + gap, Math.max(safe.bottom, minimum) + gap).frame(area.width(), area.height());
            dock = DockGeometry.avoidEdge(dock, area.width(), area.height(), Math.max(navigationInset, minimum), gap);
        } else dock = DockGeometry.edgeTouch(dock, area.width(), area.height());
        dock = SafeAreaGeometry.outside(dock, area.contentFrame(), area.width(), area.height(), area.cutouts(), area.density(), allowed);
        return new Chrome(dock, anchor, Math.max(mandatory.bottom, navigation.bottom), navigationInset, Insets.max(area.effective(), mandatory));
    }
    static String identity(Display display) { Point size = physicalSize(display); return display.getDisplayId() + ":" + display.getName() + ":" + size.x + "x" + size.y; }
    static Insets automaticInsets(WindowInsets insets, DisplayCutout cutout) {
        Insets safe = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.mandatorySystemGestures() | WindowInsets.Type.displayCutout());
        return Insets.max(safe, cutoutInsets(cutout));
    }
    static Insets cutoutInsets(DisplayCutout cutout) { return cutout == null ? Insets.NONE : Insets.of(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(), cutout.getSafeInsetRight(), cutout.getSafeInsetBottom()); }
    static Snapshot resolve(Context context, Prefs prefs, Display display, Point size, Insets automatic, SafeAreaGeometry.Profile preview) {
        SafeAreaGeometry.Profile profile = preview == null ? prefs.safeArea(display.getRotation()) : preview;
        boolean matches = profile != null && profile.matches(identity(display), size.x, size.y);
        boolean manual = matches && profile.manual();
        Insets effective = automatic;
        if (manual) { SafeAreaGeometry.Edges e = profile.edges().clipped(); effective = Insets.of(e.left(), e.top(), e.right(), e.bottom()); }
        List<DockGeometry.Box> cutouts = new ArrayList<>();
        if (display.getCutout() != null) for (android.graphics.Rect r : display.getCutout().getBoundingRects()) cutouts.add(new DockGeometry.Box(r.left, r.top, r.width(), r.height()));
        return new Snapshot(display.getDisplayId(), identity(display), display.getRotation(), size.x, size.y, context.getResources().getDisplayMetrics().density, manual, profile != null && profile.manual() && !matches, automatic, effective, List.copyOf(cutouts));
    }
    static Layout contents(Context context, Prefs prefs, Snapshot area, DockGeometry.Placement dock, DockGeometry.Placement anchor, int homeInset) {
        int width = area.width(), height = area.height(), rotation = area.rotation(); float density = area.density(); List<DockGeometry.Box> cutouts = area.cutouts();
        DockGeometry.Box systemSafe = area.frame(), physical = DockGeometry.physicalContent(width, height, cutouts), card = physical.intersect(systemSafe);
        int statusHeight = Math.round(Ui.dp(context, 20) * prefs.statusScale() / 100f);
        DockGeometry.Box controlStatus = DockGeometry.statusBar(card, width, statusHeight, density).intersect(systemSafe);
        String position = prefs.entryPosition(rotation);
        DockGeometry.Box entrySpace = area.manual() ? card : DockGeometry.panelContent(dock, width, height, cutouts);
        int topInset = DockGeometry.panelEntryTopInset(position, prefs.statusScale(), density);
        if (!position.equals("bottom_right")) topInset += Math.max(0, card.y() - entrySpace.y());
        DockGeometry.Placement entry = DockGeometry.panelEntry(anchor, dock, width, height, cutouts, density, homeInset, position, topInset, entrySpace);
        if (area.manual()) {
            entry = SafeAreaGeometry.constrain(entry, systemSafe, cutouts);
            entry = new DockGeometry.Placement(entry.visual(), entry.touch(), DockGeometry.panelContent(entry, width, height, cutouts).intersect(systemSafe), entry.edge(), entry.measured());
        }
        DockGeometry.Box content = physical.intersect(entry.panel()), status = prefs.statusEnabled() ? controlStatus : null;
        if (status != null) { int top = Math.max(content.y(), status.bottom() + Ui.dp(context, 4)); content = new DockGeometry.Box(content.x(), top, content.width(), Math.max(0, content.bottom() - top)); }
        DockGeometry.Placement chrome = new DockGeometry.Placement(dock.visual(), dock.touch(), content, dock.edge(), dock.measured());
        DockGeometry.Box hub = DockGeometry.hubContent(physical, chrome.panel(), systemSafe), available = hub, tasks = hub;
        content = content.intersect(systemSafe);
        if (rotation == android.view.Surface.ROTATION_180) {
            entry = DockGeometry.compactTopEntry(entry, density);
            DockGeometry.Box space = physical.intersect(entry.panel()).intersect(systemSafe);
            content = DockGeometry.centerVertically(content, space);
            available = new DockGeometry.Box(hub.x(), space.y(), hub.width(), Math.max(0, hub.bottom() - space.y()));
            tasks = DockGeometry.centerVertically(hub, available);
        }
        DockGeometry.Placement result = new DockGeometry.Placement(chrome.visual(), chrome.touch(), content, chrome.edge(), chrome.measured());
        return new Layout(result, entry, area.contentFrame(), status, controlStatus, hub, available, tasks, DockGeometry.widgetContent(physical, entry, status, density).intersect(systemSafe));
    }
    /** The raw host stays fixed. Page paint and child interaction use distinct, nested rectangles. */
    record Boundary(DockGeometry.Box window, DockGeometry.Box page, DockGeometry.Box content) {
        Boundary { page = page.intersect(window); content = content.intersect(page); }
        DockGeometry.Box localPage() { return local(page); }
        DockGeometry.Box localContent() { return local(content); }
        private DockGeometry.Box local(DockGeometry.Box area) { return SafeAreaGeometry.localFrame(area, 1, window.x(), window.y(), window.width(), window.height()); }
    }
    static DockGeometry.Box mapViewBounds(View source, DockGeometry.Box bounds, View target) {
        if (source == target) return bounds;
        android.graphics.RectF result = new android.graphics.RectF(bounds.x(), bounds.y(), bounds.right(), bounds.bottom());
        android.graphics.Matrix from = new android.graphics.Matrix(), to = new android.graphics.Matrix(), inverse = new android.graphics.Matrix();
        source.transformMatrixToGlobal(from); target.transformMatrixToGlobal(to);
        if (!to.invert(inverse)) return new DockGeometry.Box(0, 0, 0, 0);
        from.mapRect(result); inverse.mapRect(result);
        int left = (int) Math.ceil(result.left), top = (int) Math.ceil(result.top);
        return new DockGeometry.Box(left, top, Math.max(0, (int) Math.floor(result.right) - left), Math.max(0, (int) Math.floor(result.bottom) - top));
    }
    static DockGeometry.Box viewContent(View root) {
        int w = root.getWidth() > 0 ? root.getWidth() : root.getMeasuredWidth(), h = root.getHeight() > 0 ? root.getHeight() : root.getMeasuredHeight();
        DockGeometry.Box local = new DockGeometry.Box(root.getPaddingLeft(), root.getPaddingTop(), Math.max(0, w - root.getPaddingLeft() - root.getPaddingRight()), Math.max(0, h - root.getPaddingTop() - root.getPaddingBottom()));
        for (View v = root; v != null; v = v.getParent() instanceof View parent ? parent : null) {
            if (v instanceof InterfaceCard card && card.safeArea() != null) return local.intersect(mapViewBounds(card, card.safeArea(), root));
            if (v instanceof InputSurface input && input.explicitContent() != null) return local.intersect(mapViewBounds(input, input.explicitContent(), root));
        }
        if (root instanceof android.view.ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof InterfaceCard card && card.acceptsInput() && card.safeArea() != null) return local.intersect(mapViewBounds(card, card.safeArea(), root));
        }
        return local;
    }
    static boolean contains(DockGeometry.Box area, float x, float y) { return x >= area.x() && y >= area.y() && x < area.right() && y < area.bottom(); }
    /** RoundedCorner values are already pixels in the owning window's coordinate space. */
    static float physicalCornerRadius(WindowInsets insets, float fallback) {
        if (android.os.Build.VERSION.SDK_INT < 31 || insets == null) return fallback;
        float radius = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) { android.view.RoundedCorner corner = insets.getRoundedCorner(i); if (corner != null && corner.getRadius() > 0) radius = Math.min(radius, corner.getRadius()); }
        return radius == Float.MAX_VALUE ? fallback : Math.max(0, radius - 1);
    }
    /** Static safety margins never mean a lifted page. Only the actual scene motion changes its radius. */
    static float pageRadius(View owner, float width, float height) {
        float limit = Math.max(0, Math.min(width, height) / 2), floating = Math.min(Ui.dp(owner.getContext(), 32), limit);
        WindowInsets insets = owner.getRootWindowInsets();
        float resting = Math.min(limit, physicalCornerRadius(insets, insets == null || android.os.Build.VERSION.SDK_INT < 31 ? floating : 0));
        float progress = Math.min(1, (float) Math.hypot(owner.getTranslationX(), owner.getTranslationY()) / Math.max(1, floating * 2));
        return resting + (floating - resting) * progress;
    }
    static DockGeometry.ClockHandle clockHandle(Snapshot area, DockGeometry.Placement dock, DockGeometry.Placement entry, DockGeometry.Box status, Insets insets) {
        int top = Math.max(insets.top, status == null ? 0 : status.bottom());
        DockGeometry.Box available = new DockGeometry.Box(insets.left, top, Math.max(0, area.width() - insets.left - insets.right), Math.max(0, area.height() - top - insets.bottom));
        return DockGeometry.clockHandlePositions(dock, DockGeometry.clockHandle(dock, area.width(), area.height(), area.cutouts(), area.density(), available, entry.touch()));
    }
    /** Native hosts own their automatic system-bar insets. Cached runtime chrome includes live drafts;
     * without it, use physical cutout plus saved manual bounds, then map into the actual host once. */
    static DockGeometry.Box nativeContent(Context context, Prefs prefs, Display display, Point size, DockGeometry.Box runtime) {
        if (runtime != null) return runtime;
        Snapshot area = resolve(context, prefs, display, size, cutoutInsets(cutout(display)), null);
        return area.contentFrame();
    }
    static WidgetSafeArea.Frame nativeFrame(float width, float height, Point display, float density, DockGeometry.Box safe, DockGeometry.Box host) {
        return WidgetSafeArea.fit(width, height, display.x, display.y, density, safe, host);
    }
    /** Never construct a display snapshot from a compatibility-scaled Activity Display.
     * Fetch display, dimensions, cutout and density together from the application display context. */
    private static Snapshot currentArea(Context context) {
        Display current;
        try { current = context.getDisplay(); } catch (UnsupportedOperationException unavailable) { return null; }
        Prefs prefs = new Prefs(context);
        Context application = context.getApplicationContext(); if (application == null) application = context;
        Display selected = Displays.selected(application, prefs);
        if (current == null || selected == null || current.getDisplayId() != selected.getDisplayId()) return null;
        CoverService service = CoverService.instance; Snapshot area = service == null ? null : service.safeAreaSnapshot();
        Point size = rawDisplaySize(selected);
        if (area != null && area.displayId() == selected.getDisplayId() && area.rotation() == rotation(selected)
            && area.identity().equals(identity(selected)) && area.width() == size.x && area.height() == size.y) return area;
        Context source = application.createDisplayContext(selected);
        WindowInsets insets = maximumWindowMetrics(source.getSystemService(WindowManager.class)).getWindowInsets();
        return resolve(source, prefs, selected, size, automaticInsets(insets, cutout(selected)), null);
    }
    private static DockGeometry.Box localContent(Context context, Rect window, WindowInsets insets) {
        Insets fallback = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        DockGeometry.Box standard = new SafeAreaGeometry.Edges(fallback.left, fallback.top, fallback.right, fallback.bottom).frame(window.width(), window.height());
        Snapshot area = currentArea(context); if (area == null) return standard;
        // Compatibility bounds are not proportional to resource density (Samsung can use
        // 854x822 at 320dpi over a 748x720 overlay at 340dpi). Map actual coordinate extents.
        Rect maximum = maximumWindowMetrics(context.getSystemService(WindowManager.class)).getBounds();
        float scaleX = maximum.width() / (float) area.width(), scaleY = maximum.height() / (float) area.height();
        if (scaleX <= 0 || scaleY <= 0 || Math.abs(scaleX - scaleY) > Math.max(scaleX, scaleY) * .02f) return standard;
        CoverService service = CoverService.instance;
        DockGeometry.Box content = service == null ? null : service.activityContentBounds(area);
        return mapContent(area, content == null ? area.contentFrame() : content, maximum, window, insets.getInsets(WindowInsets.Type.ime()));
    }
    static DockGeometry.Box mapContent(Snapshot area, Rect maximum, Rect window, Insets ime) {
        return mapContent(area, area.contentFrame(), maximum, window, ime);
    }
    static DockGeometry.Box mapContent(Snapshot area, DockGeometry.Box content, Rect maximum, Rect window, Insets ime) {
        float scaleX = maximum.width() / (float) area.width(), scaleY = maximum.height() / (float) area.height();
        DockGeometry.Box local = SafeAreaGeometry.localFrame(content.intersect(area.contentFrame()), scaleX, scaleY, window.left - maximum.left, window.top - maximum.top, window.width(), window.height());
        return local.intersect(new SafeAreaGeometry.Edges(ime.left, ime.top, ime.right, ime.bottom).frame(window.width(), window.height()));
    }
    /** Available UI pixels in this window's local coordinates; also used before its views are measured. */
    static DockGeometry.Box contentBounds(Context context) {
        WindowMetrics metrics = currentWindowMetrics(context);
        return localContent(context, metrics.getBounds(), metrics.getWindowInsets());
    }
    static String windowDiagnostics(Context context) {
        try {
            WindowMetrics window = currentWindowMetrics(context), maximum = maximumWindowMetrics(context.getSystemService(WindowManager.class));
            Snapshot area = currentArea(context);
            return "\n设置窗口坐标：maximum=" + maximum.getBounds() + ", current=" + window.getBounds()
                + ", 安全区来源=" + (area == null ? "当前宿主窗口" : area.width() + "×" + area.height() + " / revision=" + area.revision())
                + ", 局部可用=" + localContent(context, window.getBounds(), window.getWindowInsets()) + "\n";
        } catch (RuntimeException unavailable) { return "\n设置窗口坐标暂不可用：" + unavailable.getClass().getSimpleName() + "\n"; }
    }
    /** Activity windows can be smaller/offset by their host. Convert once from display to local pixels. */
    static Insets activityInsets(android.app.Activity activity, View root, WindowInsets insets) {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        int[] origin = new int[2]; screenLocation(root, origin);
        DockGeometry.Box local = localContent(activity, new Rect(origin[0], origin[1], origin[0] + w, origin[1] + h), insets);
        return Insets.of(local.x(), local.y(), w - local.right(), h - local.bottom());
    }
}
