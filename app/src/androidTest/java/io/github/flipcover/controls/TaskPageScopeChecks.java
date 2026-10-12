package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** In-memory page fixtures: no real task is read, captured, opened or removed. */
final class TaskPageScopeChecks {
    private final Instrumentation test;
    private int assertions;
    TaskPageScopeChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private TextView innerTitle(View view) {
        if (view instanceof TextView text && text.getText().toString().endsWith("(内屏)")) return text;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = innerTitle(group.getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private int textCount(View view) {
        if (view instanceof TextView) return 1;
        int count = 0; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) count += textCount(group.getChildAt(i));
        return count;
    }
    String run() throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Prefs prefs = new Prefs(activity);
        int user = android.os.Process.myUid() / 100000;
        RecentTasks.Task inner = new RecentTasks.Task(8100, 0, user, "fixture.inner/fixture.inner.Main", "fixture.inner", true);
        RecentTasks.Task outer = new RecentTasks.Task(8101, 2, user, "fixture.outer/fixture.outer.Main", "fixture.outer", false);
        List<RecentTasks.Task> source = List.of(inner, outer), opened = new ArrayList<>(), previews = new ArrayList<>();
        JSONArray encoded = new JSONArray(AppRecentTasks.encode(source));
        require(AppRecentTasks.tasks(encoded, 2, true).equals(source), "page decoding preserves mixed display identities and order");
        boolean rejected = false; try { AppRecentTasks.tasks(encoded, 2); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "Dock decoder rejects mixed display payload");
        JSONArray many = new JSONArray();
        for (int i = 0; i < RecentTasks.TASK_LIMIT; i++) many.put(SystemRecentTasks.json(new RecentTasks.Task(i, i % 2 == 0 ? 0 : 2, user, "fixture/.Main", "fixture", false)));
        require(AppRecentTasks.tasks(many, 2, true).size() == 100, "task page accepts more than 32 tasks up to its bound");
        many.put(SystemRecentTasks.json(new RecentTasks.Task(100, 2, user, "fixture/.Main", "fixture", false)));
        rejected = false; try { AppRecentTasks.tasks(many, 2, true); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "oversized task response rejected");
        for (JSONObject invalid : List.of(SystemRecentTasks.json(inner).put("displayId", 3), SystemRecentTasks.json(inner).put("userId", user + 1))) {
            rejected = false; try { AppRecentTasks.task(invalid, 2, true); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "foreign user or secondary display rejected");
        }
        RecentTasksView[] page = {null};
        try {
            test.runOnMainSync(() -> {
                page[0] = new RecentTasksView(activity, prefs, DockGeometry.BOTTOM, new RecentTasksView.Listener() {
                    public void open(RecentTasks.Task task) { opened.add(task); }
                    public void clear(List<RecentTasks.Task> tasks) { }
                    public void refresh() { }
                    public void apps() { }
                    public void close() { }
                    public void reopen(RecentTasks.Task task) { }
                    public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { previews.add(task); callback.accept(new ShizukuBridge.Snapshot(null, "fixture")); }
                });
                activity.setContentView(page[0]); page[0].data(source, true, true, false, true, true, true, "左右切换");
                page[0].previewsVisible();
            });
            SystemClock.sleep(700); test.waitForIdleSync();
            Throwable[] failure = {null};
            test.runOnMainSync(() -> { try {
                TextView title = innerTitle(page[0]); require(title != null, "inner card app name carries the source label");
                require(title.getText() instanceof Spanned && ((Spanned) title.getText()).getSpans(0, title.length(), RelativeSizeSpan.class).length == 1, "inner suffix uses smaller text");
                require(previews.stream().noneMatch(task -> task.displayId() <= 0), "inner card never requests a preview");
                require(previews.contains(outer), "outer card continues requesting its preview");
                for (RecentTasks.Task task : source) require(textCount(page[0].findViewWithTag("task-card:" + task.id())) == 1, "inner and outer cards show only the title, without a lower status label");
                android.graphics.Bitmap rendered = android.graphics.Bitmap.createBitmap(page[0].getWidth(), page[0].getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                try {
                    page[0].draw(new android.graphics.Canvas(rendered));
                    java.io.File directory = new java.io.File(activity.getFilesDir(), "ui-smoke"); directory.mkdirs();
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, "task-page-scope.png"))) { rendered.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); }
                } finally { rendered.recycle(); }
                View card = page[0].findViewWithTag("task-card:" + inner.id()); require(card != null && card.performClick() && opened.equals(List.of(inner)), "inner icon card forwards original identity on click");
            } catch (Throwable error) { failure[0] = error; } });
            if (failure[0] != null) throw new AssertionError(failure[0]);
        } finally { test.runOnMainSync(() -> { if (page[0] != null) page[0].dispose(); activity.finish(); }); }
        return "task-page-scope: " + assertions + " assertions PASS; mixed displays, 100-task boundary, smaller inner label, icon-only inner card and outer preview";
    }
}
