package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

/** Production reconciliation on an isolated display, with Activity-owned test windows. */
final class WindowStabilityChecks {
    private final Instrumentation test;
    private final Activity activity;
    private CoverService owner;
    private WindowInsets insets;
    private int mutations, detached, assertions;
    private long entryHideAt;
    WindowStabilityChecks(Instrumentation test, Activity activity) { this.test = test; this.activity = activity; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    int run() {
        Prefs prefs = new Prefs(activity);
        java.util.Map<String, ?> original = prefs.data.getAll();
        CoverService previous = CoverService.instance;
        android.media.ImageReader surface = android.media.ImageReader.newInstance(748, 720, android.graphics.PixelFormat.RGBA_8888, 2);
        android.hardware.display.VirtualDisplay target = null;
        try {
            target = activity.getSystemService(android.hardware.display.DisplayManager.class).createVirtualDisplay("chrome-insets-test", 748, 720, 340, surface.getSurface(), 0);
            require(target != null && target.getDisplay().getState() == android.view.Display.STATE_ON, "test display is on");
            android.view.Display display = target.getDisplay();
            for (boolean avoid : new boolean[]{false, true}) for (int corner = 0; corner < 4; corner++) {
                int selectedCorner = corner;
                try {
                    test.runOnMainSync(() -> {
                        prefs.data.edit().putInt("display", display.getDisplayId()).putBoolean("enabled", true).putBoolean("status_enabled", true)
                            .putBoolean("dock_expandable", true).putBoolean("avoid_navigation", avoid).putInt("corner_" + display.getRotation(), selectedCorner).putString("panel_entry_0", "top_left").commit();
                        create(display, prefs);
                    });
                    test.waitForIdleSync();
                    test.runOnMainSync(this::startArrow);
                    test.waitForIdleSync();
                    test.runOnMainSync(this::checkTransitions);
                    test.waitForIdleSync();
                    test.runOnMainSync(() -> { insets = metrics(24, 66); reconcile(); });
                    test.waitForIdleSync();
                    test.runOnMainSync(() -> {
                        require(detached == 0, "insets never detach status or arrow after pending layout callbacks");
                        var entry = (PanelEntryView) field(owner, "panelEntry");
                        var placement = (DockGeometry.Placement) field(owner, "panelEntryPlacement");
                        require(field(entry, "chrome").equals(DockGeometry.panelEntryChrome(placement, owner.screenContext.getResources().getDisplayMetrics().density)), "white-bar drawing follows final safe area after resize");
                        require(entry.getAlpha() == .5f && (long) field(entry, "hideAt") == entryHideAt, "white-bar resize preserves visibility and the original fade deadline");
                    });
                } finally { test.runOnMainSync(this::close); }
            }
            return assertions;
        } finally {
            try { test.runOnMainSync(() -> { close(); CoverService.instance = previous; restore(prefs, original); }); }
            finally { try { if (target != null) target.release(); } finally { surface.close(); } }
        }
    }
    private void create(android.view.Display display, Prefs prefs) {
        owner = new CoverService(); owner.prefs = prefs; owner.display = display; owner.screenContext = activity.createDisplayContext(display); CoverService.instance = owner;
        try { var attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class); attach.setAccessible(true); attach.invoke(owner, activity); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        insets = metrics(0, 0);
        WindowManager actual = activity.getWindowManager();
        WindowManager proxy = (WindowManager) java.lang.reflect.Proxy.newProxyInstance(WindowManager.class.getClassLoader(), new Class<?>[]{WindowManager.class}, (instance, method, args) -> {
            if (method.getName().equals("getMaximumWindowMetrics")) return new WindowMetrics(new Rect(0, 0, 748, 720), insets);
            if (method.getName().equals("removeViewImmediate")) { mutations++; actual.removeViewImmediate((View) args[0]); }
            if (method.getName().equals("addView") || method.getName().equals("updateViewLayout")) {
                var source = (WindowManager.LayoutParams) args[1]; var layout = new WindowManager.LayoutParams(); layout.copyFrom(source);
                layout.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL; layout.token = activity.getWindow().getDecorView().getWindowToken();
                if (method.getName().equals("addView")) { mutations++; actual.addView((View) args[0], layout); } else actual.updateViewLayout((View) args[0], layout);
            }
            return null;
        });
        field(owner, "windows", proxy);
        geometry("resolveWindowGeometry");
        field(owner, "dockPlacement", owner.placement);
        DockView.Listener listener = new DockView.Listener() { public void action(String id) { } public void configure() { } };
        DockView dock = new DockView(owner.screenContext, prefs, owner.placement, 0, listener, true); field(owner, "dock", dock);
        dock.setVisibility(View.GONE); proxy.addView(dock, (WindowManager.LayoutParams) invoke(owner, "dockParameters"));
        PanelEntryView entry = new PanelEntryView(owner.screenContext, prefs, (DockGeometry.Placement) field(owner, "panelEntryPlacement"), listener); field(owner, "panelEntry", entry);
        proxy.addView(entry, (WindowManager.LayoutParams) invoke(owner, "panelEntryParameters"));
        StatusBarView status = new StatusBarView(owner.screenContext, prefs); field(owner, "statusBar", status); proxy.addView(status, (WindowManager.LayoutParams) invoke(owner, "statusParameters"));
        ((DockVisibility) field(owner, "dockVisibility")).expandable(true);
        String key = display.getDisplayId() + ":" + display.getRotation() + ":" + Displays.size(display) + ":" + owner.screenContext.getResources().getDisplayMetrics().density + ":" + display.getCutout();
        field(owner, "displaySignature", key); field(owner, "signature", "initial-fixture");
    }
    private void startArrow() {
        invoke(owner, "syncDockVisibility");
        require(field(owner, "dockHandle") != null, "fixture mounts arrow after entry window is attached");
        mutations = detached = 0;
        View.OnAttachStateChangeListener lifecycle = new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { }
            public void onViewDetachedFromWindow(View view) { detached++; }
        };
        ((View) field(owner, "statusBar")).addOnAttachStateChangeListener(lifecycle); ((View) field(owner, "dockHandle")).addOnAttachStateChangeListener(lifecycle);
    }
    private void checkTransitions() {
        DockHandleView arrow = (DockHandleView) field(owner, "dockHandle");
        DockView dock = (DockView) field(owner, "dock");
        StatusBarView status = (StatusBarView) field(owner, "statusBar");
        PanelEntryView entry = (PanelEntryView) field(owner, "panelEntry");
        DockVisibility state = (DockVisibility) field(owner, "dockVisibility");
        entry.animate().cancel(); entry.setAlpha(.5f); field(entry, "revealed", true);
        entryHideAt = android.os.SystemClock.uptimeMillis() + 10000; field(entry, "hideAt", entryHideAt);
        entry.postDelayed((Runnable) field(entry, "hideHandles"), 10000);
        float alpha = arrow.getAlpha();
        for (int[] next : new int[][]{{0, 66}, {24, 66}, {24, 0}, {0, 0}, {24, 66}}) {
            insets = metrics(next[0], next[1]); reconcile();
            require(field(owner, "dockHandle") == arrow && field(owner, "statusBar") == status && field(owner, "dock") == dock && field(owner, "panelEntry") == entry, "app insets retain all chrome view identities");
            require(mutations == 0 && detached == 0 && arrow.isAttachedToWindow() && status.isAttachedToWindow(), "app insets perform no chrome remove/add operations");
            require(arrow.getAlpha() == alpha && status.getVisibility() == View.VISIBLE && (boolean) field(status, "running"), "arrow alpha and status subscription survive app transitions");
            require(bounds(status).equals(field(owner, "statusBox")), "status window uses updated safe bounds");
            require(bounds(arrow).equals(((DockGeometry.ClockHandle) field(owner, "dockHandlePositions")).collapsed()), "collapsed arrow uses current dock geometry without animation");
            require(field(entry, "chrome").equals(DockGeometry.panelEntryChrome((DockGeometry.Placement) field(owner, "panelEntryPlacement"), owner.screenContext.getResources().getDisplayMetrics().density)), "entry artwork updates along with insets");
        }
        state.toggle(false, java.util.Set.of()); invoke(owner, "syncDockVisibility");
        android.animation.ValueAnimator motion = (android.animation.ValueAnimator) field(owner, "dockHandleMotion");
        require(motion != null, "explicit toggle begins animation before insets change");
        Object previousPositions = field(owner, "dockHandlePositions");
        DockGeometry.Box previousDock = bounds(dock), previousArrow = bounds(arrow);
        down(dock); down(arrow); down(entry);
        insets = metrics(0, 0); reconcile();
        if (!previousDock.equals(bounds(dock))) require(field(dock, "touchMode").toString().equals("CONSUMED"), "dock relocation cancels its old press and long-press owner");
        if (!previousArrow.equals(bounds(arrow))) require(!(boolean) field(arrow, "pending"), "arrow relocation cancels its old press");
        require(!(boolean) field(entry, "pending"), "entry relocation cancels its old press");
        cancel(dock); cancel(arrow); cancel(entry);
        // Input cancellation has its own fade policy; restore the visible geometry-only fixture.
        entry.animate().cancel(); entry.setAlpha(.5f); field(entry, "revealed", true); field(entry, "hideAt", entryHideAt);
        entry.removeCallbacks((Runnable) field(entry, "hideHandles")); entry.postDelayed((Runnable) field(entry, "hideHandles"), Math.max(0, entryHideAt - android.os.SystemClock.uptimeMillis()));
        if (previousPositions.equals(field(owner, "dockHandlePositions"))) {
            require(field(owner, "dockHandleMotion") == motion, "unaffected arrow geometry preserves the user animation"); motion.end();
        }
        require(field(owner, "dockHandle") == arrow && !dock.compact() && field(owner, "dockHandleMotion") == null, "new geometry cancels old movement while retaining expanded state");
        require(bounds(arrow).equals(((DockGeometry.ClockHandle) field(owner, "dockHandlePositions")).expanded()) && dock.getTranslationX() == 0 && dock.getTranslationY() == 0, "expanded arrow and buttons settle in current geometry");
        require((((WindowManager.LayoutParams) dock.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0, "settling restores shortcut touch");
        require(mutations == 0 && detached == 0, "animation cancellation never detaches chrome");
        field(owner, "homeClosing", true); require(!(boolean) geometry("updateWindowInsets"), "home transition keeps its existing lifecycle owner"); field(owner, "homeClosing", false);
    }
    private WindowInsets metrics(int top, int bottom) {
        return new WindowInsets.Builder().setInsets(WindowInsets.Type.statusBars(), Insets.of(0, top, 0, 0)).setVisible(WindowInsets.Type.statusBars(), top > 0)
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bottom)).setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bottom)).setVisible(WindowInsets.Type.navigationBars(), bottom > 0).build();
    }
    private void down(View view) { event(view, android.view.MotionEvent.ACTION_DOWN); }
    private void cancel(View view) { event(view, android.view.MotionEvent.ACTION_CANCEL); }
    private void event(View view, int action) { long now = android.os.SystemClock.uptimeMillis(); var event = android.view.MotionEvent.obtain(now, now, action, Math.max(1, view.getWidth() / 2f), Math.max(1, view.getHeight() / 2f), 0); view.onTouchEvent(event); event.recycle(); }
    private Object geometry(String method) {
        try {
            var target = CoverService.class.getDeclaredMethod(method, android.view.Display.class, Point.class, int.class, Insets.class, Insets.class, Insets.class, Insets.class); target.setAccessible(true);
            return target.invoke(owner, owner.display, Displays.size(owner.display), owner.display.getRotation(), insets.getInsets(WindowInsets.Type.mandatorySystemGestures()), insets.getInsets(WindowInsets.Type.navigationBars()), insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()), CoverService.contentInsets(insets, owner.display.getCutout()));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void reconcile() { try { var method = CoverService.class.getDeclaredMethod("reconcile", boolean.class); method.setAccessible(true); method.invoke(owner, false); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private DockGeometry.Box bounds(View view) { var p = (WindowManager.LayoutParams) view.getLayoutParams(); return new DockGeometry.Box(p.x, p.y, p.width, p.height); }
    private Object field(Object target, String name) { try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void field(Object target, String name, Object value) { try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private Object invoke(Object target, String name) { try { var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void close() {
        if (owner == null) return;
        try { invoke(owner, "removeWindows"); }
        finally { owner.main.removeCallbacksAndMessages(null); ((java.util.concurrent.ExecutorService) field(owner, "files")).shutdown(); owner = null; }
    }
    private void restore(Prefs prefs, java.util.Map<String, ?> values) {
        var edit = prefs.data.edit().clear();
        for (var entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean v) edit.putBoolean(key, v); else if (value instanceof Integer v) edit.putInt(key, v); else if (value instanceof Long v) edit.putLong(key, v); else if (value instanceof Float v) edit.putFloat(key, v); else if (value instanceof String v) edit.putString(key, v); else if (value instanceof java.util.Set<?> v) edit.putStringSet(key, new java.util.HashSet<>(v.stream().map(Object::toString).toList()));
        }
        edit.commit();
    }
}
