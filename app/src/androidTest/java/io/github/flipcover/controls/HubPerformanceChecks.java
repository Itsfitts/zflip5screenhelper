package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.DataSetObserver;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual PackageManager and native-view checks, restricted by the runner to disposable emulators. */
final class HubPerformanceChecks {
    private final Instrumentation instrumentation;
    private AppCatalogCache cache;
    private int assertions, baselineLabelReads;
    private final JSONObject evidence = new JSONObject();
    HubPerformanceChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        require(condition.getAsBoolean(), message); instrumentation.waitForIdleSync();
    }
    private String oldLabel(String id) {
        baselineLabelReads++;
        try { PackageManager pm = instrumentation.getTargetContext().getPackageManager(); return pm.getActivityInfo(ActionCatalog.component(id), 0).loadLabel(pm).toString(); }
        catch (Exception e) { return "应用已移除"; }
    }
    /** The prior scanner read labels inside its comparator, then the hub read every label again. */
    private Map<String, String> baselineCatalog() {
        List<String> ids = new ArrayList<>();
        for (ResolveInfo info : instrumentation.getTargetContext().getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (info.activityInfo != null && info.activityInfo.exported) {
                String id = "app:" + new ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString(); if (!ids.contains(id)) ids.add(id);
            }
        }
        ids.sort((a, b) -> oldLabel(a).compareToIgnoreCase(oldLabel(b)));
        Map<String, String> labels = new HashMap<>(); for (String id : ids) labels.put(id, oldLabel(id)); return labels;
    }
    String run() throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        cache = CoverApp.catalog(activity);
        cache.entriesBlocking(); instrumentation.waitForIdleSync();
        String firstId = cache.snapshot().get(0).id(); main(cache::invalidate); require(cache.label(firstId) == null, "invalidated metadata cannot seed icons with stale application names"); cache.entriesBlocking();
        JSONArray catalogRuns = new JSONArray();
        for (int run = 0; run < 4; run++) {
            Map<String, String> before, after = new HashMap<>(); long start, oldNanos, newNanos; int reads;
            baselineLabelReads = 0;
            if (run % 2 == 0) {
                start = SystemClock.elapsedRealtimeNanos(); before = baselineCatalog(); oldNanos = SystemClock.elapsedRealtimeNanos() - start;
                main(cache::invalidate); reads = cache.labelReadCount(); start = SystemClock.elapsedRealtimeNanos(); for (AppCatalogCache.Entry entry : cache.entriesBlocking()) after.put(entry.id(), entry.label()); newNanos = SystemClock.elapsedRealtimeNanos() - start;
            } else {
                main(cache::invalidate); reads = cache.labelReadCount(); start = SystemClock.elapsedRealtimeNanos(); for (AppCatalogCache.Entry entry : cache.entriesBlocking()) after.put(entry.id(), entry.label()); newNanos = SystemClock.elapsedRealtimeNanos() - start;
                start = SystemClock.elapsedRealtimeNanos(); before = baselineCatalog(); oldNanos = SystemClock.elapsedRealtimeNanos() - start;
            }
            require(before.equals(after), "cold cached catalog preserves installed application IDs and names");
            require(cache.labelReadCount() - reads == after.size(), "each application label is read once per cold scan");
            catalogRuns.put(new JSONObject().put("baseline_ms", oldNanos / 1e6).put("cached_cold_ms", newNanos / 1e6).put("baseline_label_reads", baselineLabelReads).put("cached_label_reads", cache.labelReadCount() - reads).put("apps", after.size()));
        }
        evidence.put("catalog_runs", catalogRuns);
        Prefs prefs = new Prefs(activity); List<AppCatalogCache.Entry> entries = cache.entriesBlocking(); require(entries.size() >= 5, "emulator has application fixtures");
        prefs.saveHubPins(List.of(entries.get(0).id(), entries.get(1).id(), entries.get(2).id()));
        int scans = cache.scanCount(), reads = cache.labelReadCount(), warmDecodes = -1; JSONArray openings = new JSONArray();
        for (int run = 0; run < 5; run++) {
            AppHubView[] hub = {null}; AppWorkspaceView[] grid = {null}; LinearLayout[] dock = {null}; int[] changes = {0}; long[] construction = {0};
            DataSetObserver observer = new DataSetObserver() { @Override public void onChanged() { changes[0]++; } };
            main(() -> {
                long start = SystemClock.elapsedRealtimeNanos();
                hub[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { } });
                FrameLayout root = new FrameLayout(activity); root.addView(hub[0], new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root);
                grid[0] = hub[0].findViewWithTag("hub-grid"); dock[0] = hub[0].findViewWithTag("hub-dock"); grid[0].getAdapter().registerDataSetObserver(observer);
                construction[0] = SystemClock.elapsedRealtimeNanos() - start;
                require(hub[0].expanded() && grid[0].getCount() == entries.size() - prefs.hubPins().size(), "warm open shows desktop apps without duplicating fixed Dock apps");
                require(!find(hub[0], EditText.class).hasFocus(), "open does not summon the keyboard");
            });
            instrumentation.waitForIdleSync(); View[] firstPin = {null}; main(() -> firstPin[0] = dock[0].getChildAt(0));
            await(() -> cache.pendingIconCount() == 0, "visible icons finish loading"); SystemClock.sleep(100); instrumentation.waitForIdleSync();
            main(() -> {
                require(changes[0] == 0, "icon completions do not refresh the grid adapter");
                require(firstPin[0] == dock[0].getChildAt(0), "icon completions do not recreate Dock cells");
                require(find(grid[0].getChildAt(0), ImageView.class).getDrawable() instanceof android.graphics.drawable.BitmapDrawable, "visible grid icon resolves from bitmap cache");
                hub[0].recentResult(List.of(), null); hub[0].recentResult(List.of(), null); hub[0].recentFailure("offline"); hub[0].recentFailure("offline");
                require(firstPin[0] == dock[0].getChildAt(0) && changes[0] == 0, "unchanged or unavailable recents do not rebuild installed apps or Dock");
                EditText search = find(hub[0], EditText.class); search.setText("__missing__"); require(grid[0].getCount() == 0, "search filters cached metadata"); search.setText(entries.get(0).label()); require(grid[0].getCount() > 0, "explicit search includes pinned applications"); search.setText(""); require(grid[0].getCount() == entries.size() - prefs.hubPins().size(), "clearing search restores the desktop ownership rule");
                grid[0].getAdapter().unregisterDataSetObserver(observer); hub[0].dispose(); activity.setContentView(new FrameLayout(activity)); require(cache.observerCount() == 0, "disposed hub releases cache subscription");
            });
            require(cache.scanCount() == scans && cache.labelReadCount() == reads, "reopening and searching perform no package scan or label read");
            if (run > 0) require(cache.decodeCount() == warmDecodes, "warm reopening performs no icon decode"); warmDecodes = cache.decodeCount();
            require(cache.iconBytes() <= AppCatalogCache.ICON_BYTES, "icon bytes stay within the four MiB bound");
            openings.put(new JSONObject().put("create_ms", construction[0] / 1e6).put("package_scans", cache.scanCount() - scans).put("label_reads", cache.labelReadCount() - reads).put("total_icon_decodes", cache.decodeCount()).put("grid_icon_refreshes", 0).put("icon_bytes", cache.iconBytes()));
        }
        evidence.put("openings", openings);
        checkInterruptedIcons(activity, prefs, entries);
        checkEnabledAlias(activity, prefs);
        checkPackageChange(activity);
        main(() -> { cache.trimMemory(); require(cache.iconBytes() == 0, "low-memory trim releases cached bitmaps without recycling displayed images"); });
        ShizukuBridge bridge = CoverApp.bridge(activity); int[] notifications = {0}; Runnable listener = () -> notifications[0]++;
        require(!bridge.granted(), "test emulator has no Shizuku permission");
        main(() -> { bridge.addObserver(listener); bridge.connect(); bridge.connect(); bridge.connect(); }); instrumentation.waitForIdleSync();
        main(() -> { bridge.removeObserver(listener); require(notifications[0] == 0, "unchanged bridge state does not feed its observers repeatedly"); activity.finish(); });
        evidence.put("assertions", assertions).put("scope", "Android emulator; system tasks and Samsung frame timing not measured");
        File directory = new File(instrumentation.getTargetContext().getFilesDir(), "ui-smoke"); directory.mkdirs(); Files.write(new File(directory, "hub-performance.json").toPath(), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        return "PASS: hub-performance; " + assertions + " assertions on disposable emulator";
    }
    private void checkInterruptedIcons(Activity activity, Prefs prefs, List<AppCatalogCache.Entry> entries) {
        AppHubView[] hub = {null};
        main(() -> {
            cache.trimMemory();
            // Overflow one bounded queue with distinct unavailable components, then mount real apps.
            for (int i = 0; i < 130; i++) cache.requestIcon("app:missing.example/.Fixture" + i);
            hub[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { } });
            activity.setContentView(hub[0]); cache.trimMemory();
        });
        await(() -> {
            boolean[] visibleReady = {false};
            main(() -> { AppWorkspaceView grid = hub[0].findViewWithTag("hub-grid"); visibleReady[0] = grid.getChildCount() > 0; for (int i = 0; i < grid.getChildCount(); i++) if (!(find(grid.getChildAt(i), ImageView.class).getDrawable() instanceof android.graphics.drawable.BitmapDrawable)) visibleReady[0] = false; });
            return visibleReady[0] && cache.pendingIconCount() == 0;
        }, "visible icons recover after queue pressure and an in-flight trim");
        main(() -> { AppWorkspaceView grid = hub[0].findViewWithTag("hub-grid"); for (int i = 0; i < grid.getChildCount(); i++) require(find(grid.getChildAt(i), ImageView.class).getDrawable() instanceof android.graphics.drawable.BitmapDrawable, "visible application does not retain an abandoned placeholder"); hub[0].dispose(); activity.setContentView(new FrameLayout(activity)); });
    }
    private void checkPackageChange(Activity activity) {
        AppCatalogCache.Listener observer = new AppCatalogCache.Listener() { public void catalogChanged() { } public void iconsChanged(java.util.Set<String> ids) { } };
        PackageManager pm = activity.getPackageManager(); ComponentName own = new ComponentName(activity, MainActivity.class); String id = "app:" + own.flattenToString(); int previous = pm.getComponentEnabledSetting(own);
        main(() -> cache.observe(observer));
        try {
            pm.setComponentEnabledSetting(own, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            await(() -> cache.ready() && cache.snapshot().stream().noneMatch(entry -> entry.id().equals(id)), "real package-change broadcast invalidates disabled application");
        } finally { pm.setComponentEnabledSetting(own, previous, PackageManager.DONT_KILL_APP); }
        try { await(() -> cache.ready() && cache.snapshot().stream().anyMatch(entry -> entry.id().equals(id)), "re-enabled application returns after package broadcast"); }
        finally { main(() -> cache.unobserve(observer)); }
    }
    private void checkEnabledAlias(Activity activity, Prefs prefs) throws Exception {
        PackageManager pm = activity.getPackageManager();
        ComponentName alias = new ComponentName(instrumentation.getContext().getPackageName(), "io.github.flipcover.controls.CatalogHiddenAlias");
        String id = "app:" + alias.flattenToString(), previousSort = prefs.hubSort();
        int previous = pm.getComponentEnabledSetting(alias);
        AppHubView[] hub = {null};
        instrumentation.getUiAutomation().adoptShellPermissionIdentity("android.permission.CHANGE_COMPONENT_ENABLED_STATE");
        try {
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
            main(() -> {
                prefs.data.edit().putString("hub_sort", "name").commit();
                hub[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String action) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { } });
                activity.setContentView(hub[0]); find(hub[0], EditText.class).setText("日常程序测试");
                require(hub[0].isAttachedToWindow() && cache.observerCount() > 0, "alias check uses a mounted catalog observer before disabling the host activity");
            });
            await(() -> cache.ready() && !hasAlias(id), "manifest-disabled alias stays absent");
            for (int round = 0; round < 2; round++) {
                int generation = cache.contentGeneration(), scans = cache.scanCount();
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                ResolveInfo resolved = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(alias.getPackageName()), 0).stream().filter(info -> info.activityInfo.name.equals(alias.getClassName())).findFirst().orElseThrow();
                require(!resolved.activityInfo.enabled, "fixture reproduces enabled override with false manifest default");
                try { await(() -> cache.ready() && hasAlias(id), "package-change broadcast adds user-enabled alias without reopening"); }
                catch (AssertionError failure) { throw new AssertionError(failure.getMessage() + "; generation=" + generation + "->" + cache.contentGeneration() + " scans=" + scans + "->" + cache.scanCount() + " observers=" + cache.observerCount() + " ready=" + cache.ready(), failure); }
                await(() -> {
                    boolean[] visible = {false};
                    main(() -> { AppWorkspaceView grid = hub[0].findViewWithTag("hub-grid"); visible[0] = grid.getCount() == 1 && grid.getChildCount() == 1 && find(grid.getChildAt(0), ImageView.class).getDrawable() instanceof android.graphics.drawable.BitmapDrawable; });
                    return visible[0];
                }, "mounted floating search displays the enabled alias and its icon");
                main(() -> require(nativeShowsAlias(activity, prefs, id), "native card renders the same enabled alias"));
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                await(() -> cache.ready() && !hasAlias(id), "package-change broadcast removes disabled alias");
                main(() -> {
                    AppWorkspaceView grid = hub[0].findViewWithTag("hub-grid"); require(grid.getCount() == 0, "mounted search removes the disabled alias");
                    require(!nativeShowsAlias(activity, prefs, id), "native card removes the disabled alias");
                });
            }
        } finally {
            pm.setComponentEnabledSetting(alias, previous, PackageManager.DONT_KILL_APP);
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
            main(() -> { if (hub[0] != null) hub[0].dispose(); activity.setContentView(new FrameLayout(activity)); prefs.data.edit().putString("hub_sort", previousSort).commit(); });
        }
    }
    private boolean hasAlias(String id) { return cache.snapshot().stream().anyMatch(entry -> entry.id().equals(id)); }
    private boolean nativeShowsAlias(Activity activity, Prefs prefs, String id) {
        java.util.Set<String> shown = new java.util.HashSet<>();
        new LauncherWidgetViews(activity, prefs, activity.getDisplay(), -1, new LauncherWidgetBridge.State(), new LauncherWidgetBridge.Recents(List.of(), false, false, false, false, ""), shown, new java.util.HashSet<>(), new HashMap<>()).render();
        return shown.contains(id);
    }
    private <T extends View> T find(View view, Class<T> type) { if (type.isInstance(view)) return type.cast(view); if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { T found = find(group.getChildAt(i), type); if (found != null) return found; } return null; }
}
