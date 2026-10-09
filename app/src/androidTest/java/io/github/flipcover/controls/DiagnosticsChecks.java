package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Refreshes the actual settings page; fake Shizuku results never invoke system commands. */
final class DiagnosticsChecks {
    private final Instrumentation test;
    private final int rotation;
    private MainActivity activity;
    private int assertions;
    DiagnosticsChecks(Instrumentation test, int rotation) { this.test = test; this.rotation = rotation; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private View tag(String name) { return activity.findViewById(android.R.id.content).findViewWithTag(name); }
    private String report() { return ((TextView) tag("diagnostic-details")).getText().toString(); }
    private Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private void waitFor(BooleanSupplier condition) {
        boolean[] ready = {false}; long deadline = SystemClock.uptimeMillis() + 4000;
        do { main(() -> ready[0] = condition.getAsBoolean()); if (ready[0]) break; SystemClock.sleep(40); } while (SystemClock.uptimeMillis() < deadline);
        require(ready[0], "pending diagnostic callback completes");
    }
    private void textFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView text && text.getLayout() != null) require(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2, "diagnostic action text fits its row");
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) textFits(group.getChildAt(i));
    }
    String run() throws Exception {
        if (rotation >= 0) require(test.getUiAutomation().setRotation(rotation), "requested settings rotation");
        Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
        activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("section", "diagnostics"));
        SystemClock.sleep(700); test.waitForIdleSync(); if (monitor.getLastActivity() != null) activity = (MainActivity) monitor.getLastActivity(); test.removeMonitor(monitor);
        Prefs prefs = new Prefs(activity); boolean hadBlur = prefs.data.contains("panel_blur"), blur = prefs.panelBlur();
        String status = CoverService.status; ShizukuBridge bridge = CoverApp.bridge(activity); Field remote = field(ShizukuBridge.class, "remote"); Object originalRemote = remote.get(bridge); FakeService fake = new FakeService();
        try {
            require(!bridge.busy(), "bridge starts idle"); remote.set(bridge, fake);
            View details = tag("diagnostic-details"), refresh = tag("diagnostic-refresh"), scroll = tag("settings-scroll");
            main(() -> {
                require(details.getVisibility() == View.GONE && refresh.isEnabled(), "refresh available without expanding raw details");
                require(report().contains("采集时间："), "report identifies capture time");
                prefs.data.edit().putBoolean("panel_blur", !blur).commit(); CoverService.status = "诊断刷新验证";
                refresh.performClick(); require(report().contains("面板模糊开关：" + !blur) && report().contains("快捷栏状态：诊断刷新验证"), "refresh reads current preferences and service state");
                require(report().contains("省电 API（系统有效策略）：" + activity.getSystemService(android.os.PowerManager.class).isPowerSaveMode()), "refresh samples the current platform power policy");
                require(report().contains("标准省电配置（global.low_power）：" + PowerDiagnostics.setting(android.provider.Settings.Global.getString(activity.getContentResolver(), "low_power"))), "refresh reports configuration separately from effective policy");
                require(details.getVisibility() == View.GONE, "refresh preserves collapsed details"); tag("diagnostic-toggle").performClick();
                ((ScrollView) scroll).scrollTo(0, 80);
            });
            SystemClock.sleep(180); test.waitForIdleSync();
            int position = ((ScrollView) scroll).getScrollY();
            main(() -> {
                refresh.performClick(); require(tag("diagnostic-details") == details && tag("diagnostic-refresh") == refresh && tag("settings-scroll") == scroll, "refresh reuses the current page");
                require(details.getVisibility() == View.VISIBLE, "refresh preserves expanded details");
                CoverApp.launcher(activity).diagnostics.begin("diagnostic-copy-fixture"); tag("diagnostic-copy").performClick();
                String copied = activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString();
                require(copied.equals(report()) && copied.contains("diagnostic-copy-fixture"), "clipboard and displayed report match, including latest launch events");
                textFits(tag("diagnostic-actions"));
            });
            SystemClock.sleep(180); test.waitForIdleSync(); require(((ScrollView) scroll).getScrollY() == position, "refresh retains scroll position");
            Bitmap image = test.getUiAutomation().takeScreenshot(); require(image != null, "settings screenshot available"); File directory = new File(activity.getFilesDir(), "diagnostics-checks"); directory.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(directory, "refresh-" + rotation + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { image.recycle(); }
            main(() -> tag("diagnostic-probe").performClick()); require(fake.entered.await(3, TimeUnit.SECONDS), "controlled probe entered");
            main(() -> { prefs.data.edit().putBoolean("panel_blur", blur).commit(); refresh.performClick(); require(report().contains("面板模糊开关：" + blur), "manual refresh wins while Shizuku is pending"); });
            fake.release.countDown(); waitFor(() -> tag("diagnostic-probe").isEnabled()); require(!report().contains("diagnostic-fixture-1"), "late old probe cannot overwrite refreshed log");
            main(() -> tag("diagnostic-probe").performClick()); waitFor(() -> tag("diagnostic-probe").isEnabled()); require(report().contains("diagnostic-fixture-2"), "current Shizuku result remains available");
            main(() -> { tag("diagnostic-copy").performClick(); require(activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString().equals(report()), "copy preserves current Shizuku supplement"); refresh.performClick(); require(!report().contains("diagnostic-fixture-2"), "refresh clears stale supplemental result"); });
            remote.set(bridge, null); main(() -> tag("diagnostic-probe").performClick()); require(tag("diagnostic-probe").isEnabled(), "missing Shizuku leaves retry usable");
            return "PASS: " + assertions + " diagnostic refresh checks; live sampling, clipboard parity, scroll/visibility, stale callback rejection and unavailable Shizuku; rotation=" + rotation;
        } finally {
            fake.release.countDown(); waitFor(() -> !bridge.busy()); remote.set(bridge, originalRemote);
            main(() -> { CoverService.status = status; if (hadBlur) prefs.data.edit().putBoolean("panel_blur", blur).commit(); else prefs.data.edit().remove("panel_blur").commit(); activity.finish(); });
        }
    }
    private static final class FakeService extends IShellService.Stub {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        int calls;
        @Override public String execute(String operation, int display, int value, String component) {
            if (!operation.equals("diagnostics")) throw new AssertionError("Unexpected operation: " + operation);
            int call = ++calls;
            if (call == 1) { entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("probe timeout"); } catch (InterruptedException error) { throw new AssertionError(error); } }
            return "{\"ok\":true,\"message\":\"只读检测完成\",\"output\":\"diagnostic-fixture-" + call + "\"}";
        }
        @Override public Bundle taskSnapshot(int display, String task) { return null; }
        @Override public void watchConnectivity(IConnectivityListener listener) { }
        @Override public void watchSwitches(IConnectivityListener listener) { }
        @Override public void destroy() { }
    }
}
