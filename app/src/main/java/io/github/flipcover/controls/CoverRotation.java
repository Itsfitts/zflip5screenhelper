package io.github.flipcover.controls;

import android.app.KeyguardManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.OrientationEventListener;
import java.util.function.Function;
import java.util.function.IntConsumer;

/** One in-memory rotation owner. Calibrate to the current cover posture when enabled. */
final class CoverRotation {
    static final int SAMPLE_INTERVAL_MS = 2000;
    private static final int MAX_BUSY_RETRIES = 3;
    private final CoverService owner;
    private final Function<IntConsumer, OrientationEventListener> sensorFactory;
    private OrientationEventListener sensor;
    private volatile boolean enabled, suspended;
    private boolean busy;
    private Runnable afterFlight;
    private int displayId, reference = -1, referenceRotation, candidate = -1;
    private int pendingAngle = -1, busyRetries;
    private long lastSampleAt = -1;
    private volatile int generation;
    private final Runnable apply = this::applyCandidate;
    private final Runnable sample = () -> { if (!active()) return; lastSampleAt = SystemClock.uptimeMillis(); orientation(pendingAngle); };
    CoverRotation(CoverService owner) {
        this(owner, callback -> new OrientationEventListener(owner, SAMPLE_INTERVAL_MS * 1000) { @Override public void onOrientationChanged(int angle) { callback.accept(angle); } });
    }
    CoverRotation(CoverService owner, Function<IntConsumer, OrientationEventListener> sensorFactory) { this.owner = owner; this.sensorFactory = sensorFactory; sensor = createSensor(); }
    private OrientationEventListener createSensor() {
        int version = generation;
        return sensorFactory.apply(angle -> {
            if (version != generation || !active()) return;
            pendingAngle = angle;
            if (owner.main.hasCallbacks(sample)) return;
            long delay = lastSampleAt < 0 ? 0 : Math.max(0, lastSampleAt + SAMPLE_INTERVAL_MS - SystemClock.uptimeMillis());
            if (delay == 0) sample.run(); else owner.main.postDelayed(sample, delay);
        });
    }
    private void listen() { sensor = createSensor(); lastSampleAt = -1; sensor.enable(); }
    private boolean active() { return enabled && !suspended && CoverService.instance == owner; }
    boolean enabled() { return enabled; }
    boolean start() {
        if (owner.display == null || owner.display.getDisplayId() <= 0 || !sensor.canDetectOrientation() || !CoverApp.bridge(owner).granted()) return false;
        stop(); enabled = true; displayId = owner.display.getDisplayId(); referenceRotation = owner.display.getRotation(); listen(); owner.rotationStateChanged(); return true;
    }
    void stop() { boolean changed = enabled; enabled = suspended = false; generation++; afterFlight = null; reference = candidate = pendingAngle = -1; busyRetries = 0; owner.main.removeCallbacks(apply); owner.main.removeCallbacks(sample); sensor.disable(); if (changed) owner.rotationStateChanged(); }
    void suspend() {
        afterFlight = null;
        if (!enabled || suspended) return;
        suspended = true; generation++; candidate = pendingAngle = -1; busyRetries = 0; owner.main.removeCallbacks(apply); owner.main.removeCallbacks(sample); sensor.disable();
    }
    static int systemMode(boolean ok, String output) { if (!ok || output == null) return -1; String state = output.trim(); return state.equals("free") ? 0 : state.matches("lock [0-3]") ? 1 : -1; }
    static String label(boolean automatic, int mode) { return automatic ? "工具自动旋转" : mode == 1 ? "旋转锁定" : mode == 0 ? "系统旋转" : "旋转状态未知"; }
    void afterCurrent(Runnable action) { if (busy) afterFlight = action; else action.run(); }
    boolean valid() {
        if (!active()) return false;
        Display selected = Displays.selected(owner, owner.prefs);
        return owner.prefs.enabled() && selected != null && selected.getDisplayId() == displayId && selected.getState() == Display.STATE_ON && !owner.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    void checkActive() {
        if (!enabled) return;
        Display selected = Displays.selected(owner, owner.prefs);
        if (CoverService.instance != owner || !owner.prefs.enabled() || selected == null || selected.getDisplayId() != displayId) { stop(); return; }
        if (selected.getState() != Display.STATE_ON || owner.getSystemService(KeyguardManager.class).isKeyguardLocked()) { suspend(); return; }
        // Resume the user's mode with its original posture calibration, using fresh sensor input.
        if (suspended) { suspended = false; listen(); }
    }
    static int target(int reference, int angle, int rotation) {
        if (angle < 0 || reference < 0) return -1;
        int delta = (reference - angle + 540) % 360 - 180, quarter = Math.round(delta / 90f);
        return Math.abs(delta - quarter * 90) > 25 ? -1 : (rotation + quarter + 4) % 4;
    }
    private void orientation(int angle) {
        // Display discovery/keyguard checks belong to lifecycle events and command submission.
        if (!active()) return;
        if (angle < 0) { candidate = -1; owner.main.removeCallbacks(apply); return; }
        if (reference < 0) { reference = angle; return; }
        int next = target(reference, angle, referenceRotation);
        if (next == candidate) return;
        owner.main.removeCallbacks(apply); candidate = next; busyRetries = 0;
        if (next >= 0 && !busy && owner.display.getRotation() != next) owner.main.postDelayed(apply, 280);
    }
    private void applyCandidate() {
        if (!valid()) { if (!suspended) checkActive(); return; }
        if (candidate < 0 || busy) return;
        int version = generation, angle = candidate; busy = true;
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        CoverApp.bridge(owner).run("rotation", displayId, angle, "", () -> { boolean active = version == generation && valid(); cancelled.set(!active); return active; }, result -> {
            busy = false;
            if (version != generation) { Runnable next = afterFlight; afterFlight = null; if (next != null && CoverService.instance == owner) next.run(); else if (valid() && candidate >= 0) owner.main.postDelayed(apply, 280); return; }
            // The worker can observe sleep before the main-thread display event, then deliver after wake.
            if (cancelled.get()) { suspend(); checkActive(); return; }
            if (!valid()) { checkActive(); return; }
            if (result.retryable) {
                if (candidate >= 0 && busyRetries < MAX_BUSY_RETRIES) { busyRetries++; owner.main.postDelayed(apply, SAMPLE_INTERVAL_MS); }
                return;
            }
            if (!result.ok) { stop(); owner.controlStatesChanged(); owner.message("自动旋转已暂停：" + result.message); return; }
            busyRetries = 0;
            owner.rotationConfirmed(result);
            if (candidate >= 0 && candidate != angle) owner.main.postDelayed(apply, 280);
        });
    }
}
