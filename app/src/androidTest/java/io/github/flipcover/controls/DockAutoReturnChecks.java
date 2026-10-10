package io.github.flipcover.controls;

import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.Switch;

/** Real delayed callbacks and gesture/lifecycle cancellation on a disposable emulator. */
final class DockAutoReturnChecks {
    private final UiSmokeInstrumentation test;
    private MainActivity activity;
    private Prefs prefs;
    private FrameLayout root;
    private DockView dock;
    private int assertions;
    private long downTime;
    DockAutoReturnChecks(UiSmokeInstrumentation test) { this.test = test; }
    String run() {
        prefs = new Prefs(test.getTargetContext());
        java.util.Map<String, ?> original = new java.util.HashMap<>(prefs.data.getAll());
        try {
            prefs.data.edit().remove("dock_auto_return_first_page").commit();
            activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "dock"));
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                Switch control = activity.findViewById(android.R.id.content).findViewWithTag("dock_auto_return_first_page");
                View scroll = activity.findViewById(android.R.id.content).findViewWithTag("settings-scroll");
                require(control != null && control.isChecked() && prefs.dockAutoReturnFirstPage(), "new and existing users default to enabled");
                control.performClick(); require(!prefs.dockAutoReturnFirstPage(), "settings disables auto return");
                control.performClick(); require(prefs.dockAutoReturnFirstPage(), "settings enables auto return");
                require(scroll == activity.findViewById(android.R.id.content).findViewWithTag("settings-scroll"), "toggle preserves scroll container");
                prefs.data.edit().putString("dock", "[\"home\",\"back\",\"torch\",\"screenshot\",\"configure\",\"recents\"]").putInt("per_page", 3).putString("pinned_action", "app_hub").commit();
                root = new FrameLayout(activity); activity.setContentView(root);
                DockGeometry.Box box = new DockGeometry.Box(0, 0, 320, 60);
                dock = new DockView(activity, prefs, new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, false), 1, new DockView.Listener() { public void action(String id) { } public void configure() { } });
                root.addView(dock, new FrameLayout.LayoutParams(320, 60));
            });
            waitMs(500); page(1, "restored page stays visible before deadline");
            waitDeadline(); page(0, "idle callback returns to first page");
            next(); waitMs(3000); next(); waitMs(2500); page(2, "new navigation replaces old deadline");
            waitMs(2800); page(0, "latest navigation deadline returns home");
            next(); touch(MotionEvent.ACTION_DOWN); waitDeadline(); page(1, "held long press never jumps pages");
            touch(MotionEvent.ACTION_UP); waitDeadline(); page(0, "release starts a fresh idle deadline");
            next(); test.runOnMainSync(() -> { prefs.data.edit().putBoolean("dock_auto_return_first_page", false).commit(); dock.scheduleAutoReturn(); });
            waitDeadline(); page(1, "disabled preference cancels pending callback");
            test.runOnMainSync(() -> { prefs.data.edit().putBoolean("dock_auto_return_first_page", true).commit(); dock.scheduleAutoReturn(); });
            waitDeadline(); page(0, "enabling resumes timer for current page");
            next(); test.runOnMainSync(() -> dock.compact(true)); waitDeadline(); page(1, "collapsed dock has no running return");
            test.runOnMainSync(() -> dock.compact(false)); waitDeadline(); page(0, "expanded dock gets a fresh deadline");
            next(); test.runOnMainSync(() -> root.removeView(dock)); waitDeadline(); page(1, "detached dock cannot be changed by stale callback");
            return "PASS: dock-auto-return; " + assertions + " assertions; idle, settings, gestures, visibility and detach";
        } finally {
            if (activity != null) test.runOnMainSync(activity::finish);
            test.waitForIdleSync();
            android.content.SharedPreferences.Editor restore = prefs.data.edit();
            for (String key : new String[]{"dock_auto_return_first_page", "dock", "per_page", "pinned_action"}) {
                Object value = original.get(key);
                if (value instanceof Boolean item) restore.putBoolean(key, item);
                else if (value instanceof Integer item) restore.putInt(key, item);
                else if (value instanceof String item) restore.putString(key, item);
                else restore.remove(key);
            }
            restore.commit();
        }
    }
    private void next() { test.runOnMainSync(() -> require(dock.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null), "navigate to next page")); }
    private void page(int expected, String reason) { test.runOnMainSync(() -> require(dock.page() == expected, reason + "; actual=" + dock.page())); }
    private void waitDeadline() { waitMs(BuildConfig.MOTION_DOCK_AUTO_RETURN_DELAY_MS + 300); }
    private void waitMs(long delay) { SystemClock.sleep(delay); test.waitForIdleSync(); }
    private void touch(int action) {
        test.runOnMainSync(() -> {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 45, 30, 0);
            try { dock.dispatchTouchEvent(event); } finally { event.recycle(); }
        });
    }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
}
