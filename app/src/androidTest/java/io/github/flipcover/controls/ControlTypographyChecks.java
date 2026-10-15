package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.List;

/** Font preferences must not change control geometry or leak into sibling surfaces. */
final class ControlTypographyChecks {
    static String run(Instrumentation test) {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        int[] cases = {0};
        try {
            test.runOnMainSync(() -> {
                mismatchedFontMetrics(activity);
                for (int dpi : new int[]{240, 340, 480}) for (int width : new int[]{720, 748}) for (int columns : new int[]{3, 4, 5}) for (int labelSize : new int[]{0, 1, 2}) for (int spacing : new int[]{0, 1, 2}) {
                    String baseline = null;
                    for (float scale : new float[]{1f, .85f, 1.3f, 1.5f, 2f}) for (int weight : new int[]{0, 300}) {
                        Configuration configuration = new Configuration(activity.getResources().getConfiguration()); configuration.densityDpi = dpi; configuration.fontScale = scale;
                        if (Build.VERSION.SDK_INT >= 31) configuration.fontWeightAdjustment = weight;
                        Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme);
                        Prefs prefs = new Prefs(context); prefs.data.edit().clear().putInt("panel_columns", columns).putInt("panel_label_size", labelSize).putInt("panel_density", spacing).putBoolean("panel_media", true).putBoolean("panel_brightness", true).putBoolean("panel_volume", true).commit();
                        prefs.saveActions("panel", List.of("wifi", "bluetooth", "data", "location", "torch", "dnd", "airplane", "rotation", "screenshot", "lock", "media", "apps", "system_controls", "external_devices"));
                        CoverService owner = new CoverService(); owner.screenContext = context; owner.prefs = prefs; owner.display = activity.getDisplay();
                        int height = width == 720 ? 748 : 720;
                        owner.placement = new DockGeometry.Placement(new DockGeometry.Box(0, 0, 0, 0), new DockGeometry.Box(0, 0, 0, 0), new DockGeometry.Box(0, 38, width, height - 104), DockGeometry.TOP, true);
                        View panel = owner.buildPanelContent("controls", new DockGeometry.Box(0, 0, width, height), 38);
                        String sample = "dpi=" + dpi + ", width=" + width + ", columns=" + columns + ", label=" + labelSize + ", spacing=" + spacing + ", scale=" + scale + ", weight=" + weight;
                        StringBuilder state = new StringBuilder(); capture(panel, width, height, state);
                        checkIcons(panel, sample);
                        View preview = new Panels(owner, true).build("controls"); capture(preview, width, height, state);
                        ControlEditorView editor = new ControlEditorView(context, prefs, null, id -> { }, () -> { }); capture(editor, width, height, state);
                        editor.findViewWithTag("control-candidates-search").performClick(); capture(editor, width, height, state);
                        FrameLayout host = new FrameLayout(context); host.addView(panel);
                        try {
                            for (String detail : new String[]{"brightness", "media", "external_devices", "rotation"}) {
                                owner.showDetails(detail, null); capture(host.getChildAt(1), width, height, state); owner.dismissDetails();
                            }
                        } finally { owner.dismissDetails(); host.removeAllViews(); }
                        capture(owner.buildPanelContent("rotation", new DockGeometry.Box(0, 0, width, height), 38), width, height, state);
                        String current = state.toString();
                        if (baseline == null) baseline = current; else require(baseline.equals(current), "font preferences change control measurements: " + sample);
                        require(context.getResources().getConfiguration().fontScale == scale, "source font scale changed");
                        require(context.getResources().getDisplayMetrics().densityDpi == dpi && panel.getResources().getDisplayMetrics().densityDpi == dpi, "display density changed: " + sample + ", source=" + context.getResources().getDisplayMetrics().densityDpi + ", panel=" + panel.getResources().getDisplayMetrics().densityDpi);
                        if (Build.VERSION.SDK_INT >= 31) require(context.getResources().getConfiguration().fontWeightAdjustment == weight, "source font weight changed");
                        View notifications = owner.buildPanelContent("notifications", new DockGeometry.Box(0, 0, width, height), 38);
                        require(notifications.getResources().getConfiguration().fontScale == scale, "notification surface opted into fixed fonts");
                        require(new TextView(context).getResources().getConfiguration().fontScale == scale, "ordinary text opted into fixed fonts");
                        cases[0]++;
                    }
                }
            });
            return "PASS: " + cases[0] + " font/weight/layout cases; 240/340/480 dpi; independent label/spacing presets; bounded glyphs; mismatched SP metrics; home, preview, editor/search, detail/media/input; source and notification isolation; 3/4/5 columns, both orientations";
        } finally { test.runOnMainSync(activity::finish); }
    }
    private static void mismatchedFontMetrics(Context source) {
        Context fixed = PanelUi.controlContext(source);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); metrics.setTo(fixed.getResources().getDisplayMetrics());
        android.content.res.Resources resources = new android.content.res.Resources(fixed.getAssets(), metrics, new Configuration(fixed.getResources().getConfiguration()));
        // Simulate an OEM font conversion that disagrees with Configuration.fontScale.
        float before = fixed.getResources().getDisplayMetrics().scaledDensity;
        resources.getDisplayMetrics().scaledDensity = resources.getDisplayMetrics().density * 2;
        Context mismatched = new android.content.ContextWrapper(fixed) { @Override public android.content.res.Resources getResources() { return resources; } };
        require(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12, resources.getDisplayMetrics()) > 12 * metrics.density, "mismatched SP fixture is ineffective");
        TextView text = Ui.text(mismatched, "移动数据", 12, Ui.TEXT);
        require(Math.abs(text.getTextSize() - 12 * metrics.density) < .01f, "control text still uses scaled font metrics");
        Ui.textSize(text, 17); require(Math.abs(text.getTextSize() - 17 * metrics.density) < .01f, "later resize reintroduces SP");
        TextView input = PanelUi.search(mismatched, "搜索", ""); require(Math.abs(input.getTextSize() - PanelUi.BODY * metrics.density) < .01f, "search still uses SP");
        require(fixed.getResources().getDisplayMetrics().scaledDensity == before, "fixture changes source resources");
    }
    private static void checkIcons(View panel, String sample) {
        ViewGroup grid = panel.findViewWithTag("control-tile-grid");
        for (int i = 0; i < grid.getChildCount(); i++) {
            ViewGroup tile = (ViewGroup) grid.getChildAt(i), face = (ViewGroup) tile.getChildAt(0); View glyph = face.getChildAt(0), label = tile.getChildAt(1);
            require(glyph.getWidth() > 0 && glyph.getWidth() <= Ui.dp(glyph.getContext(), 23) && glyph.getHeight() == glyph.getWidth(), "compact glyph stays square and bounded: " + sample);
            if (face.getWidth() >= Ui.dp(face.getContext(), 39) && face.getHeight() >= Ui.dp(face.getContext(), 39)) require(glyph.getWidth() == Ui.dp(glyph.getContext(), 23), "available space retains the full glyph: " + sample);
            require(face.getTop() >= 0 && face.getBottom() <= label.getTop() && label.getBottom() <= tile.getHeight(), "tile content overlaps or clips: " + sample);
        }
    }
    private static void capture(View view, int width, int height, StringBuilder state) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height); append(view, state);
    }
    private static void append(View view, StringBuilder state) {
        require(view.getResources().getConfiguration().fontScale == 1f, "unscoped control view: " + view.getClass().getSimpleName());
        if (Build.VERSION.SDK_INT >= 31) require(view.getResources().getConfiguration().fontWeightAdjustment == 0, "unscoped bold text: " + view.getClass().getSimpleName());
        state.append(view.getClass().getSimpleName()).append(':').append(view.getVisibility()).append(':').append(view.getLeft()).append(',').append(view.getTop()).append(',').append(view.getWidth()).append(',').append(view.getHeight());
        if (view instanceof TextView text) state.append(':').append(text.getText()).append(':').append(text.getTextSize()).append(':').append(text.getLineHeight()).append(':').append(text.getMaxLines()).append(':').append(text.getTypeface().getWeight());
        state.append(';');
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) append(group.getChildAt(i), state);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
