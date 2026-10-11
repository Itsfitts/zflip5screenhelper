package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Real child dispatch must finish with one owner and no accidental click/value commit. */
final class PanelSwipeChecks {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void touch(View host, long at, long time, int action, float x, float y) { MotionEvent event = MotionEvent.obtain(at, at + time, action, x, y, 0); host.dispatchTouchEvent(event); event.recycle(); }
    static int run(Instrumentation test, Activity activity) {
        int[] assertions = {0};
        for (String kind : new String[]{"button", "slider", "scroll", "notification", "horizontal-slider"}) {
            PanelHost[] root = {null}; PanelSurface[] page = {null}; int[] begins = {0}, finishes = {0}, clicks = {0}, commits = {0};
            test.runOnMainSync(() -> {
                page[0] = new PanelSurface(activity, DockGeometry.BOTTOM, 900, false, new PanelHeaderView.Listener() { public void begin() { } public void progress(float value) { } public void finish(boolean close) { } });
                root[0] = new PanelHost(activity, new PanelHost.SwipeListener() {
                    public View currentPage() { return page[0]; } public int direction() { return -1; }
                    public boolean begin(View source, int direction) { begins[0]++; return true; }
                    public void progress(float distance) { }
                    public void finish(float distance, float speed, boolean canceled) { finishes[0]++; }
                });
                View child;
                if (kind.equals("slider")) child = new LevelSlider(activity, "Fixture", R.drawable.ic_ms_volume_up, 0, 100, 50, value -> commits[0]++);
                else if (kind.equals("horizontal-slider")) child = new android.widget.SeekBar(activity);
                else if (kind.equals("notification")) { View face = new View(activity); face.setOnClickListener(v -> clicks[0]++); child = new NotificationSwipeRow(activity, face, new LinearLayout(activity), () -> { }); }
                else if (kind.equals("scroll")) { ControlScrollView scroll = new ControlScrollView(activity); View tall = new View(activity); tall.setOnClickListener(v -> clicks[0]++); scroll.addView(tall, new FrameLayout.LayoutParams(-1, 2400)); child = scroll; }
                else { child = new View(activity); child.setOnClickListener(v -> clicks[0]++); }
                page[0].addView(child, new LinearLayout.LayoutParams(-1, -1)); root[0].addView(page[0]); activity.setContentView(root[0]);
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                float x = root[0].getWidth() * .8f, y = root[0].getHeight() * .5f, travel = root[0].getWidth() * .5f; long at = SystemClock.uptimeMillis();
                touch(root[0], at, 0, MotionEvent.ACTION_DOWN, x, y); require(begins[0] == 0, kind + " DOWN must not create page"); assertions[0]++;
                touch(root[0], at, 50, MotionEvent.ACTION_MOVE, x - travel, y); touch(root[0], at, 100, MotionEvent.ACTION_UP, x - travel, y);
                boolean ownsHorizontal = kind.equals("notification") || kind.equals("horizontal-slider");
                require(begins[0] == (ownsHorizontal ? 0 : 1) && finishes[0] == begins[0], kind + " axis ownership"); assertions[0]++;
                require(clicks[0] == 0 && commits[0] == 0, kind + " horizontal drag must not click or commit vertical level"); assertions[0]++;
                int previous = begins[0]; at = SystemClock.uptimeMillis();
                touch(root[0], at, 0, MotionEvent.ACTION_DOWN, x, y); touch(root[0], at, 30, MotionEvent.ACTION_MOVE, x, y - 80); touch(root[0], at, 80, MotionEvent.ACTION_MOVE, x - travel, y - 80); touch(root[0], at, 120, MotionEvent.ACTION_CANCEL, x - travel, y - 80);
                require(begins[0] == previous, kind + " vertical owner cannot be stolen later"); assertions[0]++;
                if (!ownsHorizontal) {
                    at = SystemClock.uptimeMillis(); touch(root[0], at, 0, MotionEvent.ACTION_DOWN, x, y); touch(root[0], at, 30, MotionEvent.ACTION_MOVE, x - travel, y); touch(root[0], at, 50, MotionEvent.ACTION_POINTER_DOWN, x - travel, y); touch(root[0], at, 70, MotionEvent.ACTION_UP, x - travel, y);
                    require(begins[0] == previous + 1 && finishes[0] == begins[0] && !root[0].swiping(), kind + " multipointer cancels once"); assertions[0]++;
                }
                root[0].abortSwipe(); activity.setContentView(new FrameLayout(activity));
            });
            test.waitForIdleSync();
        }
        return assertions[0];
    }
}
