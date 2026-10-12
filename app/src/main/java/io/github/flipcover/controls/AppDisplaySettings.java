package io.github.flipcover.controls;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** One application editor. System changes are explicit, journaled locally, and checked on readback. */
final class AppDisplaySettings extends LinearLayout {
    interface Confirmation { void show(String title, String message, String action, Runnable confirmed); }
    private final Activity activity;
    private final Prefs prefs;
    private final String packageName;
    private final ShizukuBridge bridge;
    private final Confirmation confirmation;
    private final Button simpleTab, advancedTab, apply, restore, open, refresh, permission, adopt;
    private final Button[] presets = new Button[5];
    private final LinearLayout advanced;
    private final TextView status, current, original;
    private final EditText dpi, scale;
    private final android.widget.Switch portrait;
    private final LinearLayout portraitRatios;
    private final Button[] ratioTabs = new Button[AppPortraitBounds.RATIOS.size()];
    private final Illustration preview;
    private final AppLauncher launcher = new AppLauncher();
    private final Runnable bridgeChanged = this::read;
    private volatile boolean visible;
    private volatile int generation;
    private boolean busy, advancedMode, binding, dirty, ready, loaded, conflict, defaultModePending;
    private int selected = AppDisplayPolicy.DEFAULT;
    private String entry = "", boundDpi = "", boundScale = "", device = "";
    private Display target;

