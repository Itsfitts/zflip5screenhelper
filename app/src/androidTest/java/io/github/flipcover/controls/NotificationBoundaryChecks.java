package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Rendered reading-edge regression, including a high-contrast desktop behind the reserved strip. */
final class NotificationBoundaryChecks {
    private final Instrumentation test;
    private Activity activity;
    private InterfaceCard card;
    private NotificationScrollView scroll;
    private View rows;
    private DockGeometry.Box page, content;
    private int width, height, assertions;
    NotificationBoundaryChecks(Instrumentation test) { this.test = test; }
    String run(int rotation) {
        Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        require(test.getUiAutomation().setRotation(rotation), "rotation accepted"); idle();
        if (monitor.getLastActivity() != null) activity = monitor.getLastActivity(); test.removeMonitor(monitor);
        try {
            require(activity.getDisplay().getRotation() == rotation, "actual rotation matches request");
            main(() -> { activity.getWindow().setDecorFitsSystemWindows(false); activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); }); idle();
            for (boolean inset : new boolean[]{false, true}) {
                mount(inset, InterfaceCard.NOTIFICATIONS); checkFade();
                main(() -> { scroll.setVisibility(View.INVISIBLE); glass(); }); idle();
                Bitmap pixels = test.getUiAutomation().takeScreenshot();
                double reserved, interior;
                try { reserved = variance(pixels, content.bottom() + 16); interior = variance(pixels, content.y() + 100); }
                finally { pixels.recycle(); }
                require(interior > 1000 && reserved < interior * .15, "reserved strip blurs desktop detail instead of exposing icons: " + reserved + " / " + interior);
                // The shader is shared by the whole page. Drawing a local surface must
                // reset readingEdge, and cannot inherit the page's content coordinates.
                FrameLayout[] probe = {null};
                main(() -> { probe[0] = new FrameLayout(activity); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(160, 120); p.leftMargin = width / 2 - 80; p.topMargin = content.y() + 90; card.addView(probe[0], p); card.glass().bind(probe[0], GlassSurface.Role.NOTIFICATION_PAGE); }); idle();
                pixels = test.getUiAutomation().takeScreenshot();
                try { require(variance(pixels, content.y() + 145, width / 2 - 45, width / 2 + 45) > 1000, "local surfaces retain sharp interiors after the page mask"); } finally { pixels.recycle(); }
                main(() -> card.removeView(probe[0]));
                mount(inset, InterfaceCard.TASKS); main(() -> { scroll.setVisibility(View.INVISIBLE); glass(); }); idle();
                pixels = test.getUiAutomation().takeScreenshot();
                try { require(variance(pixels, content.bottom() + 16) > reserved * 2, "notification reading mask does not change other page materials"); } finally { pixels.recycle(); }
            }
            return "PASS: notification-boundaries rotation=" + rotation + "; " + assertions + " assertions; full/inset pages, scrolling/end/short lists, hardware fades and background detail suppression";
        } finally { main(() -> { if (card != null) card.release(); activity.finish(); }); test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE); }
    }
    private void mount(boolean inset, InterfaceCard.Definition definition) {
        main(() -> {
            if (card != null) card.release(); View decor = activity.getWindow().getDecorView(); width = decor.getWidth(); height = decor.getHeight();
            page = inset ? new DockGeometry.Box(21, 27, width - 54, height - 65) : new DockGeometry.Box(0, 0, width, height);
            content = new DockGeometry.Box(page.x() + 16, page.y() + 48, page.width() - 32, page.height() - 138);
            LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
            View header = new View(activity); header.setBackgroundColor(Color.GREEN); body.addView(header, new LinearLayout.LayoutParams(-1, 70));
            scroll = new NotificationScrollView(activity); scroll.setVerticalScrollBarEnabled(false); rows = new View(activity); rows.setTag("notification-center"); rows.setMinimumHeight(content.height() * 3); scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2)); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            card = new InterfaceCard(activity, new Prefs(activity), definition, body, () -> { }, () -> { }); card.frameBounds(new DockGeometry.Box(0, 0, width, height), page); card.contentBounds(content, new DockGeometry.Box(0, 0, width, height)); rows.setBackgroundColor(Color.WHITE);
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(Color.BLACK); root.addView(card); activity.setContentView(root);
        }); idle();
    }
    private void checkFade() {
        int band = Ui.dp(activity, 16), x = width / 2, bottom = content.bottom();
        for (int offset : new int[]{0, 127}) {
            main(() -> scroll.scrollTo(0, offset)); idle(); require(scroll.canScrollVertically(1), "long list can scroll further");
            Bitmap pixels = test.getUiAutomation().takeScreenshot();
            try {
                int before = Color.red(pixels.getPixel(x, bottom - band - 3)), middle = Color.red(pixels.getPixel(x, bottom - band / 2)), end = Color.red(pixels.getPixel(x, bottom - 2));
                require(before > 245 && middle > 80 && middle < 180 && end < 35, "list fades continuously at the viewport, independent of scroll offset: " + before + "/" + middle + "/" + end);
                require(Color.red(pixels.getPixel(x, bottom + 4)) == 0, "rows never paint inside the reserved strip");
                require(Color.green(pixels.getPixel(x, content.y() + 30)) > 245, "reading fade does not affect header");
            } finally { pixels.recycle(); }
        }
        main(() -> { scroll.setSmoothScrollingEnabled(false); scroll.fullScroll(View.FOCUS_DOWN); }); idle(); require(!scroll.canScrollVertically(1), "last row reached");
        Bitmap pixels = test.getUiAutomation().takeScreenshot();
        try { require(Color.red(pixels.getPixel(x, bottom - 2)) > 245, "last row remains fully visible without a permanent fade: pixel=" + Integer.toHexString(pixels.getPixel(x, bottom - 2)) + ", scroll=" + scroll.getScrollY() + ", viewport=" + scroll.getHeight() + ", row=" + rows.getHeight() + ", content=" + content); } finally { pixels.recycle(); }
        main(() -> { rows.setMinimumHeight(80); scroll.scrollTo(0, 0); }); idle(); require(!scroll.canScrollVertically(1), "short list needs no reading mask");
    }
    private void glass() {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) image.setPixel(x, y, x % 16 < 8 ? Color.WHITE : Color.BLACK);
        PanelGlassSession session = new PanelGlassSession(activity, activity.getDisplay()); session.fixture(image); image.recycle(); card.glass(session);
    }
    private double variance(Bitmap pixels, int y) { return variance(pixels, y, width / 2 - 80, width / 2 + 80); }
    private double variance(Bitmap pixels, int y, int left, int right) {
        double sum = 0, squares = 0; for (int x = left; x < right; x++) { int c = Color.red(pixels.getPixel(x, y)); sum += c; squares += c * c; }
        return squares / (right - left) - Math.pow(sum / (right - left), 2);
    }
    private void idle() { SystemClock.sleep(240); test.waitForIdleSync(); }
    private void main(Runnable action) { Throwable[] problem = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { problem[0] = error; } }); if (problem[0] != null) throw new AssertionError(problem[0]); }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
