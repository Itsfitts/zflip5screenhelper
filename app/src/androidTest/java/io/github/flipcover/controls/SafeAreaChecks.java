package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Insets;
import android.view.View;
import java.util.List;

/** Actual Android layout/prefs checks, confined to the disposable emulator by the runner. */
final class SafeAreaChecks {
    private final Instrumentation test;
    private int assertions;
    SafeAreaChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    String run() {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "group_dock"));
        Prefs prefs = new Prefs(activity); String[] before = new String[4];
        for (int r = 0; r < 4; r++) before[r] = prefs.data.getString("safe_area_" + r, null);
        boolean oldStatus = prefs.statusEnabled();
        try {
            test.runOnMainSync(() -> {
                android.widget.LinearLayout options = activity.findViewById(android.R.id.content).findViewWithTag("category-options");
                require(options != null && "settings-link-safe_area".equals(options.getChildAt(0).getTag()), "safe area is first in dock/gesture root menu");
                options.getChildAt(0).performClick();
                require(activity.findViewById(android.R.id.content).findViewWithTag("safe-area-guides") != null, "dedicated guide switch exists");
                for (int r = 0; r < 4; r++) require(activity.findViewById(android.R.id.content).findViewWithTag("safe-area-direction-" + r) != null, "all four independent directions reachable");
                var directionRow = (android.widget.LinearLayout) activity.findViewById(android.R.id.content).findViewWithTag("safe-area-directions");
                require(directionRow != null && directionRow.getChildCount() == 4 && directionRow.getOrientation() == android.widget.LinearLayout.HORIZONTAL, "four directions occupy one compact row");
                android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration()); config.densityDpi = 340;
                android.content.Context context = activity.createConfigurationContext(config);
                prefs.data.edit().putBoolean("status_enabled", true).commit();
                checkDockBoundaries(context, prefs);
                for (int r = 0; r < 4; r++) {
                    int w = r % 2 == 0 ? 748 : 720, h = r % 2 == 0 ? 720 : 748;
                    var cut = switch (r) { case 0 -> new DockGeometry.Box(379, 654, 369, 66); case 1 -> new DockGeometry.Box(654, 0, 66, 369); case 2 -> new DockGeometry.Box(0, 0, 369, 66); default -> new DockGeometry.Box(0, 379, 66, 369); };
                    for (int reported : new int[]{0, 64, 96, 144}) for (String position : r == 0 ? new String[]{"bottom_right", "top_left"} : r == 2 ? new String[]{"top_left"} : new String[]{"top_left", "top_right"}) {
                        String key = "panel_entry_" + r, old = prefs.data.getString(key, null); prefs.entryPosition(r, position);
                        try {
                            var automatic = switch (r) { case 0 -> Insets.of(0, 0, 0, Math.max(66, reported)); case 1 -> Insets.of(0, 0, Math.max(66, reported), 0); case 2 -> Insets.of(0, Math.max(66, reported), 0, 0); default -> Insets.of(Math.max(66, reported), 0, 0, 0); };
                            var snapshot = new SafeAreaManager.Snapshot(1, "test", r, w, h, 2.125f, true, false, automatic, Insets.NONE, List.of(cut));
                            var anchor = DockGeometry.resolve(w, h, List.of(cut), 2.125f, (r + 3) % 4, .46f, .088f, true, r, false);
                            var dock = SafeAreaGeometry.outside(DockGeometry.edgeTouch(anchor, w, h), snapshot.contentFrame(), w, h, List.of(cut), snapshot.density());
                            var layout = SafeAreaManager.contents(context, prefs, snapshot, dock, dock, 0);
                            for (DockGeometry.Box frame : List.of(layout.dock().touch(), layout.entry().touch(), layout.dock().panel(), layout.card(), layout.controlStatus(), layout.hub(), layout.widget(), layout.tasks())) {
                                require(frame.width() > 0 && frame.height() > 0, "all consumers retain positive geometry at " + r + "/" + position);
                                require(frame.intersect(snapshot.rawFrame()).equals(frame), "all consumers stay on the display");
                                require(!SafeAreaGeometry.overlaps(frame, cut), "all consumers avoid real cutout");
                            }
                            if (r == 0 && position.equals("bottom_right")) {
                                require(layout.entry().touch().bottom() == 654, "manual boundary can reclaim both 96 and 144 reports");
                                require(layout.entry().touch().y() + DockGeometry.panelEntryChrome(layout.entry(), 2.125f).firstHandle().y() == 648, "white bar sits above the real cutout");
                                require(layout.dock().visual().equals(anchor.visual()), "zero inset retains correct notch-side shortcut position");
                            }
                            for (float scale : new float[]{1, 2, 8f / 7}) {
                                int windowWidth = Math.round(w * scale), windowHeight = Math.round(h * scale);
                                var maximum = new android.graphics.Rect(0, 0, windowWidth, windowHeight);
                                var mapped = SafeAreaManager.mapContent(snapshot, maximum, maximum, Insets.NONE);
                                DockGeometry.Box physical = DockGeometry.physicalContent(w, h, List.of(cut));
                                int left = Math.round(physical.x() * windowWidth / (float) w), top = Math.round(physical.y() * windowHeight / (float) h);
                                int right = Math.round(physical.right() * windowWidth / (float) w), bottom = Math.round(physical.bottom() * windowHeight / (float) h);
                                require(mapped.equals(new DockGeometry.Box(left, top, right - left, bottom - top)), "all rotations map physical cutout once across Activity compatibility scales");
                                var offset = new android.graphics.Rect(left, top, right, bottom);
                                require(SafeAreaManager.mapContent(snapshot, maximum, offset, Insets.NONE).equals(new DockGeometry.Box(0, 0, right - left, bottom - top)), "already inset hosts are not inset twice");
                                var keyboard = SafeAreaManager.mapContent(snapshot, maximum, maximum, Insets.of(0, 0, 0, 300));
                                require(keyboard.bottom() <= windowHeight - 300, "IME limits the local viewport without saving calibration");
                                var body = layout.dock().panel().intersect(snapshot.contentFrame());
                                var activityBody = SafeAreaManager.mapContent(snapshot, body, maximum, maximum, Insets.NONE);
                                var expectedBody = SafeAreaGeometry.localFrame(body, windowWidth / (float) w, windowHeight / (float) h, 0, 0, windowWidth, windowHeight);
                                require(activityBody.equals(expectedBody), "Activity consumes the same chrome reservation as runtime content in every rotation and scale");
                                var bodyWindow = new android.graphics.Rect(activityBody.x(), activityBody.y(), activityBody.right(), activityBody.bottom());
                                require(SafeAreaManager.mapContent(snapshot, body, maximum, bodyWindow, Insets.NONE).equals(new DockGeometry.Box(0, 0, activityBody.width(), activityBody.height())), "already inset Activity chrome is not deducted again after reload");
                            }
                            SafeAreaGuideView guide = new SafeAreaGuideView(context); guide.geometry(snapshot, dock, -1); guide.layout(0, 0, w, h);
                            android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888); guide.draw(new android.graphics.Canvas(image));
                            require(!guide.isClickable() && !guide.isFocusable(), "guide is passive"); image.recycle();
                        } finally { if (old == null) prefs.data.edit().remove(key).commit(); else prefs.data.edit().putString(key, old).commit(); }
                    }
                    var profile = new SafeAreaGeometry.Profile(true, w, h, "test", new SafeAreaGeometry.Edges(3, 5, 7, 9));
                    require(prefs.saveSafeArea(r, profile), "manual save succeeds"); require(prefs.safeArea(r).equals(profile), "manual values roundtrip");
                    require(prefs.safeAreaMode(r, false) && !prefs.safeArea(r).manual(), "auto preserves manual profile");
                    require(prefs.safeAreaMode(r, true) && prefs.safeArea(r).equals(profile), "manual restores previous values");
                    var reference = new SafeAreaGeometry.Edges(12, 20, 18, 96);
                    var signed = new SafeAreaGeometry.Profile(true, w, h, "test", reference.plus(new SafeAreaGeometry.Edges(-16, -20, -18, -30)), reference);
                    require(prefs.saveSafeArea(r, signed) && prefs.safeArea(r).equals(signed), "negative adjustments and frozen reference persist");
                    require(prefs.safeAreaMode(r, false) && prefs.safeAreaMode(r, true) && prefs.safeArea(r).equals(signed), "mode roundtrip preserves signed values and reference");
                }
                require(prefs.resetSafeArea(1) && prefs.safeArea(1) == null && prefs.safeArea(0) != null, "single reset leaves other directions intact");
                // A left Dock must not lift a non-overlapping right entry when the whole safe edge moves.
                String oldEntry = prefs.data.getString("panel_entry_0", null); prefs.entryPosition(0, "bottom_right");
                try {
                    var cut = new DockGeometry.Box(379, 654, 369, 66); var cuts = List.of(cut);
                    for (int bottom : new int[]{144, 96, 66, 30, 0}) {
                        Insets e = Insets.of(0, 0, 0, bottom);
                        var snapshot = new SafeAreaManager.Snapshot(1, "test", 0, 748, 720, 2.125f, true, false, Insets.of(0, 0, 0, 144), e, cuts);
                        var anchor = DockGeometry.resolve(748, 720, cuts, 2.125f, 3, .46f, .088f, true);
                        var dock = SafeAreaGeometry.outside(DockGeometry.edgeTouch(anchor, 748, 720), snapshot.contentFrame(), 748, 720, cuts, snapshot.density());
                        var layout = SafeAreaManager.contents(context, prefs, snapshot, dock, dock, bottom);
                        require(layout.entry().touch().bottom() == Math.min(654, 720 - bottom), "reducing manual bottom moves right entry downward without inheriting left Dock height: " + bottom);
                        require(!SafeAreaGeometry.overlaps(layout.entry().touch(), dock.touch()), "entry and Dock remain separate");
                    }
                } finally { if (oldEntry == null) prefs.data.edit().remove("panel_entry_0").commit(); else prefs.data.edit().putString("panel_entry_0", oldEntry).commit(); }
                require(prefs.resetSafeArea(-1), "reset all commits atomically"); for (int r = 0; r < 4; r++) require(prefs.safeArea(r) == null, "reset defaults to automatic");
                checkEditor(activity, prefs);
                checkSlider(activity);
            });
            return "PASS: safe-area; " + assertions + " assertions; four rotations, every legal entry, 0/64/96/144 reports, shared consumers, manual/auto/reset, guide and root menu; physical Samsung routing separate";
        } finally {
            test.runOnMainSync(() -> { var restore = prefs.data.edit().putBoolean("status_enabled", oldStatus); for (int r = 0; r < 4; r++) { if (before[r] == null) restore.remove("safe_area_" + r); else restore.putString("safe_area_" + r, before[r]); } restore.commit(); activity.finish(); });
        }
    }
    private void checkDockBoundaries(android.content.Context context, Prefs prefs) {
        boolean oldAvoid = prefs.avoidNavigation(), oldAutomatic = prefs.data.getBoolean("auto_placement", true);
        String[] oldEntries = new String[4]; for (int r = 0; r < 4; r++) oldEntries[r] = prefs.data.getString("panel_entry_" + r, null);
        try {
            for (int rotation = 0; rotation < 4; rotation++) for (boolean manual : new boolean[]{false, true}) for (boolean automatic : new boolean[]{false, true}) for (boolean avoid : new boolean[]{false, true}) {
                prefs.data.edit().putBoolean("auto_placement", automatic).putBoolean("avoid_navigation", avoid).commit();
                int w = rotation % 2 == 0 ? 748 : 720, h = rotation % 2 == 0 ? 720 : 748;
                var cut = switch (rotation) { case 0 -> new DockGeometry.Box(379, 654, 369, 66); case 1 -> new DockGeometry.Box(654, 0, 66, 369); case 2 -> new DockGeometry.Box(0, 0, 369, 66); default -> new DockGeometry.Box(0, 379, 66, 369); };
                for (int adjustment : new int[]{-22, 0, 20}) {
                    Insets baseline = switch (rotation) { case 0 -> Insets.of(0, 0, 0, 96); case 1 -> Insets.of(0, 0, 66, 64); case 2 -> Insets.of(0, 66, 0, 64); default -> Insets.of(66, 0, 0, 64); };
                    Insets effective = Insets.of(baseline.left, baseline.top, baseline.right, Math.max(0, baseline.bottom + adjustment));
                    var area = new SafeAreaManager.Snapshot(1, "test", rotation, w, h, 2.125f, manual, false, baseline, effective, List.of(cut));
                    var chrome = SafeAreaManager.chrome(context, prefs, area, Insets.of(0, 0, 0, 64), Insets.NONE, Insets.NONE);
                    var layout = SafeAreaManager.contents(context, prefs, area, chrome.dock(), chrome.anchor(), chrome.homeInset());
                    boolean shown = layout.dock().touch().width() > 0 && layout.dock().touch().height() > 0;
                    if (manual || !avoid) require(shown, "camera-free exterior lane remains usable");
                    else if (shown) {
                        int minimum = Ui.dp(context, 16), gap = Ui.dp(context, prefs.navigationGap());
                        var allowed = new SafeAreaGeometry.Edges(minimum + gap, minimum + gap, minimum + gap, Math.max(64, minimum) + gap).frame(w, h);
                        require(layout.dock().touch().intersect(allowed).equals(layout.dock().touch()), "outside placement retains navigation avoidance and its extra gap");
                    }
                    require(!SafeAreaGeometry.overlaps(layout.dock().touch(), area.contentFrame()) && !SafeAreaGeometry.overlaps(layout.dock().visual(), area.contentFrame()), "all Dock pixels and touch targets stay outside the fixed page: " + rotation + "/" + manual + "/" + automatic + "/" + avoid);
                    require(!SafeAreaGeometry.overlaps(layout.dock().touch(), cut), "exterior touch avoids actual camera");
                    for (var content : List.of(layout.dock().panel(), layout.hub(), layout.tasks(), layout.widget())) require(content.x() == area.contentFrame().x() && content.width() == area.contentFrame().width(), "Dock never deducts another column from any content consumer");
                    require(layout.card().equals(area.contentFrame()), "page boundary never depends on Dock placement");
                    var guide = new SafeAreaGuideView(context); guide.geometry(area, layout.dock(), -1); guide.layout(0, 0, w, h);
                    var image = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                    try { guide.draw(new android.graphics.Canvas(image)); var touch = layout.dock().touch(); if (shown) require(android.graphics.Color.alpha(image.getPixel(touch.x() + touch.width() / 2, touch.y() + touch.height() / 2)) == 0, "guide does not include the exterior Dock lane"); } finally { image.recycle(); }
                }
            }
            prefs.data.edit().putBoolean("avoid_navigation", false).putBoolean("auto_placement", true).commit();
            for (int rotation = 0; rotation < 4; rotation++) for (String position : new String[]{"top_left", "top_right", "bottom_right"}) {
                if (!Prefs.validEntryPosition(rotation, position)) continue;
                prefs.entryPosition(rotation, position);
                var area = new SafeAreaManager.Snapshot(1, "test", rotation, 748, 720, 2.125f, false, false, Insets.NONE, Insets.NONE, List.of());
                var chrome = SafeAreaManager.chrome(context, prefs, area, Insets.NONE, Insets.NONE, Insets.NONE);
                var layout = SafeAreaManager.contents(context, prefs, area, chrome.dock(), chrome.anchor(), chrome.homeInset());
                require(layout.dock().touch().width() == 0 && layout.dock().visual().width() == 0, "full-page display hides Dock without creating an interior lane");
                require(layout.card().equals(area.rawFrame()) && layout.dock().panel().width() == 748 && layout.dock().panel().height() > 580, "hidden Dock does not collapse the page or its content");
                require(layout.entry().visual().width() > 200, "hidden Dock leaves a usable white-bar pair");
                require(position.equals("bottom_right") ? layout.entry().visual().bottom() == 720 : layout.entry().touch().y() == 0, "hidden Dock does not displace the white-bar edge origin");
                for (var content : List.of(layout.hub(), layout.tasks(), layout.widget())) require(content.width() == 748 && content.height() > 580, "all content consumers retain full space with hidden Dock");
            }
        } finally {
            var restore = prefs.data.edit().putBoolean("auto_placement", oldAutomatic).putBoolean("avoid_navigation", oldAvoid);
            for (int r = 0; r < 4; r++) { if (oldEntries[r] == null) restore.remove("panel_entry_" + r); else restore.putString("panel_entry_" + r, oldEntries[r]); } restore.commit();
        }
    }
    private void checkSlider(Activity activity) {
        int[] saved = {0}, commits = {0};
        SettingsUi.Slider slider = new SettingsUi.Slider(activity, -720, 560, 0, value -> { }, value -> { saved[0] = value; commits[0]++; });
        android.widget.FrameLayout parent = new android.widget.FrameLayout(activity); parent.addView(slider);
        slider.committedValue(1); slider.setProgress(0);
        try {
            var commit = SettingsUi.Slider.class.getDeclaredMethod("commit"); commit.setAccessible(true); commit.invoke(slider);
            require(saved[0] == 0 && commits[0] == 1, "dragging back after sibling input commits the original value again");
            slider.committedValue(-30); slider.setProgress(-50);
            long now = android.os.SystemClock.uptimeMillis(); android.view.MotionEvent cancel = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0, 0, 0);
            slider.onTouchEvent(cancel); cancel.recycle();
            require(slider.getProgress() == -30 && commits[0] == 1, "cancel restores the latest external committed value");
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void checkEditor(Activity activity, Prefs prefs) {
        CoverService previous = CoverService.instance; boolean hadDisplay = prefs.data.contains("display"); int previousDisplay = prefs.displayId();
        android.media.ImageReader surface = android.media.ImageReader.newInstance(748, 720, android.graphics.PixelFormat.RGBA_8888, 2);
        android.hardware.display.VirtualDisplay target = activity.getSystemService(android.hardware.display.DisplayManager.class).createVirtualDisplay("safe-area-ui-test", 748, 720, 340, surface.getSurface(), 0);
        SafeAreaSettings[] editor = {null};
        try {
            CoverService owner = new CoverService(); owner.prefs = prefs; owner.display = target.getDisplay(); owner.screenContext = activity.createDisplayContext(owner.display);
            owner.safeAreaCalibration = new SafeAreaCalibration(owner); CoverService.instance = owner;
            prefs.data.edit().putInt("display", owner.display.getDisplayId()).commit();
            var area = new SafeAreaManager.Snapshot(owner.display.getDisplayId(), SafeAreaManager.identity(owner.display), 0, 748, 720, 2.125f, false, false, Insets.of(0, 0, 0, 66), Insets.of(0, 0, 0, 66), List.of(new DockGeometry.Box(379, 654, 369, 66)));
            set(owner, "safeArea", area); String[] prompt = {""};
            SafeAreaSettings.Host host = new SafeAreaSettings.Host() {
                public void show(String title, String message, String action, Runnable confirmed) { prompt[0] = title + message; }
                public void mode(boolean manual, java.util.function.Consumer<Boolean> selected) { }
                public void number(String title, int value, java.util.function.IntPredicate accept) { }
            };
            editor[0] = new SafeAreaSettings(activity, prefs, 1, host);
            editor[0].findViewWithTag("safe-area-begin").performClick();
            require(prompt[0].contains("90°") && prompt[0].contains("恢复"), "different direction asks consent and explains restoration");
            require(!owner.safeAreaCalibration.active(), "rotation is not requested before confirmation");
            editor[0].findViewWithTag("safe-area-direction-0").performClick(); editor[0].findViewWithTag("safe-area-begin").performClick();
            require(prompt[0].contains("临时锁定"), "same direction explains temporary lock");
            set(owner.safeAreaCalibration, "active", true); set(owner.safeAreaCalibration, "editing", true);
            set(owner.safeAreaCalibration, "draft", new SafeAreaGeometry.Profile(true, 748, 720, area.identity(), new SafeAreaGeometry.Edges(0, 0, 0, 66), new SafeAreaGeometry.Edges(0, 0, 0, 66)));
            editor[0] = new SafeAreaSettings(activity, prefs, 0, host);
            for (int edge = 0; edge < 4; edge++) { android.widget.SeekBar slider = editor[0].findViewWithTag("safe-area-edge-" + edge); require(slider != null && slider.getMin() < 0 && slider.getProgress() == 0, "four signed edge controls start at the automatic baseline"); }
            require(editor[0].findViewWithTag("safe-area-save") != null && editor[0].findViewWithTag("safe-area-cancel") != null, "save and cancel remain explicit");
            editor[0].measure(View.MeasureSpec.makeMeasureSpec(660, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); editor[0].layout(0, 0, 660, editor[0].getMeasuredHeight());
            android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(660, editor[0].getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888); android.graphics.Canvas canvas = new android.graphics.Canvas(image); canvas.drawColor(SettingsUi.BACKGROUND); editor[0].draw(canvas);
            java.io.File output = new java.io.File(activity.getFilesDir(), "safe-area-editor.png"); try (var stream = new java.io.FileOutputStream(output)) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream); } image.recycle();
            require(!prefs.safeAreaGuides(), "temporary calibration does not rewrite guide switch");
        } catch (Exception error) { throw new AssertionError(error); }
        finally { if (editor[0] != null) editor[0].detach(); CoverService.instance = previous; if (hadDisplay) prefs.data.edit().putInt("display", previousDisplay).commit(); else prefs.data.edit().remove("display").commit(); target.release(); surface.close(); }
    }
    private static void set(Object owner, String name, Object value) throws Exception { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
}
