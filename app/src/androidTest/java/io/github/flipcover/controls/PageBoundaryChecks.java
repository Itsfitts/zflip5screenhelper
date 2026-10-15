package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** Exercises page paint, content input and modal mapping separately, on the disposable emulator. */
final class PageBoundaryChecks {
    private final Instrumentation test;
    private int assertions;
    PageBoundaryChecks(Instrumentation test) { this.test = test; }
    String run() {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.runOnMainSync(() -> {
                for (int rotation = 0; rotation < 4; rotation++) {
                    int w = rotation % 2 == 0 ? 748 : 720, h = rotation % 2 == 0 ? 720 : 748;
                    // Deliberately asymmetric; the raw host never inherits the smaller page size.
                    DockGeometry.Box page = new DockGeometry.Box(31, 47, w - 86, h - 121);
                    DockGeometry.Box content = new DockGeometry.Box(43, 109, w - 112, h - 243);
                    for (InterfaceCard.Definition definition : new InterfaceCard.Definition[]{InterfaceCard.CONTROLS, InterfaceCard.NOTIFICATIONS, InterfaceCard.TASKS, InterfaceCard.LAUNCHER}) {
                        int[] clicks = {0}; View body = new View(activity); body.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_UP) clicks[0]++; return true; });
                        InterfaceCard card = new InterfaceCard(activity, new Prefs(activity), definition, body, () -> { }, () -> { });
                        InputSurface host = new InputSurface(activity); host.addView(card);
                        card.frameBounds(new DockGeometry.Box(0, 0, w, h), page); card.contentSafeBounds(content); body.setBackgroundColor(Color.RED);
                        try {
                            measure(host, w, h); RectF visible = new RectF(); card.visualBounds(visible);
                            require(visible.equals(new RectF(page.x(), page.y(), page.right(), page.bottom())), "page outline uses the page boundary " + definition.id() + "/" + rotation);
                            require(card.effectSafeArea().equals(page) && card.safeArea().equals(content), "edge effects and content use distinct boundaries");
                            Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); card.draw(new Canvas(image));
                            try {
                                require(Color.red(image.getPixel(w / 2, content.bottom() - 3)) > 240, "chrome reservation never fades the final content row");
                                require(Color.red(image.getPixel(w / 2, content.bottom() + 3)) == 0, "overflowing content cannot paint over reserved chrome");
                                card.contentSafeBounds(page); image.eraseColor(Color.TRANSPARENT); card.draw(new Canvas(image));
                                int band = Ui.dp(activity, 16);
                                if (definition == InterfaceCard.NOTIFICATIONS || definition == InterfaceCard.CONTROLS) require(Color.red(image.getPixel(w / 2, page.bottom() - 3)) > 240, "reading viewports own their fade; the page does not apply a second mask");
                                else require(Color.red(image.getPixel(w / 2, page.bottom() - band / 2)) > 80 && Color.red(image.getPixel(w / 2, page.bottom() - 3)) < 90, "page fade ends at the page boundary when content reaches it");
                                card.contentSafeBounds(content);
                            } finally { image.recycle(); }
                            tap(card, content.x() + 20, content.y() + 20); require(clicks[0] == 1, "content still receives clicks");
                            tap(card, 8, content.y() + 20); tap(card, content.x() + 20, content.bottom() + 20);
                            require(clicks[0] == 1, "outside page and chrome reservation cannot activate content");
                            DetailSheet details = sheet(activity); card.addView(details, new FrameLayout.LayoutParams(-1, -1)); measure(host, w, h);
                            modal(details, content, card); card.removeView(details);
                            DetailSheet shortcuts = sheet(activity); host.addView(shortcuts, new FrameLayout.LayoutParams(-1, -1)); measure(host, w, h);
                            modal(shortcuts, content, host); host.removeView(shortcuts);
                            // Active page movement must move a root-hosted popup's constraint, not resample the display.
                            card.setTranslationX(11); card.setTranslationY(7);
                            require(SafeAreaManager.viewContent(host).equals(new DockGeometry.Box(content.x() + 11, content.y() + 7, content.width(), content.height())), "input maps the actual page transform");
                        } finally { card.release(); host.removeAllViews(); }
                    }
                    for (int scale : new int[]{80, 100, 125}) {
                        InputSurface settings = new InputSurface(activity); settings.setPadding(page.x(), page.y(), w - page.right(), h - page.bottom());
                        SettingsUi.Viewport viewport = new SettingsUi.Viewport(activity, scale); DetailSheet sheet = sheet(activity); viewport.addView(sheet, new FrameLayout.LayoutParams(-1, -1)); settings.addView(viewport, new FrameLayout.LayoutParams(-1, -1)); measure(settings, w, h);
                        modal(sheet, page, settings);
                        require(sheet.getPaddingLeft() <= 1 && sheet.getPaddingTop() <= 1 && sheet.getPaddingBottom() <= 1, "already inset and scaled settings do not lose space again");
                        settings.setPadding(page.x(), page.y(), w - page.right(), h - page.bottom() + 140); measure(settings, w, h);
                        modal(sheet, new DockGeometry.Box(page.x(), page.y(), page.width(), page.height() - 140), settings);
                        settings.removeAllViews();
                    }
                }
            });
            menu(activity);
            return "PASS: page-boundaries; " + assertions + " assertions; four rotations, four page types, edge pixels, touch exclusion, modal placement, scaled settings and keyboard-sized viewport";
        } finally { test.runOnMainSync(activity::finish); }
    }
    private void menu(Activity activity) {
        InputSurface[] host = {null}; View[] anchor = {null}; int[] focus = {0};
        android.view.WindowManager windows = activity.getWindowManager();
        android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(-1, -1, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT);
        params.token = activity.getWindow().getDecorView().getWindowToken(); params.setFitInsetsTypes(0);
        test.runOnMainSync(() -> {
            host[0] = new InputSurface(activity); host[0].setPadding(31, 47, 55, 74);
            host[0].navigation(() -> { }, () -> { focus[0]++; params.flags &= ~android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(host[0], params); });
            host[0].released(() -> { params.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(host[0], params); });
            anchor[0] = Ui.button(activity, "菜单", () -> { }); host[0].addView(anchor[0], new FrameLayout.LayoutParams(160, 80)); windows.addView(host[0], params);
        });
        try {
            test.waitForIdleSync();
            for (int end = 0; end < 3; end++) {
                test.runOnMainSync(() -> { InputPopupMenu menu = new InputPopupMenu(activity, anchor[0]); menu.add("操作"); menu.show(); }); android.os.SystemClock.sleep(350); test.waitForIdleSync();
                int mode = end;
                test.runOnMainSync(() -> {
                    DetailSheet sheet = host[0].findViewWithTag("detail-sheet"); require(sheet != null && focus[0] > 0, "touch menu opens in the owned window and requests focus"); modal(sheet, SafeAreaManager.viewContent(host[0]), host[0]);
                    if (mode == 0) host[0].cancelCommand(); else if (mode == 1) host[0].page("next"); else host[0].removeView(anchor[0]);
                });
                android.os.SystemClock.sleep(350); test.waitForIdleSync();
                test.runOnMainSync(() -> require(host[0].findViewWithTag("detail-sheet") == null, "back, page change and source detachment release the menu"));
            }
        } finally { test.runOnMainSync(() -> windows.removeViewImmediate(host[0])); }
    }
    private DetailSheet sheet(Activity activity) { DetailSheet sheet = new DetailSheet(activity, "边界检查", () -> { }); sheet.content.addView(Ui.button(activity, "操作", () -> { })); return sheet; }
    private void modal(DetailSheet sheet, DockGeometry.Box safe, View host) {
        View card = sheet.findViewWithTag("detail-card"); DockGeometry.Box local = new DockGeometry.Box(0, 0, card.getWidth(), card.getHeight());
        DockGeometry.Box bounds = SafeAreaManager.mapViewBounds(card, local, host);
        require(bounds.width() > 0 && bounds.height() > 0 && bounds.equals(bounds.intersect(safe)), "modal card and shadow margin stay inside content: " + bounds + " / " + safe);
        require(Math.abs(bounds.x() + bounds.width() / 2f - safe.x() - safe.width() / 2f) <= 2 && Math.abs(bounds.y() + bounds.height() / 2f - safe.y() - safe.height() / 2f) <= 2, "modal centers within available content");
    }
    private static void measure(View view, int w, int h) { for (int i = 0; i < 4; i++) { view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); view.layout(0, 0, w, h); } }
    private static void tap(View view, float x, float y) { long now = android.os.SystemClock.uptimeMillis(); for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) { MotionEvent event = MotionEvent.obtain(now, now + action * 10, action, x, y, 0); view.dispatchTouchEvent(event); event.recycle(); } }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
