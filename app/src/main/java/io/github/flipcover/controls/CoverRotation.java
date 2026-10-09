package io.github.flipcover.controls;

import android.app.KeyguardManager;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.OrientationEventListener;

/** One in-memory rotation owner. Calibrate to the current cover posture when enabled. */
final class CoverRotation {
    private final CoverService owner;
    private final OrientationEventListener sensor;
    private boolean enabled, busy;
    private Runnable afterFlight;
    private int displayId, reference = -1, referenceRotation, candidate = -1;
    private int generation;
    private final Runnable apply = this::applyCandidate;
    CoverRotation(CoverService owner) {
        this.owner = owner;
        sensor = new OrientationEventListener(owner, SensorManager.SENSOR_DELAY_NORMAL) { @Override public void onOrientationChanged(int angle) { orientation(angle); } };
    }
    boolean enabled() { return enabled; }
    boolean start() {
        if (owner.display == null || owner.display.getDisplayId() <= 0 || !sensor.canDetectOrientation() || !CoverApp.bridge(owner).granted()) return false;
        stop(); enabled = true; displayId = owner.display.getDisplayId(); referenceRotation = owner.display.getRotation(); sensor.enable(); owner.rotationStateChanged(); return true;
    }
    void stop() { boolean changed = enabled; generation++; enabled = false; afterFlight = null; reference = candidate = -1; owner.main.removeCallbacks(apply); sensor.disable(); if (changed) owner.rotationStateChanged(); }
    static int systemMode(boolean ok, String output) { if (!ok || output == null) return -1; String state = output.trim(); return state.equals("free") ? 0 : state.matches("lock [0-3]") ? 1 : -1; }
    static String label(boolean automatic, int mode) { return automatic ? "工具自动旋转" : mode == 1 ? "旋转锁定" : mode == 0 ? "系统旋转" : "旋转状态未知"; }
    void afterCurrent(Runnable action) { if (busy) afterFlight = action; else action.run(); }
    boolean valid() {
        Display selected = Displays.selected(owner, owner.prefs);
        return enabled && owner.prefs.enabled() && selected != null && selected.getDisplayId() == displayId && selected.getState() == Display.STATE_ON && !owner.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    void checkActive() { if (enabled && !valid()) stop(); }
    static int target(int reference, int angle, int rotation) {
        if (angle < 0 || reference < 0) return -1;
        int delta = (reference - angle + 540) % 360 - 180, quarter = Math.round(delta / 90f);
        return Math.abs(delta - quarter * 90) > 25 ? -1 : (rotation + quarter + 4) % 4;
    }
    private void orientation(int angle) {
        if (!valid()) { stop(); return; }
        if (angle < 0) { candidate = -1; owner.main.removeCallbacks(apply); return; }
        if (reference < 0) { reference = angle; return; }
        int next = target(reference, angle, referenceRotation);
        if (next == candidate) return;
        owner.main.removeCallbacks(apply); candidate = next;
        if (next >= 0 && !busy && owner.display.getRotation() != next) owner.main.postDelayed(apply, 280);
    }
    private void applyCandidate() {
        if (!valid() || candidate < 0 || busy) return;
        int version = generation, angle = candidate; busy = true;
        CoverApp.bridge(owner).run("rotation", displayId, angle, "", result -> {
            busy = false;
            if (version != generation) { Runnable next = afterFlight; afterFlight = null; if (next != null && CoverService.instance == owner) next.run(); else if (valid() && candidate >= 0) owner.main.postDelayed(apply, 280); return; }
            if (!valid()) { stop(); return; }
            if (!result.ok) { stop(); owner.controlStatesChanged(); owner.message("自动旋转已暂停：" + result.message); return; }
            owner.rotationConfirmed(result);
            if (candidate >= 0 && candidate != angle) owner.main.postDelayed(apply, 280);
        });
    }
}
