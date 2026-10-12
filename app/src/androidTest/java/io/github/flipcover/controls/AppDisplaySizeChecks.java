package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Disposable emulator only. All policy I/O is an in-memory service; no Samsung settings are written. */
final class AppDisplaySizeChecks {
    private static final String PACKAGE = "com.android.settings";
    private final Instrumentation test;
    private MainActivity activity;
    private int assertions;
    AppDisplaySizeChecks(Instrumentation test) { this.test = test; }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); assertions++; }
    private View tag(String tag) { return activity.getWindow().getDecorView().findViewWithTag(tag); }
    private String text(String tag) { return ((TextView) tag(tag)).getText().toString(); }
    private void click(String tag) { test.runOnMainSync(() -> { require(this.tag(tag) != null && this.tag(tag).isEnabled(), "enabled " + tag); this.tag(tag).performClick(); }); }
    private void waitFor(BooleanSupplier condition) { long until = SystemClock.uptimeMillis() + 6000; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30); require(condition.getAsBoolean(), "async operation completed"); test.waitForIdleSync(); }
    private void open() { activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "app_display_size").putExtra("orientation_package", PACKAGE)); test.waitForIdleSync(); }
    String run() throws Exception {
        Context context = test.getTargetContext(); Prefs prefs = new Prefs(context); ShizukuBridge bridge = CoverApp.bridge(context);
        Field remote = ShizukuBridge.class.getDeclaredField("remote"); remote.setAccessible(true); Object previous = remote.get(bridge);
        String before = prefs.data.getString("app_display_sizes", null); int oldDisplay = prefs.displayId();
        ImageReader image = ImageReader.newInstance(720, 748, PixelFormat.RGBA_8888, 2);
        VirtualDisplay virtual = context.getSystemService(DisplayManager.class).createVirtualDisplay("Display size checks", 720, 748, 340, image.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        FakeService fake = new FakeService();
        try {
            prefs.data.edit().remove("app_display_sizes").putInt("display", virtual.getDisplay().getDisplayId()).commit(); remote.set(bridge, fake); open(); waitFor(() -> fake.reads > 0 && !bridge.busy());
            test.runOnMainSync(() -> { require(fake.writes == 0, "opening never mutates settings"); require(tag("display-preset-2").isSelected(), "default selected"); require(tag("display-mode-simple").isSelected(), "simple initial mode"); });
            capture("display-size-simple.png");
            test.runOnMainSync(() -> require(tag("display-portrait-ratios").getVisibility() == View.GONE, "ratios hidden when portrait is off"));
            click("display-size-portrait");
            require(prefs.appPortrait(PACKAGE) && prefs.displaySizeCount() == 1 && fake.writes == 0, "portrait is counted without changing system scale");
            test.runOnMainSync(() -> {
                require(tag("display-portrait-ratios").getVisibility() == View.VISIBLE && tag("display-portrait-ratio-2").isSelected() && prefs.appPortraitRatio(PACKAGE).equals("12:16"), "portrait defaults to visible 12:16 selection");
                for (int i = 0; i < 4; i++) require(text("display-portrait-ratio-" + i).equals(AppPortraitBounds.RATIOS.get(i)), "ratio tab order");
            });
            click("display-portrait-ratio-0"); require(prefs.appPortraitRatio(PACKAGE).equals("9:20") && fake.writes == 0, "ratio saves locally without changing DPI or scale");
            test.runOnMainSync(() -> { android.widget.ScrollView scroll = (android.widget.ScrollView) tag("settings-scroll"); int top = 0; View child = tag("display-portrait-ratios"); while (child != scroll) { top += child.getTop(); child = (View) child.getParent(); } scroll.scrollTo(0, Math.max(0, top - Ui.dp(activity, 56))); }); capture("display-portrait-ratios.png");
            java.util.concurrent.atomic.AtomicBoolean launchOwner = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicReference<Boolean> launchResult = new java.util.concurrent.atomic.AtomicReference<>();
            fake.onPortrait = () -> launchOwner.set(false);
            test.runOnMainSync(() -> new AppLauncher().launch(context, prefs, "app:" + context.getPackageManager().getLaunchIntentForPackage(PACKAGE).getComponent().flattenToString(), virtual.getDisplay().getDisplayId(), launchOwner::get, message -> { throw new AssertionError(message); }, launchResult::set));
            waitFor(() -> launchResult.get() != null && !bridge.busy());
            require(launchResult.get() && fake.portraitOpens == 1 && fake.portraitCloses == 0 && "9:20".equals(fake.lastPortraitRatio), "remote launch receives selected ratio and survives launching UI pause");
            test.runOnMainSync(() -> require(SettingsUi.toggleSummary((android.widget.Switch) tag("display-size-portrait")).getText().toString().contains("Pad 模式"), "portrait has requested explanation"));
            click("display-preset-1"); waitFor(() -> fake.writes == 1 && !bridge.busy());
            require(prefs.displaySizeRule(PACKAGE).getInt("preset") == 1 && !prefs.displaySizeRule(PACKAGE).has("pending"), "preset committed after readback");
            require(AppDisplayPolicy.config(fake.entry).dpi() == AppDisplayPolicy.reference().dpi() && AppDisplayPolicy.config(fake.entry).scale() > AppDisplayPolicy.reference().scale(), "smaller uses compatibility scale with unchanged DPI");
            test.runOnMainSync(() -> { require(text("display-size-restart-hint").contains("不会自动重启"), "opening distinguished from restarting"); require(text("display-size-adaptive-help").contains("AndroidAutoSize") && !text("display-size-adaptive-help").contains("DPI"), "simple explanation identifies width adaptation without density units"); });
            test.runOnMainSync(() -> { CharSequence help = ((TextView) tag("display-size-adaptive-help")).getText(); require(help instanceof android.text.Spanned styled && java.util.Arrays.stream(styled.getSpans(0, styled.length(), android.text.style.StyleSpan.class)).anyMatch(span -> span.getStyle() == android.graphics.Typeface.BOLD && styled.subSequence(styled.getSpanStart(span), styled.getSpanEnd(span)).toString().equals("AndroidAutoSize")), "library name is bold in rendered text"); });
            test.runOnMainSync(() -> { android.widget.ScrollView scroll = (android.widget.ScrollView) tag("settings-scroll"); int top = 0; View child = (View) tag("display-size-adaptive-help").getParent(); while (child != scroll) { top += child.getTop(); child = (View) child.getParent(); } scroll.scrollTo(0, top); }); capture("display-size-help.png");
            click("display-mode-advanced");
            test.runOnMainSync(() -> { require(fake.writes == 1, "mode switch never writes"); ((EditText) tag("display-dpi")).setText("480"); ((EditText) tag("display-scale")).setText("0.5"); require(!tag("display-size-open").isEnabled(), "draft cannot be mistaken for applied"); });
            click("display-mode-simple"); click("display-mode-advanced");
            test.runOnMainSync(() -> require(text("display-dpi").equals("480") && text("display-scale").equals("0.5"), "mode switching retains magnification input"));
            click("display-size-portrait");
            require(!prefs.appPortrait(PACKAGE) && fake.writes == 1, "disabling portrait does not apply parameter drafts");
            test.runOnMainSync(() -> require(tag("display-portrait-ratios").getVisibility() == View.GONE && prefs.appPortraitRatio(PACKAGE).equals("9:20"), "disabling hides ratio controls without losing choice"));
            test.runOnMainSync(() -> require(text("display-dpi").equals("480") && text("display-scale").equals("0.5") && !tag("display-size-open").isEnabled(), "portrait toggle retains unapplied drafts"));
            click("display-size-portrait");
            test.runOnMainSync(() -> require(tag("display-portrait-ratio-0").isSelected(), "reenabling preserves selected ratio"));
            click("display-size-apply"); waitFor(() -> fake.writes == 2 && !bridge.busy());
            test.runOnMainSync(() -> { require(text("display-size-current").equals("自定义"), "custom mode is truthful"); for (int i = 0; i < 5; i++) require(!tag("display-preset-" + i).isSelected(), "custom is not a preset"); });
            int readsBeforeReopen = fake.reads;
            test.runOnMainSync(activity::finish); test.waitForIdleSync(); open(); waitFor(() -> fake.reads > readsBeforeReopen && !bridge.busy());
            test.runOnMainSync(() -> require(tag("display-mode-advanced").isSelected() && text("display-dpi").equals("480") && text("display-scale").equals("0.5") && fake.writes == 2, "reopening custom parameters defaults to advanced without writing"));
            test.runOnMainSync(() -> require(tag("display-portrait-ratio-0").isSelected(), "reopening retains ratio"));
            click("display-mode-simple"); int readsBeforeManualMode = fake.reads;
            click("display-size-refresh"); waitFor(() -> fake.reads > readsBeforeManualMode && !bridge.busy());
            test.runOnMainSync(() -> require(tag("display-mode-simple").isSelected(), "refresh respects an explicit mode choice"));
            test.runOnMainSync(() -> {
                Bundle simpleState = new Bundle(); simpleState.putBoolean("display-advanced", false);
                AppDisplaySettings restored = new AppDisplaySettings(activity, prefs, PACKAGE, "Settings", simpleState, () -> { }, (title, message, action, confirmed) -> { });
                require(restored.findViewWithTag("display-mode-simple").isSelected(), "navigation restoration respects the saved mode for custom values");
            });
            click("display-mode-advanced");
            test.runOnMainSync(() -> { android.widget.ScrollView scroll = (android.widget.ScrollView) tag("settings-scroll"); int top = 0; View child = tag("display-dpi"); while (child != scroll) { top += child.getTop(); child = (View) child.getParent(); } scroll.scrollTo(0, Math.max(0, top - Ui.dp(activity, 50))); }); capture("display-size-advanced.png");
            click("display-size-refresh"); waitFor(() -> fake.reads >= 2 && !bridge.busy());
            test.runOnMainSync(() -> require(text("display-dpi").equals("480") && text("display-scale").equals("0.5"), "read preserves exact magnification parameters"));
            test.runOnMainSync(() -> { ((EditText) tag("display-dpi")).setText("120"); ((EditText) tag("display-scale")).setText("1.01"); }); click("display-size-apply");
            test.runOnMainSync(() -> require(fake.writes == 2 && ((EditText) tag("display-scale")).getError() != null && text("display-size-status").contains("未保存"), "invalid pair is marked beside input and never saved"));
            test.runOnMainSync(() -> ((EditText) tag("display-dpi")).setText("1")); click("display-size-apply");
            test.runOnMainSync(() -> require(fake.writes == 2 && text("display-size-status").contains("120"), "invalid input never reaches service"));
            click("display-preset-2"); waitFor(() -> fake.writes == 3 && !bridge.busy()); require(fake.entry.isEmpty(), "default restores absent original entry"); require(prefs.appPortrait(PACKAGE) && prefs.displaySizeCount() == 1, "size restore preserves independent portrait switch");
            click("display-size-portrait"); require(prefs.displaySizeCount() == 0, "fully restored entry excluded from count");
            fake.entry = AppDisplayPolicy.entry(PACKAGE, new AppDisplayPolicy.Config(520, 2)); click("display-size-refresh"); waitFor(() -> fake.reads >= 3 && !bridge.busy());
            test.runOnMainSync(() -> { require(!tag("display-preset-0").isEnabled(), "external conflict prevents overwrite"); require(tag("display-size-adopt").getVisibility() == View.VISIBLE, "explicit adoption offered"); });
            // Restore our observed state to exercise a late write completion on an unmounted page.
            fake.entry = ""; click("display-size-refresh"); waitFor(() -> fake.reads >= 4 && !bridge.busy());
            fake.block = true; click("display-preset-0"); waitFor(() -> fake.started.getCount() == 0);
            test.runOnMainSync(activity::finish); test.waitForIdleSync(); fake.release.countDown(); waitFor(() -> !bridge.busy());
            require(prefs.displaySizeRule(PACKAGE).getInt("preset") == 0 && !prefs.displaySizeRule(PACKAGE).has("pending"), "late completion finalizes journal without reopening UI");
            fake.block = false; open(); waitFor(() -> fake.reads >= 5 && !bridge.busy());
            test.runOnMainSync(() -> require(tag("display-preset-0").isSelected() && tag("display-mode-simple").isSelected(), "reopening a preset defaults to simple with its tab selected"));
            test.runOnMainSync(() -> { clickOnMain("display-mode-advanced"); ((EditText) tag("display-dpi")).setText("620"); });
            final Bundle[] saved = new Bundle[1]; test.runOnMainSync(() -> { saved[0] = SettingsNavigator.capture((android.widget.ScrollView) tag("settings-scroll"), (android.view.ViewGroup) tag("app-display-editor").getParent()); require(saved[0].getBoolean("display-dirty") && saved[0].getBoolean("display-advanced"), "draft and mode participate in navigation state"); });
            return "PASS app-display-size assertions=" + assertions;
        } finally {
            fake.release.countDown(); if (activity != null) test.runOnMainSync(activity::finish); waitFor(() -> !bridge.busy()); remote.set(bridge, previous);
            android.content.SharedPreferences.Editor edit = prefs.data.edit().putInt("display", oldDisplay); if (before == null) edit.remove("app_display_sizes"); else edit.putString("app_display_sizes", before); edit.commit(); virtual.release(); image.close();
        }
    }
    private void capture(String name) throws Exception {
        test.runOnMainSync(() -> { ((EditText) tag("display-dpi")).clearFocus(); activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(tag("display-dpi").getWindowToken(), 0); }); test.waitForIdleSync(); SystemClock.sleep(120);
        // Enter ordinary touch mode; programmatic clicks otherwise leave the emulator's keyboard focus ring.
        int[] title = new int[2]; test.runOnMainSync(() -> tag("settings-title").getLocationOnScreen(title)); long now = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, title[0] + 20, title[1] + 10, 0), up = android.view.MotionEvent.obtain(now, now + 20, android.view.MotionEvent.ACTION_UP, title[0] + 20, title[1] + 10, 0);
        test.sendPointerSync(down); test.sendPointerSync(up); down.recycle(); up.recycle(); test.waitForIdleSync();
        android.graphics.Bitmap screenshot = test.getUiAutomation().takeScreenshot();
        if (screenshot != null) { try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(test.getTargetContext().getFilesDir(), name))) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } screenshot.recycle(); }
    }
    private void clickOnMain(String tag) { tag(tag).performClick(); }
    private static final class FakeService extends IShellService.Stub {
        volatile int reads, writes; volatile String entry = ""; volatile boolean block;
        volatile int portraitOpens, portraitCloses; volatile String lastPortraitRatio; Runnable onPortrait;
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public String execute(String operation, int display, int value, String payload) {
            try {
                if (operation.equals("app_portrait_open")) { portraitOpens++; lastPortraitRatio = new JSONObject(payload).getString("ratio"); if (onPortrait != null) onPortrait.run(); return "{\"ok\":true}"; }
                if (operation.equals("app_portrait_close")) { portraitCloses++; return "{\"ok\":true}"; }
                JSONObject request = new JSONObject(payload); boolean write = operation.equals("app_display_write");
                if (write) { if (block) { started.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); } if (!entry.equals(request.getString("expected"))) throw new IllegalStateException("conflict"); entry = request.optBoolean("restore") ? request.getString("original") : AppDisplayPolicy.entry(request.getString("package"), new AppDisplayPolicy.Config(request.getInt("dpi"), request.getDouble("scale"))); writes++; } else reads++;
                return new JSONObject().put("ok", true).put("message", "").put("output", new JSONObject().put("entry", entry).put("state", write ? "saved" : "ready").toString()).toString();
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public void watchConnectivity(IConnectivityListener listener) { }
        @Override public void watchSwitches(IConnectivityListener listener) { }
        @Override public Bundle taskSnapshot(int display, String task) { return new Bundle(); }
        @Override public void destroy() { }
    }
}
