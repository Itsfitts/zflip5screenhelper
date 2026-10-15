package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** Real attached pages: static safety margins must not select the floating corner radius. */
final class PageCornerChecks {
    private final Instrumentation test;
    private int assertions;
    PageCornerChecks(Instrumentation test) { this.test = test; }
    String run(int rotation) {
        Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(test.getUiAutomation().setRotation(rotation), "test rotation accepted"); android.os.SystemClock.sleep(600); test.waitForIdleSync();
        Activity current = monitor.getLastActivity() != null ? monitor.getLastActivity() : activity; test.removeMonitor(monitor);
        return check(current, rotation);
    }
    private String check(Activity activity, int rotation) {
        require(activity.getDisplay().getRotation() == rotation, "actual rotation matches request");
        InterfaceCard[] card = {null};
        try {
            test.waitForIdleSync();
            for (InterfaceCard.Definition definition : new InterfaceCard.Definition[]{InterfaceCard.CONTROLS, InterfaceCard.NOTIFICATIONS, InterfaceCard.TASKS, InterfaceCard.LAUNCHER}) for (boolean inset : new boolean[]{false, true}) {
                test.runOnMainSync(() -> {
                    if (card[0] != null) card[0].release();
                    View root = activity.getWindow().getDecorView(); int w = root.getWidth(), h = root.getHeight();
                    FrameLayout host = new FrameLayout(activity); host.setBackgroundColor(Color.DKGRAY);
                    card[0] = new InterfaceCard(activity, new Prefs(activity), definition, new View(activity), () -> { }, () -> { });
                    card[0].frameBounds(new DockGeometry.Box(0, 0, w, h), inset ? new DockGeometry.Box(18, 27, w - 49, h - 100) : new DockGeometry.Box(0, 0, w, h));
                    host.addView(card[0]); activity.setContentView(host);
                });
                test.waitForIdleSync();
                test.runOnMainSync(() -> {
                    InterfaceCard view = card[0]; WindowInsets actual = view.getRootWindowInsets(); require(actual != null, "attached page has real window insets");
                    float expected = Float.MAX_VALUE;
                    for (int i = 0; i < 4; i++) { android.view.RoundedCorner corner = actual.getRoundedCorner(i); if (corner != null && corner.getRadius() > 0) expected = Math.min(expected, corner.getRadius()); }
                    expected = expected == Float.MAX_VALUE ? 0 : Math.max(0, expected - 1);
                    Outline outline = new Outline(); view.getBackground().getOutline(outline);
                    require(Math.abs(outline.getRadius() - expected) < .1f, "fallback follows physical corners even with static safe margins: " + definition.id());
                    Bitmap sample = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); sample.eraseColor(0xFF335577);
                    PanelGlassSession session = new PanelGlassSession(activity, activity.getDisplay()); session.fixture(sample); sample.recycle(); view.glass(session);
                    view.getBackground().getOutline(outline); require(Math.abs(outline.getRadius() - expected) < .1f, "glass and fallback share the same resting outline");
                    view.setTranslationX(25); view.getBackground().getOutline(outline); float horizontal = outline.getRadius();
                    view.setTranslationX(0); view.setTranslationY(25); view.getBackground().getOutline(outline);
                    require(Math.abs(horizontal - outline.getRadius()) < .1f, "horizontal and vertical scene motion use the same radius transition");
                    view.setTranslationY(0); view.getBackground().getOutline(outline); require(Math.abs(outline.getRadius() - expected) < .1f, "returning to rest restores the physical corner");
                    view.dropGlass(); view.getBackground().getOutline(outline); require(Math.abs(outline.getRadius() - expected) < .1f, "releasing glass cannot restore the old fixed 32dp corner");
                });
                float[] renderedRadius = {-1}; int[] outlines = {0};
                test.runOnMainSync(() -> card[0].setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) { view.getBackground().getOutline(outline); renderedRadius[0] = outline.getRadius(); outlines[0]++; }
                }));
                android.os.SystemClock.sleep(80); test.waitForIdleSync(); int before = outlines[0]; float resting = renderedRadius[0];
                test.runOnMainSync(() -> InterfaceCard.translate(card[0], DockGeometry.BOTTOM, 200));
                android.os.SystemClock.sleep(80); test.waitForIdleSync();
                require(outlines[0] > before && Math.abs(renderedRadius[0] - Ui.dp(activity, 32)) < .1f, "Android refreshes the cached fallback outline during Home/scene movement");
                before = outlines[0]; test.runOnMainSync(() -> InterfaceCard.translate(card[0], DockGeometry.BOTTOM, 0));
                android.os.SystemClock.sleep(80); test.waitForIdleSync();
                require(outlines[0] > before && Math.abs(renderedRadius[0] - resting) < .1f, "Android restores the cached resting outline after fallback movement");
            }
            return "PASS: page-corners rotation=" + rotation + "; " + assertions + " assertions; four attached page types, full/inset bounds, glass/fallback and both scene axes";
        } finally { test.runOnMainSync(() -> { if (card[0] != null) card[0].release(); activity.finish(); }); test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE); }
    }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
