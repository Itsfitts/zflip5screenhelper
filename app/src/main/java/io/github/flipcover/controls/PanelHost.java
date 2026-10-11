package io.github.flipcover.controls;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsSeekBar;
import android.widget.EditText;

/** The persistent window owns a horizontal sequence while its two page cards move. */
final class PanelHost extends InputSurface {
    interface SwipeListener {
        View currentPage();
        int direction();
        boolean begin(View source, int direction);
        void progress(float distance);
        void finish(float distance, float speed, boolean canceled);
    }
    private final SwipeListener swipe;
    private final float slop;
    private final int maximumVelocity;
    private View source;
    private VelocityTracker velocity;
    private float startX, startY, distance;
    private int direction;
    private boolean candidate, following, blocked;

    PanelHost(Context context, SwipeListener swipe) {
        super(context); this.swipe = swipe;
        ViewConfiguration config = ViewConfiguration.get(context); slop = config.getScaledTouchSlop(); maximumVelocity = config.getScaledMaximumFlingVelocity();
        setMotionEventSplittingEnabled(false);
    }
    boolean swiping() { return following; }
    void abortSwipe() { reset(); blocked = true; }
    private void reset() {
        candidate = following = false; source = null; distance = 0;
        if (velocity != null) velocity.recycle(); velocity = null;
    }
    private boolean horizontalOwner(View view, float x, float y) {
        // Single-line labels/buttons report a scroll range even though they do not own a horizontal gesture.
        if (view instanceof NotificationSwipeRow || view instanceof AbsSeekBar || view instanceof GlassToggle || view instanceof OriginalLiquidTabs || view instanceof EditText || view instanceof ControlEditorView || view instanceof DetailSheet || view instanceof android.widget.HorizontalScrollView || view instanceof ViewGroup && (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1))) return true;
        if (view instanceof ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i); float cx = x + group.getScrollX() - child.getX(), cy = y + group.getScrollY() - child.getY();
            if (child.getVisibility() == VISIBLE && cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight()) return horizontalOwner(child, cx, cy);
        }
        return false;
    }
    private void cancelChildren(MotionEvent event) { MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (following) end(true);
            reset(); blocked = false; source = swipe.currentPage(); direction = swipe.direction();
            startX = event.getRawX(); startY = event.getRawY();
            candidate = source != null && direction != 0 && !horizontalOwner(this, event.getX(), event.getY());
            if (candidate) { velocity = VelocityTracker.obtain(); velocity.addMovement(event); }
        }
        if (blocked) return true;
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            boolean active = following; cancelChildren(event); end(true); blocked = true; return active || action != MotionEvent.ACTION_CANCEL;
        }
        if (velocity != null && action != MotionEvent.ACTION_DOWN) velocity.addMovement(event);
        if (candidate && !following && action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
            if (swipe.currentPage() != source || Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) reset();
            else if (dx * direction > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                // Cancel the slider/button before constructing the destination. No click or value commit escapes.
                cancelChildren(event); following = true;
                if (!swipe.begin(source, direction)) { abortSwipe(); return true; }
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        if (following) {
            distance = Math.max(0, (event.getRawX() - startX) * direction);
            swipe.progress(distance);
            if (action == MotionEvent.ACTION_UP) end(false);
            return true;
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP) reset();
        return handled;
    }
    private void end(boolean canceled) {
        boolean active = following; float traveled = distance, speed = 0;
        if (velocity != null) { velocity.computeCurrentVelocity(1000, maximumVelocity); speed = velocity.getXVelocity() * direction; }
        reset(); if (active) swipe.finish(traveled, speed, canceled);
    }
    private void cancelSequence() {
        if (!candidate && !following) return;
        long now = android.os.SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0); cancelChildren(cancel); cancel.recycle(); end(true); blocked = true;
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (!focused) cancelSequence(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) cancelSequence(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (w != oldw || h != oldh) cancelSequence(); }
    @Override protected void onDetachedFromWindow() { abortSwipe(); super.onDetachedFromWindow(); }
}
