package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** View input/lifecycle checks on a disposable emulator, not Samsung keyguard acceptance. */
final class ClockDockChecks {
    private final Instrumentation test;
    private Activity activity;
    private Prefs prefs;
    private FrameLayout root;
    private DockHandleView arrow;
    private int clicks, outsideClicks, assertions;
    ClockDockChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private void waitAnimation() { SystemClock.sleep(BuildConfig.MOTION_HANDLES_FADE_IN_MS + 120); test.waitForIdleSync(); }
    String run() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "visibility"));
        try {
            verifyModeSetting();
            verifyModeSummaries();
            test.runOnMainSync(() -> {
                prefs = new Prefs(activity); prefs.data.edit().putBoolean("haptics", false).commit();
                root = new FrameLayout(activity); root.setBackgroundColor(0xFF101418);
                View underneath = new View(activity); underneath.setOnClickListener(v -> outsideClicks++); root.addView(underneath, new FrameLayout.LayoutParams(-1, -1));
                arrow = new DockHandleView(activity, prefs, DockGeometry.BOTTOM, () -> { clicks++; arrow.expanded(!arrow.expanded()); });
                root.addView(arrow, new FrameLayout.LayoutParams(160, 72)); activity.setContentView(root);
                root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 480, 480);
                require(Math.abs(arrow.getAlpha() - .5f) < .01f && !arrow.expanded(), "new clock entry briefly shows its location without expanding the dock");
                tap(root, 240, 100);
            });
            test.waitForIdleSync();
            SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + BuildConfig.MOTION_HANDLES_FADE_OUT_MS + 200); test.waitForIdleSync();
            test.runOnMainSync(() -> {
                require(outsideClicks == 1 && clicks == 0, "outside arrow passes to clock-page content");
                require(arrow.getAlpha() < .01f, "first appearance fades after the shared delay");
                tap(root, 80, 36); require(clicks == 1 && arrow.expanded(), "invisible hit target opens in one tap");
            });
            waitAnimation(); require(arrow.getAlpha() > .99f, "opened arrow reaches full white-bar alpha");
            test.runOnMainSync(() -> capture("clock-dock-open.png"));
            SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + 200); test.waitForIdleSync();
            require(arrow.getAlpha() > .99f && arrow.expanded(), "open toolbar never auto-collapses or fades its arrow");
            test.runOnMainSync(() -> tap(root, 80, 36)); waitAnimation();
            require(!arrow.expanded() && Math.abs(arrow.getAlpha() - .5f) < .01f, "closing dims the arrow to half");
            test.runOnMainSync(() -> capture("clock-dock-collapsed.png"));
            test.runOnMainSync(() -> tap(root, 80, 36)); waitAnimation();
            SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + 200); test.waitForIdleSync();
            require(arrow.expanded() && arrow.getAlpha() > .99f, "reopening cancels old hide deadline");
            test.runOnMainSync(() -> tap(root, 80, 36));
            SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + BuildConfig.MOTION_HANDLES_FADE_OUT_MS + 200); test.waitForIdleSync();
            require(!arrow.expanded() && arrow.getAlpha() < .01f && arrow.getVisibility() == View.VISIBLE, "collapsed arrow fades but remains hittable");
            verifyPageVisibility();
            test.runOnMainSync(() -> {
                int before = clicks;
                event(arrow, MotionEvent.ACTION_DOWN, 80, 36); event(arrow, MotionEvent.ACTION_MOVE, 150, 36); event(arrow, MotionEvent.ACTION_UP, 150, 36);
                require(clicks == before, "drag is not a toggle");
                for (int cancel : new int[]{MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN}) {
                    event(arrow, MotionEvent.ACTION_DOWN, 80, 36); event(arrow, cancel, 80, 36); event(arrow, MotionEvent.ACTION_UP, 80, 36);
                    require(clicks == before, "cancel and multi-touch do not toggle");
                }
                event(arrow, MotionEvent.ACTION_DOWN, 80, 36); arrow.layout(0, 0, 170, 72); event(arrow, MotionEvent.ACTION_UP, 80, 36);
                require(clicks == before, "resize cancels pending click");
                arrow.performClick(); require(arrow.expanded() && clicks == before + 1, "accessibility click opens toolbar");
                root.removeView(arrow); event(arrow, MotionEvent.ACTION_UP, 80, 36); require(clicks == before + 1, "detach cancels pending input");
                verifyWindowCleanup();
                verifyStableDock();
            });
            verifyCoupledMotion();
            verifyNavigationSafeMount();
            assertions += new WindowStabilityChecks(test, activity).run();
            return "PASS: " + assertions + " clock arrow input, fade, accessibility and cleanup checks; Samsung physical clock routing not verified";
        } finally { test.runOnMainSync(activity::finish); }
    }
    private void verifyPageVisibility() {
        test.runOnMainSync(() -> arrow.autoHide(false)); waitAnimation();
        require(Math.abs(arrow.getAlpha() - .5f) < .01f, "leaving clock reveals the collapsed arrow");
        SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + BuildConfig.MOTION_HANDLES_FADE_OUT_MS + 150); test.waitForIdleSync();
        require(Math.abs(arrow.getAlpha() - .5f) < .01f, "app arrow stays visible past the old hide deadline");
        test.runOnMainSync(() -> { arrow.autoHide(true); arrow.autoHide(false); });
        SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + BuildConfig.MOTION_HANDLES_FADE_OUT_MS + 150); test.waitForIdleSync();
        require(Math.abs(arrow.getAlpha() - .5f) < .01f, "quick clock to app switch cancels the old hide callback");
        test.runOnMainSync(() -> arrow.autoHide(true));
        SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + BuildConfig.MOTION_HANDLES_FADE_OUT_MS + 150); test.waitForIdleSync();
        require(arrow.getAlpha() < .01f, "returning to clock restores delayed fade");
    }
    private void verifyStableDock() {
        DockGeometry.Box box = new DockGeometry.Box(0, 360, 320, 60);
        DockGeometry.Placement placement = new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, false);
        DockView dock = new DockView(activity, prefs, placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, true);
        root.addView(dock, new FrameLayout.LayoutParams(320, 60));
        View fixed = dock.findViewWithTag("fixed-action");
        require(dock.compact() && fixed.getVisibility() == View.GONE, "clock dock is hidden before first attachment draw");
        dock.compact(false); require(!dock.compact() && fixed.getVisibility() == View.VISIBLE, "clock arrow expands the existing dock content");
        dock.compact(true); require(dock.compact() && dock.getVisibility() == View.GONE, "returning to clock hides immediately without reconstructing buttons");
        dock.compact(false); require(dock.findViewWithTag("fixed-action") == fixed, "all arrow toggles preserve button identity and window stacking");
        root.removeView(dock);
    }
    private void verifyModeSummaries() {
        for (String page : new String[]{"main", "group_dock", "search"}) {
            test.runOnMainSync(() -> {
                try { var method = MainActivity.class.getDeclaredMethod("showRoute", String.class); method.setAccessible(true); method.invoke(activity, page.equals("search") ? "main" : page); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                if (page.equals("search")) { android.widget.EditText search = activity.findViewById(android.R.id.content).findViewWithTag("settings-query"); search.setText("模式"); }
            });
            String tag = page.equals("main") ? "settings-link-group_dock" : page.equals("group_dock") ? "settings-link-visibility" : "search-visibility";
            SettingsUi.ValueRow row = activity.findViewById(android.R.id.content).findViewWithTag(tag);
            View scroll = activity.findViewById(android.R.id.content).findViewWithTag("settings-scroll");
            test.runOnMainSync(() -> prefs.dockExpandable(true)); test.waitForIdleSync();
            require(row.value.getText().toString().contains("收起展开模式") && row == activity.findViewById(android.R.id.content).findViewWithTag(tag), page + " mode summary refreshes in place");
            test.runOnMainSync(() -> prefs.dockExpandable(false)); test.waitForIdleSync();
            require(row.value.getText().toString().contains("常驻模式") && scroll == activity.findViewById(android.R.id.content).findViewWithTag("settings-scroll"), page + " reverse update preserves scroll container");
        }
    }
    private void verifyModeSetting() {
        prefs = new Prefs(activity); CoverService[] service = {null}; int[] writes = {0};
        android.content.SharedPreferences.OnSharedPreferenceChangeListener count = (data, key) -> { if ("dock_expandable".equals(key)) writes[0]++; };
        View originalScroll = activity.findViewById(android.R.id.content).findViewWithTag("settings-scroll");
        android.widget.Switch control = activity.findViewById(android.R.id.content).findViewWithTag("dock_expandable");
        try {
            test.runOnMainSync(() -> prefs.dockExpandable(false)); test.waitForIdleSync();
            prefs.data.registerOnSharedPreferenceChangeListener(count);
            test.runOnMainSync(control::performClick); test.waitForIdleSync();
            require(prefs.dockExpandable() && control.isChecked() && writes[0] == 1, "settings switch saves arrow mode exactly once");
            test.runOnMainSync(() -> {
                CoverService owner = new CoverService(); service[0] = owner; owner.prefs = prefs;
                try { var attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class); attach.setAccessible(true); attach.invoke(owner, activity); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                DockView.Listener listener;
                try { var method = CoverService.class.getDeclaredMethod("chromeListener"); method.setAccessible(true); listener = (DockView.Listener) method.invoke(owner); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                DockGeometry.Box box = new DockGeometry.Box(0, 0, 300, 48);
                PanelEntryView entry = new PanelEntryView(activity, prefs, new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, true), listener);
                setField(owner, "panelEntry", entry);
                require(entry.performAccessibilityAction(R.id.toggle_dock, null), "white-bar mode action reaches the production listener");
                require(!prefs.dockExpandable() && "常驻模式".contentEquals(entry.getStateDescription()), "white-bar shortcut saves the mode and updates its semantics");
            });
            test.waitForIdleSync();
            require(!control.isChecked() && writes[0] == 2 && control.getContentDescription().toString().contains("常驻模式"), "open settings follows the shortcut without writing the preference again");
            require(activity.findViewById(android.R.id.content).findViewWithTag("settings-scroll") == originalScroll, "external mode update preserves settings scroll container");
            test.runOnMainSync(() -> {
                PanelEntryView entry = (PanelEntryView) getField(service[0], "panelEntry"); entry.performAccessibilityAction(R.id.toggle_dock, null);
                DockVisibility state = (DockVisibility) getField(service[0], "dockVisibility");
                require(state.expandable() && state.compact(false, java.util.Set.of()), "enabling mode through white bar starts collapsed");
                require((boolean) getField(service[0], "dockAppRulesEnabled") == (prefs.autoHideDock() && !prefs.compactApps().isEmpty()), "mode switch does not disable the existing app auto-hide feature");
                state.toggle(false, java.util.Set.of()); state.foreground("com.example.next");
                invokeNoArgs(service[0], "updateApplicationRules");
                require(!state.compact(false, java.util.Set.of("com.example.next")), "preference and foreground refresh preserve arrow expansion when app rules are off");
            });
            test.waitForIdleSync(); require(control.isChecked() && writes[0] == 3, "both shortcut directions synchronize the setting once");
        } finally {
            prefs.data.unregisterOnSharedPreferenceChangeListener(count);
            test.runOnMainSync(() -> {
                prefs.dockExpandable(false);
                if (service[0] != null) { service[0].main.removeCallbacksAndMessages(null); ((java.util.concurrent.ExecutorService) getField(service[0], "files")).shutdown(); }
            });
        }
    }
    private void verifyCoupledMotion() {
        CoverService[] service = {null}; DockHandleView[] handle = {null}; DockView[] moving = {null};
        int[] samples = {0}; String[] mismatch = {null};
        android.view.WindowManager windows = activity.getWindowManager();
        DockGeometry.Box closed = new DockGeometry.Box(20, 360, 160, 60), open = new DockGeometry.Box(20, 280, 160, 60);
        try {
            test.runOnMainSync(() -> {
                CoverService owner = new CoverService(); service[0] = owner; owner.prefs = prefs; owner.screenContext = activity;
                try { var attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class); attach.setAccessible(true); attach.invoke(owner, activity); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                DockGeometry.Placement placement = new DockGeometry.Placement(closed, closed, closed, DockGeometry.BOTTOM, false); owner.placement = placement;
                DockView dock = new DockView(activity, prefs, placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, true); moving[0] = dock;
                DockHandleView arrow = new DockHandleView(activity, prefs, DockGeometry.BOTTOM, () -> { }); handle[0] = arrow;
                windows.addView(dock, testWindow(closed)); windows.addView(arrow, testWindow(closed));
                setField(owner, "dock", dock); setField(owner, "dockPlacement", placement);
                setField(owner, "dockHandle", arrow); setField(owner, "dockHandleBox", closed); setField(owner, "dockHandlePositions", new DockGeometry.ClockHandle(closed, open)); setField(owner, "dockProgress", 0f);
                setField(owner, "windows", java.lang.reflect.Proxy.newProxyInstance(android.view.WindowManager.class.getClassLoader(), new Class<?>[]{android.view.WindowManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("removeViewImmediate")) windows.removeViewImmediate((View) args[0]);
                    if (method.getName().equals("updateViewLayout")) { var source = (android.view.WindowManager.LayoutParams) args[1]; var layout = testWindow(new DockGeometry.Box(source.x, source.y, source.width, source.height)); layout.flags = source.flags; windows.updateViewLayout((View) args[0], layout); }
                    return null;
                }));
            });
            waitAnimation();
            test.runOnMainSync(() -> {
                CoverService owner = service[0]; DockView dock = moving[0];
                invokeMotion(owner, true);
                android.animation.ValueAnimator motion = (android.animation.ValueAnimator) getField(owner, "dockHandleMotion");
                require(motion != null && (((android.view.WindowManager.LayoutParams) dock.getLayoutParams()).flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0, "shared motion disables moving dock input");
                motion.addUpdateListener(frame -> {
                    float progress = (float) frame.getAnimatedValue();
                    if (progress > 0 && progress < 1) samples[0]++;
                    if (Math.abs(dock.getTranslationY() - (1 - progress) * 60) > .1f || !getField(owner, "dockHandleBox").equals(DockGeometry.interpolate(closed, open, progress))) mismatch[0] = "arrow and dock used different progress";
                });
                motion.setCurrentFraction(.25f); motion.setCurrentFraction(.5f); motion.setCurrentFraction(.75f);
            });
            waitAnimation();
            test.runOnMainSync(() -> {
                require(samples[0] > 0 && mismatch[0] == null, "arrow and dock share intermediate lerp frames: samples=" + samples[0] + ", error=" + mismatch[0]);
                require(getField(service[0], "dockHandleBox").equals(open) && moving[0].getTranslationY() == 0 && !moving[0].compact(), "shared expansion finishes at both target positions");
                setField(service[0], "dockHandlePositions", new DockGeometry.ClockHandle(open, open));
                invokeMotion(service[0], false);
                android.animation.ValueAnimator motion = (android.animation.ValueAnimator) getField(service[0], "dockHandleMotion");
                require(motion != null, "navigation-safe stationary arrow still animates dock collapse");
                motion.setCurrentFraction(.5f);
                require(getField(service[0], "dockHandleBox").equals(open) && moving[0].getTranslationY() > 0 && moving[0].getTranslationY() < 60, "only dock moves when both arrow positions share the safe target");
            });
            waitAnimation();
            test.runOnMainSync(() -> {
                require(handle[0].isAttachedToWindow() && moving[0].compact(), "collapsed navigation-safe arrow keeps its window");
                invokeMotion(service[0], true);
                require(getField(service[0], "dockHandleMotion") != null, "stationary arrow reopens the dock with motion");
            });
            waitAnimation();
            test.runOnMainSync(() -> {
                require(!moving[0].compact() && moving[0].getTranslationY() == 0, "stationary arrow expansion restores the original dock position");
                invokeMotion(service[0], false); invokeNoArgs(service[0], "removeDockHandle");
                int flags = ((android.view.WindowManager.LayoutParams) moving[0].getLayoutParams()).flags;
                require(!moving[0].compact() && moving[0].getTranslationY() == 0 && (flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0, "canceling into a visible app restores dock touch and drawing");
            });
        } finally {
            test.runOnMainSync(() -> {
                if (service[0] != null) { invokeNoArgs(service[0], "removeWindows"); service[0].main.removeCallbacksAndMessages(null); ((java.util.concurrent.ExecutorService) getField(service[0], "files")).shutdown(); }
                else { if (handle[0] != null && handle[0].isAttachedToWindow()) windows.removeViewImmediate(handle[0]); if (moving[0] != null && moving[0].isAttachedToWindow()) windows.removeViewImmediate(moving[0]); }
            });
        }
    }
    private void verifyNavigationSafeMount() {
        CoverService previous = CoverService.instance;
        boolean hadDisplay = prefs.data.contains("display"), hadEnabled = prefs.data.contains("enabled");
        int previousDisplay = prefs.displayId(); boolean previousEnabled = prefs.enabled();
        CoverService[] service = {null}; DockHandleView[] mounted = {null};
        android.media.ImageReader surface = android.media.ImageReader.newInstance(748, 720, android.graphics.PixelFormat.RGBA_8888, 2);
        android.hardware.display.VirtualDisplay[] target = {null};
        android.view.WindowManager windows = activity.getWindowManager();
        try {
            target[0] = activity.getSystemService(android.hardware.display.DisplayManager.class).createVirtualDisplay("dock-arrow-navigation-test", 748, 720, 340, surface.getSurface(), 0);
            require(target[0] != null && target[0].getDisplay().getState() == android.view.Display.STATE_ON, "isolated selected display is on for arrow mount regression");
            test.runOnMainSync(() -> {
                prefs.data.edit().putInt("display", target[0].getDisplay().getDisplayId()).putBoolean("enabled", true).commit();
                CoverService owner = new CoverService(); service[0] = owner; owner.prefs = prefs; owner.screenContext = activity; owner.display = target[0].getDisplay(); CoverService.instance = owner;
                try { var attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class); attach.setAccessible(true); attach.invoke(owner, activity); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                DockGeometry.Box camera = new DockGeometry.Box(379, 654, 369, 66), safe = new DockGeometry.Box(0, 0, 748, 654), bars = new DockGeometry.Box(385, 620, 357, 34);
                DockGeometry.Placement placement = DockGeometry.edgeTouch(DockGeometry.resolve(748, 720, java.util.List.of(camera), 2.125f, 3, .46f, .088f, true), 748, 720); owner.placement = placement;
                DockGeometry.Box open = DockGeometry.clockHandle(placement, 748, 720, java.util.List.of(camera), 2.125f, safe, bars);
                setField(owner, "dockHandlePositions", DockGeometry.clockHandlePositions(placement, open)); setField(owner, "dockPlacement", placement);
                ((DockVisibility) getField(owner, "dockVisibility")).expandable(true);
                DockView.Listener listener = new DockView.Listener() { public void action(String id) { } public void configure() { } };
                DockView dock = new DockView(activity, prefs, placement, 0, listener, true); setField(owner, "dock", dock); windows.addView(dock, testWindow(placement.touch()));
                PanelEntryView entry = new PanelEntryView(activity, prefs, new DockGeometry.Placement(bars, bars, bars, DockGeometry.BOTTOM, true), listener);
                root.addView(entry, new FrameLayout.LayoutParams(bars.width(), bars.height())); setField(owner, "panelEntry", entry); setField(owner, "panelEntryPlacement", new DockGeometry.Placement(bars, bars, bars, DockGeometry.BOTTOM, true));
                setField(owner, "windows", java.lang.reflect.Proxy.newProxyInstance(android.view.WindowManager.class.getClassLoader(), new Class<?>[]{android.view.WindowManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("removeViewImmediate")) { View view = (View) args[0]; if (view.getParent() == root) root.removeView(view); else windows.removeViewImmediate(view); }
                    if (method.getName().equals("addView") || method.getName().equals("updateViewLayout")) {
                        var source = (android.view.WindowManager.LayoutParams) args[1]; var layout = testWindow(new DockGeometry.Box(source.x, source.y, source.width, source.height)); layout.flags = source.flags;
                        View view = (View) args[0]; if (view.getParent() != root) { if (method.getName().equals("addView")) windows.addView(view, layout); else windows.updateViewLayout(view, layout); }
                    }
                    return null;
                }));
                invokeNoArgs(owner, "syncDockVisibility"); mounted[0] = (DockHandleView) getField(owner, "dockHandle");
                require(mounted[0] != null, "66px app navigation cannot suppress the production arrow mount");
                require(getField(owner, "dockHandleBox").equals(((DockGeometry.ClockHandle) getField(owner, "dockHandlePositions")).collapsed()) && getField(owner, "dockHandleMotion") == null, "collapsed arrow mounts at its saved position without an entrance animation");
                require(dock.compact() && dock.getVisibility() == View.GONE, "restoring a collapsed arrow never reveals the shortcut buttons");
                ((DockVisibility) getField(owner, "dockVisibility")).foreground("next.app"); invokeNoArgs(owner, "syncDockVisibility");
                require(mounted[0] == getField(owner, "dockHandle") && getField(owner, "dockHandleMotion") == null, "ordinary app change retains the arrow without replaying motion");
                DockGeometry.Box closed = (DockGeometry.Box) getField(owner, "dockHandleBox");
                invokeNoArgs(owner, "removeDockHandle"); invokeNoArgs(owner, "syncDockVisibility"); mounted[0] = (DockHandleView) getField(owner, "dockHandle");
                require(closed.equals(getField(owner, "dockHandleBox")) && getField(owner, "dockHandleMotion") == null, "window remount restores the collapsed arrow in place from the first frame");
            });
            waitAnimation();
            test.runOnMainSync(() -> { require(mounted[0].isAttachedToWindow(), "navigation-safe arrow remains attached"); mounted[0].performClick(); require(getField(service[0], "dockHandleMotion") != null, "explicit arrow click still animates expansion"); });
            waitAnimation();
            test.runOnMainSync(() -> {
                require(!((DockView) getField(service[0], "dock")).compact(), "mounted arrow click expands through the production callback");
                service[0].launcherAccepted(); invokeNoArgs(service[0], "syncDockVisibility");
                require(mounted[0] == getField(service[0], "dockHandle") && mounted[0].isAttachedToWindow(), "accepted app launch retains the existing safe arrow");
                mounted[0].performClick();
            });
            waitAnimation();
            test.runOnMainSync(() -> require(((DockView) getField(service[0], "dock")).compact() && mounted[0].isAttachedToWindow(), "production collapse keeps a usable arrow after launch"));
            test.runOnMainSync(() -> mounted[0].performClick()); waitAnimation();
            test.runOnMainSync(() -> {
                CoverService owner = service[0]; DockVisibility state = (DockVisibility) getField(owner, "dockVisibility");
                setField(owner, "compactApplications", java.util.Set.of("hidden.app")); setField(owner, "dockAppRulesEnabled", true);
                state.foreground("hidden.app"); invokeNoArgs(owner, "syncDockVisibility");
                require(((DockView) getField(owner, "dock")).compact() && getField(owner, "dockHandle") == null, "app auto-hide removes both expanded dock and arrow");
                require(((PanelEntryView) getField(owner, "panelEntry")).isAttachedToWindow(), "app hiding preserves notification and control white bars");
                state.foreground("browser"); invokeNoArgs(owner, "syncDockVisibility");
                require(getField(owner, "dockHandleBox").equals(((DockGeometry.ClockHandle) getField(owner, "dockHandlePositions")).expanded()) && getField(owner, "dockHandleMotion") == null && !((DockView) getField(owner, "dock")).compact(), "leaving hidden app restores expanded arrow and buttons without replaying entrance motion");
            });
            waitAnimation();
            test.runOnMainSync(() -> {
                CoverService owner = service[0]; DockVisibility state = (DockVisibility) getField(owner, "dockVisibility");
                require(!((DockView) getField(owner, "dock")).compact() && getField(owner, "dockHandle") != null, "leaving hidden app restores the previous expanded state");
                state.expandable(false); state.foreground("hidden.app"); invokeNoArgs(owner, "syncDockVisibility");
                require(((DockView) getField(owner, "dock")).compact() && getField(owner, "dockHandle") == null, "app hiding also overrides resident mode");
                setField(owner, "dockAppRulesEnabled", false); invokeNoArgs(owner, "syncDockVisibility");
                require(!((DockView) getField(owner, "dock")).compact(), "disabling the existing auto-hide switch restores resident dock");
                setField(owner, "compactApplications", java.util.Set.of("com.android.systemui", "hidden.app")); setField(owner, "dockAppRulesEnabled", true);
                for (boolean expandable : new boolean[]{false, true}) {
                    state.expandable(expandable); state.foreground("com.android.systemui"); setField(owner, "coverClockVisible", true); setField(owner, "returningToClock", true);
                    invokeNoArgs(owner, "syncDockVisibility");
                    require(getField(owner, "dockHandle") != null && ((DockView) getField(owner, "dock")).compact(), "clock exception keeps the arrow in both modes despite app rules and paging");
                    state.toggle(true, java.util.Set.of("com.android.systemui", "hidden.app")); invokeNoArgs(owner, "syncDockVisibility");
                    require(((DockHandleView) getField(owner, "dockHandle")).expanded(), "clock arrow expands without changing the saved mode");
                    setField(owner, "coverClockVisible", false); setField(owner, "returningToClock", false); state.foreground("hidden.app"); invokeNoArgs(owner, "syncDockVisibility");
                    require(getField(owner, "dockHandle") == null && ((DockView) getField(owner, "dock")).compact(), "leaving clock restores hidden-app priority");
                }
            });
        } finally {
            try {
                test.runOnMainSync(() -> {
                    try { if (service[0] != null) invokeNoArgs(service[0], "removeWindows"); }
                    finally {
                        if (service[0] != null) { service[0].main.removeCallbacksAndMessages(null); ((java.util.concurrent.ExecutorService) getField(service[0], "files")).shutdown(); }
                        CoverService.instance = previous;
                        var edit = prefs.data.edit(); if (hadDisplay) edit.putInt("display", previousDisplay); else edit.remove("display"); if (hadEnabled) edit.putBoolean("enabled", previousEnabled); else edit.remove("enabled"); edit.commit();
                    }
                });
            } finally { try { if (target[0] != null) target[0].release(); } finally { surface.close(); } }
        }
    }
    private android.view.WindowManager.LayoutParams testWindow(DockGeometry.Box box) {
        var layout = new android.view.WindowManager.LayoutParams(box.width(), box.height(), android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, android.graphics.PixelFormat.TRANSLUCENT);
        layout.token = activity.getWindow().getDecorView().getWindowToken(); layout.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT; layout.x = box.x(); layout.y = box.y(); return layout;
    }
    private void setField(Object target, String name, Object value) { try { var field = CoverService.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private Object getField(Object target, String name) { try { var field = CoverService.class.getDeclaredField(name); field.setAccessible(true); return field.get(target); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void invokeMotion(CoverService owner, boolean expanded) { try { var method = CoverService.class.getDeclaredMethod("moveDockHandle", boolean.class); method.setAccessible(true); method.invoke(owner, expanded); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void invokeNoArgs(CoverService owner, String name) { try { var method = CoverService.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void verifyWindowCleanup() {
        CoverService owner = new CoverService(); owner.prefs = prefs; owner.screenContext = activity;
        try {
            var attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class); attach.setAccessible(true); attach.invoke(owner, activity);
            root.addView(arrow, new FrameLayout.LayoutParams(160, 72));
            var mode = CoverService.class.getDeclaredField("coverClockVisible"); mode.setAccessible(true); mode.set(owner, true);
            var handle = CoverService.class.getDeclaredField("dockHandle"); handle.setAccessible(true); handle.set(owner, arrow);
            var windows = CoverService.class.getDeclaredField("windows"); windows.setAccessible(true);
            windows.set(owner, java.lang.reflect.Proxy.newProxyInstance(android.view.WindowManager.class.getClassLoader(), new Class<?>[]{android.view.WindowManager.class}, (proxy, method, args) -> { if (method.getName().equals("removeViewImmediate")) root.removeView((View) args[0]); return null; }));
            var remove = CoverService.class.getDeclaredMethod("removeWindows"); remove.setAccessible(true); remove.invoke(owner);
            require(handle.get(owner) == null && !(boolean) mode.get(owner) && !arrow.isAttachedToWindow(), "service teardown releases arrow and clock scene");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally {
            owner.main.removeCallbacksAndMessages(null);
            try { var files = CoverService.class.getDeclaredField("files"); files.setAccessible(true); ((java.util.concurrent.ExecutorService) files.get(owner)).shutdown(); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
    }
    private void tap(View view, float x, float y) { event(view, MotionEvent.ACTION_DOWN, x, y); event(view, MotionEvent.ACTION_UP, x, y); }
    private void capture(String name) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        try (var stream = new java.io.FileOutputStream(new java.io.File(activity.getFilesDir(), name))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream); }
        catch (java.io.IOException error) { throw new AssertionError(error); }
        finally { bitmap.recycle(); }
    }
    private void event(View view, int action, float x, float y) { long now = SystemClock.uptimeMillis(); MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0); view.dispatchTouchEvent(event); event.recycle(); }
}
