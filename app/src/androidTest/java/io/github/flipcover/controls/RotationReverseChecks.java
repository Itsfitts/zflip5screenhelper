package io.github.flipcover.controls;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

/** Local compatibility preference and both real rotation surfaces; disposable emulator only. */
final class RotationReverseChecks {
    static String run(UiSmokeInstrumentation test) throws Exception {
        Prefs prefs = new Prefs(test.getTargetContext());
        boolean saved = prefs.reverseLandscapeRotation();
        File directory = new File(test.getTargetContext().getFilesDir(), "rotation-reverse"); directory.mkdirs();
        try {
            for (int rotation = 0; rotation < 4; rotation++) {
                android.app.Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
                MainActivity launched = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                test.runOnMainSync(() -> launched.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER));
                require(test.getUiAutomation().setRotation(rotation), "emulator rotation accepted");
                android.os.SystemClock.sleep(700); test.waitForIdleSync();
                MainActivity activity = monitor.getLastActivity() instanceof MainActivity current ? current : launched; test.removeMonitor(monitor);
                int expectedRotation = rotation;
                View[] captures = new View[2];
                try {
                    test.runOnMainSync(() -> {
                        ControlDetails details = null;
                        try {
                            prefs.data.edit().remove("rotation_reverse_landscape").commit();
                            require(!new Prefs(activity).reverseLandscapeRotation(), "default is off");
                            CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs; owner.display = activity.getDisplay();
                            require(owner.display.getRotation() == expectedRotation, "real display matches requested test rotation: " + owner.display.getRotation() + " / " + expectedRotation);
                            Panels panels = new Panels(owner); View page = panels.build("rotation");
                            captures[0] = page;
                            View toggle = page.findViewWithTag("rotation-reverse-landscape");
                            require(page.findViewWithTag("detail-angle-" + expectedRotation).isSelected(), "normal angle selected");
                            toggle.performClick();
                            require(new Prefs(activity).reverseLandscapeRotation(), "preference survives new reader");
                            require("已开启".contentEquals(toggle.getStateDescription()), "toggle announces saved state");
                            for (int angle = 0; angle < 4; angle++) require(page.findViewWithTag("detail-angle-" + angle).isSelected() == (angle == expectedRotation), "angle selection is unchanged");
                            boolean landscape = expectedRotation == 1 || expectedRotation == 3;
                            require(prefs.reverseLandscapeLayout(expectedRotation) == landscape, "only landscape layout reverses");
                            String originalPosition = prefs.entryPosition(expectedRotation);
                            if (landscape) checkEntrySide(owner, expectedRotation);
                            SettingsIllustration preview = new SettingsIllustration(activity, SettingsIllustration.GESTURES, prefs);
                            require(preview.getContentDescription().toString().contains(expectedRotation * 90 + "°：" + SettingsIllustration.entryLabel(originalPosition)), "settings preview retains saved entry placement");
                            int width = expectedRotation % 2 == 0 ? 620 : 580;
                            render(page, width, 680, new File(directory, "page-" + expectedRotation + ".png"));
                            require(toggle.getWidth() > 0 && toggle.getBottom() <= ((View) toggle.getParent()).getHeight(), "page toggle fits row");

                            DetailSheet sheet = new DetailSheet(activity, "旋转方向", () -> { });
                            captures[1] = sheet;
                            details = new ControlDetails(owner, sheet); details.build("rotation");
                            require(page.findViewWithTag("rotation-controls") instanceof RotationControls && sheet.findViewWithTag("rotation-controls") instanceof RotationControls, "both hosts use the same content component");
                            require(page.findViewWithTag("detail-switch-rotation") != null && sheet.findViewWithTag("detail-switch-rotation") != null, "automatic switch available in both hosts");
                            require(sheet.findViewWithTag("detail-angle-" + expectedRotation).isSelected(), "detail retains real system angle");
                            View detailToggle = sheet.findViewWithTag("rotation-reverse-landscape");
                            require("已开启".contentEquals(detailToggle.getStateDescription()), "both surfaces share preference");
                            render(sheet, width, 680, new File(directory, "detail-" + expectedRotation + ".png"));
                            detailToggle.performClick();
                            require(!prefs.reverseLandscapeRotation() && sheet.findViewWithTag("detail-angle-" + expectedRotation).isSelected(), "switching off preserves real angle");
                            require(originalPosition.equals(prefs.entryPosition(expectedRotation)), "switching never rewrites saved entry layout");
                            require(owner.display.getRotation() == expectedRotation, "preference toggle does not rotate display");

                            JSONObject exported = activity.exportConfigurationData();
                            prefs.reverseLandscapeRotation(true);
                            require(exported.toString().equals(activity.exportConfigurationData().toString()), "local compatibility excluded from export");
                            activity.applyConfigurationData(exported);
                            require(prefs.reverseLandscapeRotation(), "import preserves enabled local preference");
                            prefs.reverseLandscapeRotation(false); activity.applyConfigurationData(exported);
                            require(!prefs.reverseLandscapeRotation(), "import preserves disabled local preference");
                        } catch (Exception error) { throw new AssertionError(error); }
                        finally { if (details != null) details.close(); }
                    });
                    for (int i = 0; i < captures.length; i++) {
                        View view = captures[i]; File file = new File(directory, (i == 0 ? "page-" : "detail-") + rotation + ".png");
                        test.runOnMainSync(() -> activity.setContentView(view)); android.os.SystemClock.sleep(250); test.waitForIdleSync();
                        test.runOnMainSync(() -> { try { render(view, view.getWidth(), view.getHeight(), file); } catch (Exception error) { throw new AssertionError(error); } });
                    }
                } finally { test.runOnMainSync(activity::finish); test.waitForIdleSync(); }
            }
            return "PASS: rotation-reverse; four display rotations, both surfaces, default/persistence, unchanged angle selection, saved entry sides preserved in production geometry, portrait unchanged, export/import isolation";
        } finally { prefs.reverseLandscapeRotation(saved); test.getUiAutomation().setRotation(-2); }
    }
    private static void checkEntrySide(CoverService owner, int rotation) throws Exception {
        Prefs prefs = owner.prefs; String original = prefs.entryPosition(rotation); boolean saved = prefs.reverseLandscapeRotation();
        java.util.List<DockGeometry.Box> cuts = java.util.List.of(rotation == 1 ? new DockGeometry.Box(654, 0, 66, 369) : new DockGeometry.Box(0, 379, 66, 369));
        java.lang.reflect.Field field = CoverService.class.getDeclaredField("panelEntryPlacement"); field.setAccessible(true);
        try {
            for (String position : new String[]{"top_left", "top_right"}) for (boolean reversed : new boolean[]{false, true}) {
                prefs.entryPosition(rotation, position); prefs.reverseLandscapeRotation(reversed);
                owner.placement = DockGeometry.resolve(720, 748, cuts, owner.screenContext.getResources().getDisplayMetrics().density, prefs.corner(rotation), prefs.widthRatio(), prefs.heightRatio(), true, rotation, prefs.reverseLandscapeLayout(rotation));
                owner.resolveContentGeometry(new android.graphics.Point(720, 748), rotation, cuts, owner.placement, android.graphics.Insets.NONE, 24);
                DockGeometry.Placement entry = (DockGeometry.Placement) field.get(owner);
                require(entry.edge() == DockGeometry.TOP, "entry stays horizontal at top");
                require((entry.visual().x() + entry.visual().width() / 2 < 360) == position.equals("top_left"), "reversal must preserve saved entry side: " + rotation + " / " + position + " / " + reversed);
            }
        } finally { prefs.entryPosition(rotation, original); prefs.reverseLandscapeRotation(saved); }
    }
    private static void render(View view, int width, int height, File file) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(file)) { view.draw(new Canvas(bitmap)); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { bitmap.recycle(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
