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
                android.content.res.Configuration config = new android.content.res.Configuration(activity.getResources().getConfiguration()); config.densityDpi = 340;
                android.content.Context context = activity.createConfigurationContext(config);
                prefs.data.edit().putBoolean("status_enabled", true).commit();
                for (int r = 0; r < 4; r++) {
                    int w = r % 2 == 0 ? 748 : 720, h = r % 2 == 0 ? 720 : 748;
                    var cut = switch (r) { case 0 -> new DockGeometry.Box(379, 654, 369, 66); case 1 -> new DockGeometry.Box(654, 0, 66, 369); case 2 -> new DockGeometry.Box(0, 0, 369, 66); default -> new DockGeometry.Box(0, 379, 66, 369); };
                    for (int reported : new int[]{0, 64, 96, 144}) for (String position : r == 0 ? new String[]{"bottom_right", "top_left"} : r == 2 ? new String[]{"top_left"} : new String[]{"top_left", "top_right"}) {
                        String key = "panel_entry_" + r, old = prefs.data.getString(key, null); prefs.entryPosition(r, position);
                        try {
                            var automatic = switch (r) { case 0 -> Insets.of(0, 0, 0, Math.max(66, reported)); case 1 -> Insets.of(0, 0, Math.max(66, reported), 0); case 2 -> Insets.of(0, Math.max(66, reported), 0, 0); default -> Insets.of(Math.max(66, reported), 0, 0, 0); };
                            var snapshot = new CoverSafeArea.Snapshot(1, "test", r, w, h, 2.125f, true, false, automatic, Insets.NONE, List.of(cut));
                            var anchor = DockGeometry.resolve(w, h, List.of(cut), 2.125f, (r + 3) % 4, .46f, .088f, true, r, false);
                            var dock = SafeAreaGeometry.constrain(DockGeometry.edgeTouch(anchor, w, h), snapshot.frame(), List.of(cut));
                            var layout = CoverSafeArea.contents(context, prefs, snapshot, dock, dock, 0);
                            for (DockGeometry.Box frame : List.of(layout.dock().touch(), layout.entry().touch(), layout.dock().panel(), layout.card(), layout.controlStatus(), layout.hub(), layout.widget(), layout.tasks())) {
                                require(frame.width() > 0 && frame.height() > 0, "all consumers retain positive geometry at " + r + "/" + position);
                                require(frame.intersect(snapshot.frame()).equals(frame), "all consumers obey manual bounds");
                                require(!SafeAreaGeometry.overlaps(frame, cut), "all consumers avoid real cutout");
                            }
                            if (r == 0 && position.equals("bottom_right")) {
                                require(layout.entry().touch().bottom() == 654, "manual boundary can reclaim both 96 and 144 reports");
                                require(layout.entry().touch().y() + DockGeometry.panelEntryChrome(layout.entry(), 2.125f).firstHandle().y() == 648, "white bar sits above the real cutout");
                                require(layout.dock().visual().equals(anchor.visual()), "zero inset retains correct notch-side shortcut position");
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
                }
                require(prefs.resetSafeArea(1) && prefs.safeArea(1) == null && prefs.safeArea(0) != null, "single reset leaves other directions intact");
                // A left Dock must not lift a non-overlapping right entry when the whole safe edge moves.
                String oldEntry = prefs.data.getString("panel_entry_0", null); prefs.entryPosition(0, "bottom_right");
                try {
                    var cut = new DockGeometry.Box(379, 654, 369, 66); var cuts = List.of(cut);
                    for (int bottom : new int[]{144, 96, 66, 30, 0}) {
                        Insets e = Insets.of(0, 0, 0, bottom);
                        var snapshot = new CoverSafeArea.Snapshot(1, "test", 0, 748, 720, 2.125f, true, false, Insets.of(0, 0, 0, 144), e, cuts);
                        var anchor = DockGeometry.resolve(748, 720, cuts, 2.125f, 3, .46f, .088f, true);
                        var dock = SafeAreaGeometry.constrain(DockGeometry.edgeTouch(anchor, 748, 720), snapshot.frame(), cuts);
                        var layout = CoverSafeArea.contents(context, prefs, snapshot, dock, dock, bottom);
                        require(layout.entry().touch().bottom() == Math.min(654, 720 - bottom), "reducing manual bottom moves right entry downward without inheriting left Dock height: " + bottom);
                        require(!SafeAreaGeometry.overlaps(layout.entry().touch(), dock.touch()), "entry and Dock remain separate");
                    }
                } finally { if (oldEntry == null) prefs.data.edit().remove("panel_entry_0").commit(); else prefs.data.edit().putString("panel_entry_0", oldEntry).commit(); }
                require(prefs.resetSafeArea(-1), "reset all commits atomically"); for (int r = 0; r < 4; r++) require(prefs.safeArea(r) == null, "reset defaults to automatic");
                checkEditor(activity, prefs);
            });
            return "PASS: safe-area; " + assertions + " assertions; four rotations, every legal entry, 0/64/96/144 reports, shared consumers, manual/auto/reset, guide and root menu; physical Samsung routing separate";
        } finally {
            test.runOnMainSync(() -> { var restore = prefs.data.edit().putBoolean("status_enabled", oldStatus); for (int r = 0; r < 4; r++) { if (before[r] == null) restore.remove("safe_area_" + r); else restore.putString("safe_area_" + r, before[r]); } restore.commit(); activity.finish(); });
        }
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
            var area = new CoverSafeArea.Snapshot(owner.display.getDisplayId(), CoverSafeArea.identity(owner.display), 0, 748, 720, 2.125f, false, false, Insets.of(0, 0, 0, 66), Insets.of(0, 0, 0, 66), List.of(new DockGeometry.Box(379, 654, 369, 66)));
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
            set(owner.safeAreaCalibration, "draft", new SafeAreaGeometry.Profile(true, 748, 720, area.identity(), new SafeAreaGeometry.Edges(0, 0, 0, 66)));
            editor[0] = new SafeAreaSettings(activity, prefs, 0, host);
            for (int edge = 0; edge < 4; edge++) require(editor[0].findViewWithTag("safe-area-edge-" + edge) != null, "four edge controls present");
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
