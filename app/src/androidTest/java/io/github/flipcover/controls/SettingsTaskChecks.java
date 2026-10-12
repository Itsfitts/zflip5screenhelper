package io.github.flipcover.controls;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.ArrayList;

/** Real task reuse and new-intent delivery on disposable emulator displays. */
final class SettingsTaskChecks {
    private final Instrumentation test;
    private int assertions;
    SettingsTaskChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private Object field(MainActivity activity, String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void await(MainActivity activity, String page) {
        long deadline = SystemClock.uptimeMillis() + 4000;
        boolean[] ready = {false};
        do { main(() -> ready[0] = page.equals(field(activity, "route"))); if (ready[0]) break; SystemClock.sleep(40); } while (SystemClock.uptimeMillis() < deadline);
        require(ready[0] && !activity.isDestroyed(), "existing activity receives page " + page);
    }
    private Intent request(String page) { return new Intent(test.getTargetContext(), MainActivity.class).putExtra("section", page); }
    private MainActivity create(int display, String page) {
        Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
        try {
            main(() -> MainActivity.startOnDisplay(test.getTargetContext(), request(page), display));
            MainActivity activity = (MainActivity) test.waitForMonitorWithTimeout(monitor, 5000);
            require(activity != null, "settings created on isolated display"); return activity;
        } finally { test.removeMonitor(monitor); }
    }
    private long tasks(int display) {
        String identity = "flipcover-settings://display/" + display;
        return test.getTargetContext().getSystemService(ActivityManager.class).getAppTasks().stream().map(ActivityManager.AppTask::getTaskInfo).filter(info -> info != null && identity.equals(info.baseIntent.getDataString())).count();
    }
    String run() throws Exception {
        Context context = test.getTargetContext();
        // AOSP's system-only TRUSTED flag (1 << 10) is restricted to this emulator fixture.
        test.getUiAutomation().adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY");
        ImageReader surface = ImageReader.newInstance(748, 720, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay cover = context.getSystemService(DisplayManager.class).createVirtualDisplay("settings-task-cover", 748, 720, 340, surface.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 10));
        ImageReader secondSurface = ImageReader.newInstance(748, 720, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay second = context.getSystemService(DisplayManager.class).createVirtualDisplay("settings-task-other", 748, 720, 340, secondSurface.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 10));
        ArrayList<MainActivity> opened = new ArrayList<>();
        try {
            MainActivity inner = (MainActivity) test.startActivitySync(request("main").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)); opened.add(inner);
            int innerTask = inner.getTaskId(), display = cover.getDisplay().getDisplayId();
            MainActivity activity = create(display, "dock"); opened.add(activity); int task = activity.getTaskId(); await(activity, "dock");
            for (String page : new String[]{"dock", "status", "dock", "hub", "dock"}) {
                main(() -> MainActivity.startOnDisplay(context, request(page), display)); await(activity, page);
                require(activity.getTaskId() == task && tasks(display) == 1, "repeated entry keeps one task and activity");
            }
            main(() -> MainActivity.startOnDisplay(context, request("app_display_size").putExtra("orientation_package", context.getPackageName()), display)); await(activity, "app_display_size");
            main(() -> require(context.getPackageName().equals(field(activity, "orientationPackage")), "package argument delivered"));
            main(() -> MainActivity.startOnDisplay(context, request("app_display_size").putExtra("orientation_package", "com.android.settings"), display));
            SystemClock.sleep(250); test.waitForIdleSync();
            main(() -> require("com.android.settings".equals(field(activity, "orientationPackage")), "same page accepts a different package"));
            main(() -> ((android.widget.EditText) activity.findViewById(android.R.id.content).findViewWithTag("display-dpi")).setText("721"));
            main(() -> MainActivity.startOnDisplay(context, request("status"), display)); SystemClock.sleep(250); test.waitForIdleSync();
            main(() -> {
                require(((AppDisplaySettings) field(activity, "appDisplaySettings")).hasDraft(), "display size draft waits for confirmation");
                activity.findViewById(android.R.id.content).findViewWithTag("settings-confirm").performClick();
            }); await(activity, "status");
            main(() -> activity.findViewById(android.R.id.content).findViewWithTag("settings-back").performClick()); await(activity, "app_display_size");
            main(() -> require(!((AppDisplaySettings) field(activity, "appDisplaySettings")).hasDraft(), "back never resurrects a discarded display size draft"));
            main(() -> MainActivity.startOnDisplay(context, request("order"), display)); await(activity, "order");
            main(() -> { @SuppressWarnings("unchecked") ArrayList<String> draft = (ArrayList<String>) field(activity, "draft"); draft.remove(0); });
            main(() -> MainActivity.startOnDisplay(context, request("status"), display)); SystemClock.sleep(250); test.waitForIdleSync();
            main(() -> {
                require("order".equals(field(activity, "route")) && field(activity, "sheet") != null, "new route protects unsaved draft");
                activity.onBackPressed(); require(field(activity, "draft") != null, "cancel retains draft");
            });
            main(() -> MainActivity.startOnDisplay(context, request("status"), display)); SystemClock.sleep(250); test.waitForIdleSync();
            main(() -> activity.findViewById(android.R.id.content).findViewWithTag("settings-confirm").performClick()); await(activity, "status");
            main(() -> require(field(activity, "draft") == null, "confirmed navigation discards only the draft"));
            MainActivity other = create(second.getDisplay().getDisplayId(), "hub"); opened.add(other);
            require(other.getTaskId() != task && other.getDisplay().getDisplayId() == second.getDisplay().getDisplayId(), "other display has a separate task");
            main(() -> MainActivity.startOnDisplay(context, request("dock"), display)); await(activity, "dock");
            require(inner.getTaskId() == innerTask && inner.getDisplay().getDisplayId() == 0 && !inner.isDestroyed(), "inner settings stay on inner display");
            require(activity.getDisplay().getDisplayId() == display && tasks(display) == 1, "cover reuse retains requested display");
            main(() -> { try { MainActivity.startOnDisplay(context, request("dock"), 0); throw new AssertionError("main display accepted"); } catch (IllegalArgumentException expected) { require(true, "cover entry rejects main display"); } });
            return "PASS " + assertions + " settings task reuse checks";
        } finally {
            main(() -> { for (MainActivity activity : opened) if (!activity.isDestroyed()) activity.finishAndRemoveTask(); });
            if (cover != null) cover.release(); if (second != null) second.release(); surface.close(); secondSurface.close();
            test.getUiAutomation().dropShellPermissionIdentity();
        }
    }
}
