package io.github.flipcover.controls;

import android.app.Activity;
import android.graphics.Canvas;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One UI editor. All editable values belong to the service's cancellable calibration draft. */
final class SafeAreaSettings extends LinearLayout {
    interface Host extends AppDisplaySettings.Confirmation {
        void mode(boolean manual, java.util.function.Consumer<Boolean> selected);
        void number(String title, int value, java.util.function.IntPredicate accept);
    }
    private final Activity activity;
    private final Prefs prefs;
    private final Host confirmation;
    private SafeAreaCalibration session;
    private CoverService observedService;
    private int rotation;
    private boolean resumed;
    private TextView feedback;
    private Preview preview;
    private final Runnable changed = this::render;
    private final Runnable geometryChanged = () -> { if (!resumed) return; if (session != null && session.editing()) { if (preview != null && current() != null) preview.geometry(current()); } else render(); };
    SafeAreaSettings(Activity activity, Prefs prefs, int rotation, Host confirmation) {
        super(activity); this.activity = activity; this.prefs = prefs; this.rotation = rotation; this.confirmation = confirmation; setOrientation(VERTICAL); setTag("safe-area-settings");
        CoverService service = CoverService.instance; session = service == null ? null : service.safeAreaCalibration;
        if (session != null && session.active()) this.rotation = session.rotation();
        render();
    }
    int rotation() { return rotation; }
    boolean hasDraft() { return session != null && session.active(); }
    void resume() { if (!resumed) { resumed = true; observedService = CoverService.instance; if (observedService != null) { session = observedService.safeAreaCalibration; observedService.observeSafeArea(geometryChanged); } if (session != null) session.observe(changed); render(); } }
    void detach() { resumed = false; if (session != null) session.unobserve(changed); if (observedService != null) observedService.unobserveSafeArea(geometryChanged); }
    void refreshService() { if (resumed && observedService != CoverService.instance) { detach(); session = null; resume(); } }
    void cancel() { if (session != null) session.finish(false); }
    private String get(int id) { return activity.getString(id); }
    private LinearLayout group() { LinearLayout group = SettingsUi.group(activity); SettingsUi.add(this, group); return group; }
    private TextView label(LinearLayout parent, String text, int color) { TextView view = SettingsUi.text(activity, text, 14, color); int gap = Ui.dp(activity, 14); view.setPadding(gap, Ui.dp(activity, 10), gap, Ui.dp(activity, 10)); parent.addView(view); return view; }
    private Button button(LinearLayout parent, int text, String tag, Runnable click) { Button view = SettingsUi.button(activity, get(text), click); view.setTag(tag); parent.addView(view); return view; }
    private CoverSafeArea.Snapshot current() { CoverService service = CoverService.instance; return service == null ? null : service.safeAreaSnapshot(); }
    private void render() {
        removeAllViews(); boolean editing = session != null && session.editing(), busy = session != null && session.active();
        label(this, get(R.string.safe_area_description), SettingsUi.MUTED);
        LinearLayout directions = group(); label(directions, get(R.string.safe_area_direction), SettingsUi.TEXT);
        // Two rows remain readable on the small cover and at large system font sizes.
        for (int row = 0; row < 2; row++) {
            LinearLayout pair = SettingsUi.row(activity); directions.addView(pair);
            for (int column = 0; column < 2; column++) {
                int angle = row * 2 + column; Button tab = SettingsUi.button(activity, angle * 90 + "°" + (angle == rotation ? " ✓" : ""), () -> { rotation = angle; render(); });
                tab.setTag("safe-area-direction-" + angle); tab.setSelected(angle == rotation); tab.setEnabled(!busy); tab.setStateDescription(angle == rotation ? "已选择" : "未选择"); SettingsUi.weighted(pair, tab);
            }
        }
        CoverSafeArea.Snapshot area = current(); SafeAreaGeometry.Profile profile = prefs.safeArea(rotation);
        if (area != null && area.rotation() == rotation) {
            preview = new Preview(activity); preview.geometry(area); group().addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 120)));
            label(this, get(R.string.safe_area_preview), SettingsUi.MUTED);
            label(this, activity.getString(R.string.safe_area_dimensions, rotation * 90, area.width(), area.height()), SettingsUi.ACCENT);
            if (area.mismatch()) label(this, get(R.string.safe_area_mismatch), SettingsUi.MUTED);
        } else { preview = null; label(this, area == null ? get(R.string.safe_area_unavailable) : activity.getString(R.string.safe_area_other_direction, area.rotation() * 90), SettingsUi.MUTED); }
        if (editing) { renderEditor(); return; }
        LinearLayout mode = group();
        SettingsUi.ValueRow modeRow = SettingsUi.valueRow(activity, rotation * 90 + "° · 模式", profile != null && profile.manual() ? get(R.string.safe_area_manual) : get(R.string.safe_area_auto), () -> chooseMode());
        modeRow.setTag("safe-area-mode"); modeRow.setEnabled(!busy); mode.addView(modeRow);
        label(mode, get(R.string.safe_area_auto_help), SettingsUi.MUTED); label(mode, get(R.string.safe_area_manual_help), SettingsUi.MUTED);
        LinearLayout guide = group(); SettingsUi.toggle(guide, get(R.string.safe_area_guides), get(R.string.safe_area_guides_help), prefs.safeAreaGuides(), value -> prefs.data.edit().putBoolean("safe_area_guides", value).apply()).setTag("safe-area-guides");
        LinearLayout actions = group(); Button start = button(actions, R.string.safe_area_begin, "safe-area-begin", this::begin); start.setEnabled(!busy && session != null && area != null);
        button(actions, R.string.safe_area_reset_one, "safe-area-reset-one", () -> confirmation.show(get(R.string.safe_area_reset_one), activity.getString(R.string.safe_area_reset_one_help, rotation * 90), get(R.string.safe_area_reset_action), () -> { if (!prefs.resetSafeArea(rotation)) failure(); render(); })).setEnabled(!busy);
        button(actions, R.string.safe_area_reset_all, "safe-area-reset-all", () -> confirmation.show(get(R.string.safe_area_reset_all), get(R.string.safe_area_reset_all_help), get(R.string.safe_area_reset_action), () -> { if (!prefs.resetSafeArea(-1)) failure(); render(); })).setEnabled(!busy);
        if (session != null && !prefs.data.getString("safe_area_rotation_restore", "").isEmpty()) button(actions, R.string.safe_area_recover_rotation, "safe-area-restore-rotation", session::recover).setEnabled(!busy);
        if (busy) button(actions, R.string.safe_area_cancel, "safe-area-cancel", this::cancel);
        feedback = label(this, session == null ? get(R.string.safe_area_unavailable) : session.message(), SettingsUi.MUTED); feedback.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        label(this, get(R.string.safe_area_device_local), SettingsUi.MUTED);
    }
    private void chooseMode() {
        // Mode selection changes no device rotation; only calibration requires consent.
        confirmation.mode(prefs.safeArea(rotation) != null && prefs.safeArea(rotation).manual(), manual -> {
                if (!manual) { if (!prefs.safeAreaMode(rotation, false)) failure(); render(); }
                else {
                    SafeAreaGeometry.Profile saved = prefs.safeArea(rotation); CoverSafeArea.Snapshot area = current();
                    if (saved == null || area == null || !saved.display().equals(area.identity())) begin();
                    else { if (!prefs.safeAreaMode(rotation, true)) failure(); render(); }
                }
            });
    }
    private void begin() {
        if (session == null || session.active()) return;
        Display target = Displays.selected(activity, prefs); if (target == null) { failure(); return; }
        boolean rotate = target.getRotation() != rotation;
        confirmation.show(get(rotate ? R.string.safe_area_switch_title : R.string.safe_area_lock_title), rotate ? activity.getString(R.string.safe_area_switch_message, rotation * 90) : get(R.string.safe_area_lock_message), get(R.string.safe_area_continue), () -> session.begin(rotation));
    }
    private void renderEditor() {
        label(this, get(R.string.safe_area_edit_help), SettingsUi.MUTED); LinearLayout edges = group();
        int[] labels = {R.string.safe_area_left, R.string.safe_area_top, R.string.safe_area_right, R.string.safe_area_bottom};
        for (int i = 0; i < 4; i++) {
            int index = i, value = edgeValues()[i]; SafeAreaGeometry.Profile draft = session.draft(); int max = Math.max(0, (i % 2 == 0 ? draft.width() : draft.height()) - 160);
            LinearLayout block = SettingsUi.column(activity); block.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 8), Ui.dp(activity, 14), 0); edges.addView(block);
            TextView label = label(block, get(labels[i]) + " · " + value + " px", SettingsUi.ACCENT); label.setPadding(0, 0, 0, 0);
            SettingsUi.Slider slider = new SettingsUi.Slider(activity, 0, max, value, number -> { label.setText(get(labels[index]) + " · " + number + " px"); }, number -> { changeEdge(index, number); refreshControls(); });
            slider.setTag("safe-area-edge-" + i); slider.setContentDescription(get(labels[i]) + "，单位 px"); block.addView(slider, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 48)));
            LinearLayout controls = SettingsUi.row(activity); block.addView(controls);
            Button minus = SettingsUi.button(activity, "−1", () -> { changeEdge(index, edgeValues()[index] - 1); refreshControls(); }); minus.setContentDescription(get(labels[i]) + "减少 1 px"); SettingsUi.weighted(controls, minus);
            Button number = SettingsUi.button(activity, "输入", () -> inputEdge(index, get(labels[index]))); number.setContentDescription("输入" + get(labels[i])); SettingsUi.weighted(controls, number);
            Button plus = SettingsUi.button(activity, "+1", () -> { changeEdge(index, edgeValues()[index] + 1); refreshControls(); }); plus.setContentDescription(get(labels[i]) + "增加 1 px"); SettingsUi.weighted(controls, plus);
        }
        LinearLayout actions = group(); button(actions, R.string.safe_area_fill, "safe-area-fill", session::fillAutomatic);
        button(actions, R.string.safe_area_save, "safe-area-save", () -> { session.save(); render(); });
        button(actions, R.string.safe_area_cancel, "safe-area-cancel", this::cancel);
        feedback = label(this, session.message(), SettingsUi.MUTED); feedback.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    }
    private int[] edgeValues() { SafeAreaGeometry.Edges e = session.draft().edges(); return new int[]{e.left(), e.top(), e.right(), e.bottom()}; }
    private void refreshControls() { if (session != null && session.editing()) { int[] values = edgeValues(); for (int i = 0; i < 4; i++) { SettingsUi.Slider slider = findViewWithTag("safe-area-edge-" + i); if (slider != null) slider.setProgress(values[i]); } } }
    private boolean changeEdge(int edge, int value) {
        if (session == null || !session.editing()) return false;
        int[] values = edgeValues(); values[edge] = value;
        if (!session.update(new SafeAreaGeometry.Edges(values[0], values[1], values[2], values[3]), edge)) { if (feedback != null) feedback.setText(get(R.string.safe_area_invalid)); return false; }
        if (preview != null && current() != null) preview.geometry(current());
        activity.getWindow().getDecorView().requestApplyInsets(); return true;
    }
    private void inputEdge(int edge, String title) {
        confirmation.number(title + " (px)", edgeValues()[edge], number -> {
            if (!session.editing()) return false;
            int[] values = edgeValues(); values[edge] = number; SafeAreaGeometry.Profile draft = session.draft();
            if (!new SafeAreaGeometry.Edges(values[0], values[1], values[2], values[3]).valid(draft.width(), draft.height())) return false;
            if (!changeEdge(edge, number)) return false; refreshControls(); return true;
        });
    }
    private void failure() { android.widget.Toast.makeText(activity, get(R.string.safe_area_save_failed), android.widget.Toast.LENGTH_SHORT).show(); }
    private final class Preview extends View {
        private final SafeAreaGuideView guide;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private CoverSafeArea.Snapshot area;
        Preview(android.content.Context context) { super(context); guide = new SafeAreaGuideView(context); setContentDescription(get(R.string.safe_area_preview)); }
        void geometry(CoverSafeArea.Snapshot value) { area = value; CoverService service = CoverService.instance; guide.geometry(value, service == null ? null : service.safeAreaDock(), session == null ? -1 : session.selectedEdge()); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            if (area == null) return;
            float padding = Ui.dp(activity, 8), scale = Math.min((getWidth() - padding * 2) / area.width(), (getHeight() - padding * 2) / area.height());
            canvas.save(); canvas.translate((getWidth() - area.width() * scale) / 2, (getHeight() - area.height() * scale) / 2); canvas.scale(scale, scale);
            paint.setColor(0xFF303034); canvas.drawRect(0, 0, area.width(), area.height(), paint); paint.setColor(0xFF171719);
            for (DockGeometry.Box cut : area.cutouts()) canvas.drawRect(cut.x(), cut.y(), cut.right(), cut.bottom(), paint);
            guide.strokeScale(1 / Math.max(.01f, scale)); guide.layout(0, 0, area.width(), area.height()); guide.draw(canvas); canvas.restore();
        }
    }
}
