package io.github.flipcover.controls;

import android.view.Display;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Service-owned calibration survives Activity rotation. No preferences change until Save. */
final class SafeAreaCalibration {
    private final CoverService owner;
    private final List<Runnable> observers = new ArrayList<>();
    private int rotation, displayId = -1, generation, edge = -1;
    private String identity = "", message = "";
    private boolean active, editing, restoring, inFlight;
    private SafeAreaGeometry.Profile draft;
    private long deadline;
    private final Runnable awaitLayout = this::awaitLayout;
    SafeAreaCalibration(CoverService owner) { this.owner = owner; }
    boolean active() { return active || restoring; }
    boolean blocksRotation() { return active() || !owner.prefs.data.getString("safe_area_rotation_restore", "").isEmpty(); }
    boolean editing() { return editing; }
    int rotation() { return rotation; }
    int selectedEdge() { return edge; }
    String message() { return message; }
    SafeAreaGeometry.Profile draft() { return draft; }
    void observe(Runnable callback) { if (!observers.contains(callback)) observers.add(callback); }
    void unobserve(Runnable callback) { observers.remove(callback); }
    private void changed(String value) { message = value; for (Runnable callback : List.copyOf(observers)) callback.run(); }
    private boolean sameDisplay() { Display d = Displays.selected(owner, owner.prefs); return d != null && d.getDisplayId() == displayId && SafeAreaManager.identity(d).equals(identity); }
    SafeAreaGeometry.Profile preview(Display display) { return editing && draft != null && SafeAreaManager.rotation(display) == rotation && sameDisplay() ? draft : null; }
    void begin(int angle) {
        if (active() || CoverService.instance != owner || angle < 0 || angle > 3) return;
        Display target = Displays.selected(owner, owner.prefs);
        if (target == null || target.getState() != Display.STATE_ON || owner.safeAreaSnapshot() == null) { changed("请先开启助手并点亮所选外屏"); return; }
        if (!owner.prefs.data.getString("safe_area_rotation_restore", "").isEmpty()) { recover(); return; }
        rotation = angle; displayId = target.getDisplayId(); identity = SafeAreaManager.identity(target); active = true; editing = false; draft = null; edge = -1;
        int version = ++generation; changed("正在准备校准…");
        // Even a matching direction needs a temporary lock if the system/sensor can rotate it.
        if (!CoverApp.bridge(owner).connected()) { active = false; changed("校准需要 Shizuku 连接，以保存并恢复外屏旋转设置"); return; }
        owner.suspendRotationForCalibration(() -> {
            if (!active || version != generation || !sameDisplay()) return;
            inFlight = true;
            CoverApp.bridge(owner).run("rotation_read", displayId, 0, "", () -> active && version == generation && sameDisplay(), result -> {
                inFlight = false;
                if (!active || version != generation) { recover(); return; }
                if (!result.ok) { finish(false); changed(result.message); return; }
                try {
                    String journal = new JSONObject().put("display", displayId).put("identity", identity).put("automatic", owner.rotationAutomatic()).put("state", new JSONObject(result.output)).toString();
                    if (!owner.prefs.data.edit().putString("safe_area_rotation_restore", journal).commit()) throw new IllegalStateException("无法保存旋转恢复信息");
                } catch (Exception error) { finish(false); changed("无法保存旋转恢复信息，未开始校准"); return; }
                inFlight = true;
                CoverApp.bridge(owner).run("rotation", displayId, rotation, "", () -> active && version == generation && sameDisplay(), locked -> {
                    inFlight = false;
                    if (!active || version != generation) { recover(); return; }
                    if (!locked.ok) { finish(false); changed(locked.message); return; }
                    deadline = android.os.SystemClock.uptimeMillis() + 5000; awaitLayout();
                });
            });
        });
    }
    private void awaitLayout() {
        if (!active || editing) return;
        if (!sameDisplay()) { finish(false); changed("目标外屏已改变，校准已取消"); return; }
        SafeAreaManager.Snapshot area = owner.safeAreaSnapshot();
        if (area != null && area.displayId() == displayId && area.rotation() == rotation) {
            SafeAreaGeometry.Profile saved = owner.prefs.safeArea(rotation);
            SafeAreaGeometry.Edges edges = saved != null && saved.matches(identity, area.width(), area.height()) ? saved.edges() : area.automaticEdges();
            if (!edges.valid(area.width(), area.height())) edges = SafeAreaGeometry.Edges.NONE;
            SafeAreaGeometry.Edges reference = saved != null && saved.matches(identity, area.width(), area.height()) && saved.reference() != null ? saved.reference() : area.automaticEdges();
            draft = new SafeAreaGeometry.Profile(true, area.width(), area.height(), identity, edges, reference); editing = true;
            owner.refreshSafeArea(); changed("调整中 · 保存后生效，取消恢复原设置"); return;
        }
        if (android.os.SystemClock.uptimeMillis() >= deadline) { finish(false); changed("外屏没有切换到指定方向，校准已取消"); return; }
        owner.main.postDelayed(awaitLayout, 100);
    }
    boolean update(SafeAreaGeometry.Edges edges, int selectedEdge) {
        if (!editing || !edges.valid(draft.width(), draft.height())) return false;
        SafeAreaManager.Snapshot current = owner.safeAreaSnapshot();
        DockGeometry.Box frame = edges.frame(draft.width(), draft.height());
        if (current == null || frame.width() < Math.min(draft.width(), Math.round(200 * current.density())) || frame.height() < Math.min(draft.height(), Math.round(160 * current.density()))) return false;
        draft = new SafeAreaGeometry.Profile(true, draft.width(), draft.height(), draft.display(), edges, draft.reference()); edge = selectedEdge;
        owner.refreshSafeArea(); return true;
    }
    void fillAutomatic() { SafeAreaManager.Snapshot area = owner.safeAreaSnapshot(); if (editing && area != null) { if (update(area.automaticEdges(), -1)) { draft = new SafeAreaGeometry.Profile(true, draft.width(), draft.height(), draft.display(), draft.edges(), area.automaticEdges()); changed("已更新自动基准，四边微调归零，尚未保存"); } } }
    boolean save() {
        if (!editing || !sameDisplay()) return false;
        if (!owner.prefs.saveSafeArea(rotation, draft)) { changed("保存失败，请重试；原设置仍保留"); return false; }
        finish(true); return true;
    }
    void finish(boolean saved) {
        if (!active) return;
        active = editing = false; generation++; draft = null; edge = -1; owner.main.removeCallbacks(awaitLayout);
        if (CoverService.instance == owner) owner.refreshSafeArea();
        changed(saved ? "安全区已保存" : "已取消，原安全区设置未变");
        if (!inFlight) recover();
    }
    void displayChanged() {
        if (!active) return;
        Display target = Displays.selected(owner, owner.prefs);
        if (!sameDisplay() || target == null || target.getState() != Display.STATE_ON || !owner.prefs.enabled()) { finish(false); return; }
        if (editing && (SafeAreaManager.rotation(target) != rotation || !draft.matches(identity, SafeAreaManager.rawDisplaySize(target).x, SafeAreaManager.rawDisplaySize(target).y))) { finish(false); changed("外屏方向或尺寸已改变，请重新开始校准"); }
    }
    void recover() {
        if (active || restoring || inFlight) return;
        String journal = owner.prefs.data.getString("safe_area_rotation_restore", "");
        if (journal.isEmpty()) { if (CoverService.instance == owner) owner.resumeRotationAfterCalibration(); return; }
        if (!CoverApp.bridge(owner).connected()) { changed("原旋转设置尚未恢复，连接 Shizuku 后可重试恢复"); return; }
        try {
            JSONObject state = new JSONObject(journal); int id = state.getInt("display"); String expected = state.getString("identity");
            Display selected = Displays.selected(owner, owner.prefs);
            if (selected == null || selected.getDisplayId() != id || !SafeAreaManager.identity(selected).equals(expected)) { changed("原外屏不可用，旋转恢复信息已保留"); return; }
            restoring = true;
            CoverApp.bridge(owner).run("rotation_restore", id, 0, state.getJSONObject("state").toString(), () -> {
                Display current = Displays.selected(owner, owner.prefs); return current != null && current.getDisplayId() == id && SafeAreaManager.identity(current).equals(expected);
            }, result -> {
                restoring = false;
                if (result.ok && CoverService.instance != owner) { changed("系统旋转已恢复，等待助手重新连接完成恢复"); return; }
                if (result.ok) {
                    owner.prefs.data.edit().remove("safe_area_rotation_restore").commit();
                    if (!owner.restoreAutomaticRotationAfterCalibration(state.optBoolean("automatic"))) { changed("系统旋转已恢复，工具自动旋转暂不可用，请到旋转设置重新开启"); return; }
                }
                changed(result.ok ? "校准已结束，已恢复原旋转设置" : "安全区已退出，原旋转恢复失败：" + result.message + "；可重试恢复");
            });
        } catch (Exception error) { changed("旋转恢复信息不可用，请到旋转设置检查当前状态"); }
    }
}