    AppDisplaySettings(Activity activity, Prefs prefs, String packageName, String title, Bundle state, Runnable authorize, Confirmation confirmation) {
        super(activity); this.activity = activity; this.prefs = prefs; this.packageName = packageName; this.confirmation = confirmation; bridge = CoverApp.bridge(activity);
        setOrientation(VERTICAL); setTag("app-display-editor");
        LinearLayout entity = group(); entity.addView(SettingsUi.settingRow(activity, R.drawable.ic_ms_apps, title, get(R.string.display_size_description), null));
        LinearLayout modes = SettingsUi.row(activity);
        simpleTab = SettingsUi.button(activity, get(R.string.display_size_simple), () -> { defaultModePending = false; mode(false); }); simpleTab.setTag("display-mode-simple");
        advancedTab = SettingsUi.button(activity, get(R.string.display_size_advanced), () -> { defaultModePending = false; mode(true); }); advancedTab.setTag("display-mode-advanced");
        SettingsUi.weighted(modes, simpleTab); SettingsUi.weighted(modes, advancedTab); SettingsUi.add(this, modes);
        LinearLayout size = group(); current = label(size, "", SettingsUi.ACCENT); current.setTag("display-size-current");
        HorizontalScrollView strip = new HorizontalScrollView(activity); strip.setHorizontalScrollBarEnabled(false); strip.setFillViewport(true);
        LinearLayout tabs = SettingsUi.row(activity); strip.addView(tabs, new HorizontalScrollView.LayoutParams(-2, -2));
        String[] labels = getResources().getStringArray(R.array.display_size_presets);
        for (int i = 0; i < presets.length; i++) { int value = i; Button button = SettingsUi.button(activity, labels[i], () -> choose(value)); button.setTag("display-preset-" + i); button.setSingleLine(); button.setMinWidth(Ui.dp(activity, 56)); button.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0); presets[i] = button; tabs.addView(button, new LinearLayout.LayoutParams(-2, -2, 1)); }
        size.addView(strip);
        open = SettingsUi.button(activity, get(R.string.display_size_open), this::launch); open.setTag("display-size-open"); size.addView(open);
        label(size, get(R.string.display_size_restart_hint), SettingsUi.MUTED).setTag("display-size-restart-hint");
        preview = new Illustration(activity); size.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 84))); label(size, get(R.string.display_size_preview), SettingsUi.MUTED);
        advanced = group(); dpi = input(advanced, R.string.display_size_dpi, R.string.display_size_dpi_help, "display-dpi", false); scale = input(advanced, R.string.display_size_scale, R.string.display_size_scale_help, "display-scale", true);
        apply = SettingsUi.button(activity, get(R.string.display_size_apply), this::applyCustom); apply.setTag("display-size-apply"); advanced.addView(apply);
        original = label(advanced, "", SettingsUi.MUTED);
        LinearLayout help = group(); label(help, get(R.string.display_size_adaptive_title), SettingsUi.TEXT);
        label(help, getResources().getText(R.string.display_size_adaptive_help), SettingsUi.MUTED).setTag("display-size-adaptive-help");
        status = SettingsUi.text(activity, get(R.string.display_size_loading), 14, SettingsUi.MUTED); status.setTag("display-size-status"); status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE); SettingsUi.add(this, status);
        LinearLayout actions = group();
        restore = SettingsUi.button(activity, get(R.string.display_size_restore), () -> choose(AppDisplayPolicy.DEFAULT)); restore.setTag("display-size-restore"); actions.addView(restore);
        refresh = SettingsUi.button(activity, get(R.string.display_size_refresh), this::read); refresh.setTag("display-size-refresh"); actions.addView(refresh);
        permission = SettingsUi.button(activity, get(R.string.display_size_authorize), authorize); actions.addView(permission);
        adopt = SettingsUi.button(activity, get(R.string.display_size_adopt), this::adopt); adopt.setTag("display-size-adopt"); actions.addView(adopt);
        LinearLayout portraitGroup = group();
        portrait = SettingsUi.toggle(portraitGroup, get(R.string.display_size_portrait_title), get(R.string.display_size_portrait_help), prefs.appPortrait(packageName), this::portrait);
        portrait.setTag("display-size-portrait");
        portraitRatios = SettingsUi.column(activity); portraitRatios.setTag("display-portrait-ratios"); portraitGroup.addView(portraitRatios);
        label(portraitRatios, get(R.string.display_size_portrait_ratio), SettingsUi.TEXT);
        HorizontalScrollView ratioStrip = new HorizontalScrollView(activity); ratioStrip.setHorizontalScrollBarEnabled(false); ratioStrip.setFillViewport(true);
        LinearLayout ratioRow = SettingsUi.row(activity); ratioStrip.addView(ratioRow, new HorizontalScrollView.LayoutParams(-2, -2)); portraitRatios.addView(ratioStrip);
        for (int i = 0; i < ratioTabs.length; i++) {
            String ratio = AppPortraitBounds.RATIOS.get(i); Button button = SettingsUi.button(activity, ratio, () -> portraitRatio(ratio)); button.setTag("display-portrait-ratio-" + i); button.setSingleLine(); button.setMinWidth(Ui.dp(activity, 56)); button.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0); ratioTabs[i] = button; ratioRow.addView(button, new LinearLayout.LayoutParams(-2, -2, 1));
        }
        label(portraitGroup, get(R.string.display_size_portrait_direction_hint), SettingsUi.MUTED);
        label(this, get(R.string.display_size_scope), SettingsUi.MUTED);
        JSONObject rule = prefs.displaySizeRule(packageName); if (rule != null) { entry = rule.optString("applied"); selected = AppDisplayPolicy.selectedPreset(rule.optString("original"), entry, rule.optInt("preset", AppDisplayPolicy.CUSTOM)); }
        defaultModePending = !state.containsKey("display-advanced");
        mode(state.getBoolean("display-advanced", selected == AppDisplayPolicy.CUSTOM)); fields(suggested());
        if (state.getBoolean("display-dirty")) { binding = true; dpi.setText(state.getString("display-dpi", boundDpi)); scale.setText(state.getString("display-scale", boundScale)); binding = false; dirty = true; }
        render();
    }
    private String get(int id) { return getContext().getString(id); }
    private LinearLayout group() { LinearLayout group = SettingsUi.group(getContext()); SettingsUi.add(this, group); return group; }
    private TextView label(LinearLayout parent, CharSequence value, int color) { TextView label = SettingsUi.text(getContext(), value.toString(), 14, color); label.setText(value); label.setPadding(Ui.dp(getContext(), 14), Ui.dp(getContext(), 8), Ui.dp(getContext(), 14), Ui.dp(getContext(), 8)); parent.addView(label); return label; }
    private EditText input(LinearLayout parent, int title, int help, String tag, boolean decimal) {
        label(parent, get(title), SettingsUi.TEXT); EditText input = new EditText(getContext()); input.setTag(tag); input.setTextColor(SettingsUi.TEXT); input.setTextSize(17); input.setSingleLine(); input.setContentDescription(get(title)); input.setMinimumHeight(Ui.dp(getContext(), 48)); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | (decimal ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : 0)); input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)}); input.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(Ui.dp(getContext(), 14), 0, Ui.dp(getContext(), 14), 0); parent.addView(input, lp); label(parent, get(help), SettingsUi.MUTED);
        input.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { if (!binding) { dirty = !dpi.getText().toString().equals(boundDpi) || !scale.getText().toString().equals(boundScale); if (dirty) status.setText(R.string.display_size_pending); open.setEnabled(!busy && !dirty && target != null); } } public void afterTextChanged(android.text.Editable value) { } }); return input;
    }
    private void mode(boolean advancedMode) { this.advancedMode = advancedMode; advanced.setVisibility(advancedMode ? VISIBLE : GONE); SettingsUi.select(simpleTab, !advancedMode); SettingsUi.select(advancedTab, advancedMode); }
    void saveUiState(Bundle state) { state.putBoolean("display-advanced", advancedMode); state.putBoolean("display-dirty", dirty); state.putString("display-dpi", dpi.getText().toString()); state.putString("display-scale", scale.getText().toString()); }
    boolean hasDraft() { return dirty; }
    void discardDraft() { fields(suggested()); }
    void resume() { if (visible) return; visible = true; bridge.addObserver(bridgeChanged); read(); }
    void pause() { visible = false; generation++; bridge.removeObserver(bridgeChanged); }
    @Override protected void onDetachedFromWindow() { pause(); super.onDetachedFromWindow(); }
    private String identity(Display display) { return android.os.Process.myUid() / 100000 + ":" + android.os.Build.DEVICE + ":" + android.os.Build.MODEL + ":" + display.getDisplayId() + ":" + display.getName() + ":" + display.getMode().getPhysicalWidth() + "x" + display.getMode().getPhysicalHeight(); }
    private boolean active(int request, Display display) { Display selected = Displays.selected(activity, prefs); return visible && generation == request && selected != null && identity(selected).equals(identity(display)); }
    private void read() {
        if (!visible || busy) return;
        ready = false; loaded = false; conflict = false; target = Displays.selected(activity, prefs);
        if (!bridge.connected()) { status.setText(R.string.display_size_permission); render(); return; }
        if (target == null) { status.setText(R.string.display_size_display); render(); return; }
        device = identity(target); JSONObject rule = prefs.displaySizeRule(packageName);
        if (rule != null && !device.equals(rule.optString("device"))) { status.setText(R.string.display_size_device_changed); render(); return; }
        int request = ++generation; Display display = target; busy = true; status.setText(R.string.display_size_loading); render();
        try { bridge.run("app_display_read", display.getDisplayId(), 0, SystemAppDisplay.request(packageName, display).toString(), () -> active(request, display), result -> {
            busy = false; if (!active(request, display)) { if (visible) post(this::read); return; }
            try {
                JSONObject response = result.output.isEmpty() ? new JSONObject() : new JSONObject(result.output);
                if (!result.ok) { status.setText(message(response.optString("state"))); render(); return; }
                entry = response.getString("entry"); reconcile(entry); loaded = true; ready = response.optString("state").equals("ready");
                if (defaultModePending) { mode(selected == AppDisplayPolicy.CUSTOM); defaultModePending = false; }
                status.setText(conflict ? R.string.display_size_conflict : !ready ? R.string.display_size_membership : selected == AppDisplayPolicy.CUSTOM ? R.string.display_size_custom_hint : baseline().isEmpty() ? R.string.display_size_suggestion : R.string.display_size_ready);
                if (!dirty) fields(suggested()); render();
            } catch (Exception error) { ready = false; status.setText(R.string.display_size_read_failure); render(); }
        }); } catch (Exception error) { busy = false; status.setText(R.string.display_size_read_failure); render(); }
    }
    private void reconcile(String actual) throws Exception {
        JSONObject rule = prefs.displaySizeRule(packageName); if (rule == null) { selected = AppDisplayPolicy.DEFAULT; return; }
        if (rule.has("pending")) {
            if (actual.equals(rule.getString("pending"))) rule.put("applied", actual).put("preset", rule.getInt("pendingPreset"));
            if (actual.equals(rule.optString("applied"))) { rule.remove("pending"); rule.remove("pendingPreset"); prefs.saveDisplaySizeRule(packageName, rule); }
        }
        conflict = !actual.equals(rule.optString("applied")); selected = AppDisplayPolicy.selectedPreset(rule.optString("original"), actual, rule.optInt("preset", AppDisplayPolicy.CUSTOM));
    }
    private String baseline() { JSONObject rule = prefs.displaySizeRule(packageName); return rule == null ? entry : rule.optString("original"); }
    private AppDisplayPolicy.Config suggested() {
        try { if (entry.isEmpty()) return AppDisplayPolicy.baseline(entry); AppDisplayPolicy.Config value = AppDisplayPolicy.config(entry); value.validate(); return value; } catch (RuntimeException ignored) { return AppDisplayPolicy.reference(); }
    }
    private void fields(AppDisplayPolicy.Config value) { binding = true; boundDpi = String.valueOf(value.dpi()); boundScale = String.valueOf(value.scale()); dpi.setText(boundDpi); scale.setText(boundScale); dpi.setError(null); scale.setError(null); dirty = false; binding = false; }
    private void render() {
        binding = true; portrait.setChecked(prefs.appPortrait(packageName)); binding = false;
        boolean canPortrait = !busy && (prefs.appPortrait(packageName) || ready && loaded && !conflict);
        portrait.setEnabled(canPortrait); ((View) portrait.getParent()).setEnabled(canPortrait);
        portraitRatios.setVisibility(prefs.appPortrait(packageName) ? VISIBLE : GONE);
        for (int i = 0; i < ratioTabs.length; i++) { SettingsUi.select(ratioTabs[i], AppPortraitBounds.RATIOS.get(i).equals(prefs.appPortraitRatio(packageName))); ratioTabs[i].setEnabled(!busy && ready && loaded && !conflict && prefs.appPortrait(packageName)); }
        current.setText(conflict ? get(R.string.display_size_conflict) : selected == AppDisplayPolicy.CUSTOM ? get(R.string.display_size_custom) : getResources().getStringArray(R.array.display_size_presets)[Math.max(0, Math.min(4, selected))]);
        for (int i = 0; i < presets.length; i++) { SettingsUi.select(presets[i], !conflict && selected == i); presets[i].setEnabled(!busy && !conflict && (ready || i == AppDisplayPolicy.DEFAULT && loaded && prefs.displaySizeRule(packageName) != null)); }
        apply.setEnabled(ready && !busy && !conflict); dpi.setEnabled(!busy); scale.setEnabled(!busy); refresh.setEnabled(!busy); restore.setEnabled(presets[AppDisplayPolicy.DEFAULT].isEnabled());
        open.setEnabled(!busy && !dirty && target != null); permission.setVisibility(bridge.connected() ? GONE : VISIBLE); adopt.setVisibility(conflict ? VISIBLE : GONE);
        String old = baseline(); original.setText(get(R.string.display_size_original) + "\n" + (old.isEmpty() ? get(R.string.display_size_system_default) : parameters(old)));
        try { preview.ratio = (float) Math.max(.65, Math.min(1.4, suggested().effectiveDpi() / AppDisplayPolicy.baseline(old).effectiveDpi())); } catch (RuntimeException ignored) { preview.ratio = 1; } preview.invalidate();
    }
    private String parameters(String raw) { try { AppDisplayPolicy.Config value = AppDisplayPolicy.config(raw); return getContext().getString(R.string.display_size_parameters, value.dpi(), String.valueOf(value.scale())); } catch (RuntimeException error) { return get(R.string.display_size_system_default); } }
    private void choose(int preset) {
        AppDisplayPolicy.Config value;
        try { value = preset == AppDisplayPolicy.DEFAULT ? null : AppDisplayPolicy.preset(baseline(), preset); }
        catch (RuntimeException error) { status.setText(R.string.display_size_baseline_unknown); return; }
        try { submit(value, preset); } catch (Exception error) { status.setText(R.string.display_size_failure); }
    }
    private void applyCustom() {
        AppDisplayPolicy.Config value;
        try { value = new AppDisplayPolicy.Config(Integer.parseInt(dpi.getText().toString()), Double.parseDouble(scale.getText().toString())); value.validate(); }
        catch (RuntimeException error) {
            int message = switch (String.valueOf(error.getMessage())) { case "dpi" -> R.string.display_size_dpi_invalid; case "scale" -> R.string.display_size_scale_invalid; case "size_small" -> R.string.display_size_pair_small; case "size_large" -> R.string.display_size_pair_large; default -> R.string.display_size_limits; };
            status.setText(message); EditText field = "dpi".equals(error.getMessage()) ? dpi : scale; field.setError(get(message)); field.requestFocus(); return;
        }
        dpi.setError(null); scale.setError(null);
        try { submit(value, AppDisplayPolicy.CUSTOM); } catch (Exception error) { status.setText(R.string.display_size_failure); }
    }
    private void submit(AppDisplayPolicy.Config value, int preset) throws Exception {
        if (busy || conflict || !visible || target == null || !bridge.connected() || !ready && preset != AppDisplayPolicy.DEFAULT) return;
        Display display = target; if (!AppLauncher.ready(activity, prefs, display.getDisplayId())) { status.setText(R.string.display_size_locked); return; }
        String wanted = value == null ? baseline() : AppDisplayPolicy.entry(packageName, value);
        JSONObject rule = prefs.displaySizeRule(packageName);
        if (rule == null) rule = new JSONObject().put("device", device).put("original", entry).put("applied", entry).put("preset", AppDisplayPolicy.DEFAULT);
        if (!device.equals(rule.optString("device"))) { status.setText(R.string.display_size_device_changed); return; }
        JSONObject request = SystemAppDisplay.request(packageName, display).put("expected", entry).put("restore", value == null);
        if (value == null) request.put("original", wanted); else request.put("dpi", value.dpi()).put("scale", value.scale());
        rule.put("pending", wanted).put("pendingPreset", preset); prefs.saveDisplaySizeRule(packageName, rule);
        int batch = ++generation; busy = true; status.setText(R.string.display_size_saving); render();
        bridge.run("app_display_write", display.getDisplayId(), 0, request.toString(), () -> active(batch, display), result -> {
            busy = false;
            // Finalize our durable journal even if its page has gone away. Never revive that page.
            if (result.ok) try {
                JSONObject stored = prefs.displaySizeRule(packageName);
                if (stored != null && wanted.equals(stored.optString("pending")) && device.equals(stored.optString("device"))) { stored.put("applied", wanted).put("preset", preset); stored.remove("pending"); stored.remove("pendingPreset"); prefs.saveDisplaySizeRule(packageName, stored); }
            } catch (Exception ignored) { /* A later read reconciles the retained pending record. */ }
            if (!active(batch, display)) { if (visible) post(this::read); return; }
            if (result.ok) { entry = wanted; selected = preset; fields(value == null ? suggested() : value); status.setText(value == null ? R.string.display_size_restored : R.string.display_size_saved); }
            else { ready = false; loaded = false; status.setText(R.string.display_size_failure); }
            render();
        });
    }
    private void adopt() {
        confirmation.show(get(R.string.display_size_adopt_title), get(R.string.display_size_adopt_message), get(R.string.display_size_adopt_confirm), () -> {
            try {
                JSONObject previous = prefs.displaySizeRule(packageName);
                JSONObject rule = previous != null && previous.has("portrait") ? new JSONObject().put("device", device).put("original", entry).put("applied", entry).put("preset", AppDisplayPolicy.DEFAULT).put("portrait", previous.optBoolean("portrait")).put("portraitRatio", prefs.appPortraitRatio(packageName)) : null;
                prefs.saveDisplaySizeRule(packageName, rule); conflict = false; dirty = false; read();
            } catch (Exception error) { status.setText(R.string.display_size_failure); }
        });
    }
    private void portraitRatio(String ratio) {
        if (busy || !visible || !ready || !loaded || conflict || !prefs.appPortrait(packageName) || !AppPortraitBounds.RATIOS.contains(ratio)) return;
        try {
            JSONObject rule = prefs.displaySizeRule(packageName);
            if (rule == null) return;
            prefs.saveDisplaySizeRule(packageName, rule.put("portraitRatio", ratio));
            status.setText(dirty ? R.string.display_size_portrait_ratio_saved_draft : R.string.display_size_portrait_ratio_saved);
        } catch (Exception error) { status.setText(R.string.display_size_failure); }
        render();
    }
    private void portrait(boolean enabled) {
        if (binding) return;
        if (busy || !visible || enabled && (!ready || !loaded || conflict)) { render(); return; }
        try {
            JSONObject rule = prefs.displaySizeRule(packageName);
            if (rule == null) rule = new JSONObject().put("device", device).put("original", entry).put("applied", entry).put("preset", selected);
            prefs.saveDisplaySizeRule(packageName, rule.put("portrait", enabled));
            status.setText(dirty ? R.string.display_size_portrait_saved_draft : R.string.display_size_portrait_saved);
        } catch (Exception error) { status.setText(R.string.display_size_failure); }
        render();
    }
    private void launch() {
        if (dirty || busy) { status.setText(R.string.display_size_pending); return; }
        Display display = Displays.selected(activity, prefs); Intent intent = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (display == null || intent == null || intent.getComponent() == null) { status.setText(display == null ? R.string.display_size_display : R.string.display_size_unavailable); return; }
        launcher.launch(activity, prefs, "app:" + intent.getComponent().flattenToString(), display.getDisplayId(), () -> visible && isAttachedToWindow(), text -> status.setText(text), accepted -> { });
    }
    private int message(String state) { return switch (state) { case "unsupported" -> R.string.display_size_unsupported; case "membership" -> R.string.display_size_membership; case "protected" -> R.string.display_size_protected; case "display" -> R.string.display_size_display; case "conflict" -> R.string.display_size_conflict; default -> R.string.display_size_read_failure; }; }
    private static final class Illustration extends View {
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); float ratio = 1;
        Illustration(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas); float unit = getResources().getDisplayMetrics().density, left = 16 * unit, top = 8 * unit, width = getWidth() - 32 * unit;
            canvas.save(); canvas.clipRect(left, top, left + width, getHeight() - top); float row = 33 * unit * ratio;
            for (int i = 0; i < 8; i++) { float y = top + i * row; paint.setColor(0xFF80AAFF); canvas.drawRoundRect(left, y, left + 20 * unit * ratio, y + 20 * unit * ratio, 5 * unit, 5 * unit, paint); paint.setColor(SettingsUi.TEXT); paint.setTextSize(14 * unit * ratio); canvas.drawText(getContext().getString(R.string.display_size_preview_item), left + 30 * unit * ratio, y + 15 * unit * ratio, paint); }
            canvas.restore();
        }
    }
}
