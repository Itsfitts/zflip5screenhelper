package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import java.util.List;

/** Four rotated physical cutouts and real Android Insets, including the reported 144/66 case. */
final class FullWindowInsetsChecks {
    private final Instrumentation test;
    private int assertions;
    FullWindowInsetsChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    String run() {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.runOnMainSync(() -> {
                var configuration = new android.content.res.Configuration(activity.getResources().getConfiguration()); configuration.densityDpi = 340;
                var context = activity.createConfigurationContext(configuration);
                Prefs prefs = new Prefs(context);
                for (int rotation = 0; rotation < 4; rotation++) {
                    int width = rotation % 2 == 0 ? 748 : 720, height = rotation % 2 == 0 ? 720 : 748;
                    Rect rect = switch (rotation) { case 0 -> new Rect(379, 654, 748, 720); case 1 -> new Rect(654, 0, 720, 369); case 2 -> new Rect(0, 0, 369, 66); default -> new Rect(0, 379, 66, 748); };
                    Insets physical = edge(rotation, 66);
                    DisplayCutout cutout = new DisplayCutout(new Rect(physical.left, physical.top, physical.right, physical.bottom), List.of(rect));
                    var key = new CoverWindowInsets.Geometry(1, rotation, width, height, 2.125f, cutout.toString());
                    var frame = new DockGeometry.Box(0, 0, width, height);
                    WindowInsets maximum = insets(cutout, edge(rotation, 144), physical, physical, true);
                    for (int mandatory : new int[]{0, 12, 144}) for (boolean visible : new boolean[]{false, true}) {
                        CoverWindowInsets source = new CoverWindowInsets();
                        require(source.resolve(key, maximum) == maximum, "unmeasured host retains maximum fallback at rotation " + rotation);
                        WindowInsets actual = insets(cutout, edge(rotation, mandatory), physical, edge(rotation, 160), visible);
                        require(observe(source, key, key, 1, 2.125f, frame, actual), "first full-window sample changes source");
                        WindowInsets selected = source.resolve(key, maximum);
                        require(source.diagnostics().contains("边衬来源=全屏实测"), "source is identified");
                        require(selected.getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(edge(rotation, mandatory)), "actual forced gesture areas survive unchanged");
                        Insets safe = CoverService.contentInsets(selected, cutout);
                        require(safe.equals(Insets.max(physical, edge(rotation, mandatory))), "hidden nav bounds do not leak into content");
                        require(!observe(source, key, key, 1, 2.125f, frame, actual), "identical dispatch does not schedule a layout loop");
                        List<DockGeometry.Box> cuts = List.of(new DockGeometry.Box(rect.left, rect.top, rect.width(), rect.height()));
                        for (String position : rotation == 0 ? new String[]{"top_left", "bottom_right"} : rotation == 2 ? new String[]{"top_left"} : new String[]{"top_left", "top_right"}) {
                            String pref = "panel_entry_" + rotation; String previous = prefs.data.getString(pref, null);
                            prefs.entryPosition(rotation, position);
                            try {
                                CoverService owner = new CoverService(); owner.screenContext = context; owner.prefs = prefs;
                                DockGeometry.Placement dock = DockGeometry.resolve(width, height, cuts, 2.125f, (rotation + 3) % 4, .46f, .088f, true);
                                owner.placement = DockGeometry.edgeTouch(dock, width, height);
                                int home = Math.max(selected.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom, selected.getInsets(WindowInsets.Type.navigationBars()).bottom);
                                DockGeometry.Box widget = owner.resolveContentGeometry(new Point(width, height), rotation, cuts, dock, safe, home);
                                DockGeometry.Box bounds = new DockGeometry.Box(safe.left, safe.top, width - safe.left - safe.right, height - safe.top - safe.bottom);
                                require(owner.placement.panel().intersect(bounds).equals(owner.placement.panel()), "panel obeys actual full-screen safe area");
                                require(widget.intersect(bounds).equals(widget), "native widget shares actual safe area");
                                require(owner.placement.visual().equals(dock.visual()), "source selection does not move the correct shortcut dock");
                                var field = CoverService.class.getDeclaredField("panelEntryPlacement"); field.setAccessible(true);
                                var entry = (DockGeometry.Placement) field.get(owner);
                                require(entry.edge() == (position.equals("bottom_right") ? DockGeometry.BOTTOM : DockGeometry.TOP), "entry direction remains correct in all rotations");
                                if (rotation == 0 && position.equals("bottom_right")) {
                                    int bottom = mandatory == 144 ? 576 : 654;
                                    require(entry.touch().bottom() == bottom && owner.placement.panel().bottom() == bottom - 34, "144/66 regression changes the entry and panel together");
                                    var chrome = DockGeometry.panelEntryChrome(entry, 2.125f);
                                    require(entry.touch().y() + chrome.firstHandle().y() == bottom - 6, "reported bar moves from y570 to y648 only when actual safety permits");
                                }
                            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                            finally { if (previous == null) prefs.data.edit().remove(pref).commit(); else prefs.data.edit().putString(pref, previous).commit(); }
                        }
                    }
                    rejectionChecks(key, frame, maximum); freshnessChecks(key, frame, maximum);
                }
            });
            return "PASS: " + assertions + " full-window insets checks; four rotations, every entry position, 144/66 geometry, real mandatory areas, hidden nav, invalid and stale sources; Samsung routing pending";
        } finally { test.runOnMainSync(activity::finish); }
    }
    private void rejectionChecks(CoverWindowInsets.Geometry key, DockGeometry.Box frame, WindowInsets maximum) {
        CoverWindowInsets source = new CoverWindowInsets();
        WindowInsets zero = insets(null, Insets.NONE, Insets.NONE, Insets.NONE, false);
        for (DockGeometry.Box invalid : List.of(new DockGeometry.Box(1, 0, key.width(), key.height()), new DockGeometry.Box(0, 0, key.width(), key.height() / 2))) {
            observe(source, key, key, 1, 2.125f, invalid, zero);
            require(source.resolve(key, maximum) == maximum, "offset and cropped windows cannot erase mandatory safety");
        }
        observe(source, key, key, 0, 2.125f, frame, zero); require(source.resolve(key, maximum) == maximum, "primary-screen data rejected");
        observe(source, key, key, 1, 3f, frame, zero); require(source.resolve(key, maximum) == maximum, "scaled-context data rejected");
        observe(source, key, key, 1, 2.125f, frame, WindowInsets.CONSUMED); require(source.resolve(key, maximum) == maximum, "consumed insets are unknown rather than zero");
        observe(source, key, key, 1, 2.125f, frame, null); require(source.resolve(key, maximum) == maximum, "missing insets retain fallback");
        observe(source, key, key, 1, 2.125f, frame, zero);
        require(CoverService.contentInsets(source.resolve(key, maximum), maximum.getDisplayCutout()).equals(edge(key.rotation(), 66)), "physical notch survives a full host reporting no cutout");
        WindowInsets keyboard = new WindowInsets.Builder(maximum).setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 300)).setVisible(WindowInsets.Type.ime(), true).build();
        require(!observe(source, key, key, 1, 2.125f, new DockGeometry.Box(0, 0, key.width(), key.height() - 300), keyboard), "IME resize does not schedule a source switch or rebuild");
        require(!observe(source, key, key, 1, 2.125f, frame, keyboard), "full-size IME dispatch also preserves the editing session");
        require(!observe(source, key, key, 1, 2.125f, frame, null) && !observe(source, key, key, 1, 2.125f, frame, WindowInsets.CONSUMED), "temporary unavailable dispatch retains validated evidence");
        require(source.resolve(key, maximum).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(Insets.NONE), "transient invalid samples cannot restore obsolete max144 safety");
        require(!observe(source, key, key, 1, 2.125f, frame, zero), "restored full window completes without a second source switch");
        var next = new CoverWindowInsets.Geometry(key.displayId(), (key.rotation() + 2) % 4, key.width(), key.height(), key.density(), key.cutout());
        require(source.resolve(next, maximum) == maximum, "same-size reverse rotation discards the previous source");
        require(!observe(source, key, next, 1, 2.125f, frame, zero), "late old-rotation callback cannot restore old state");
        observe(source, next, next, 1, 2.125f, frame, zero);
        observe(source, key, next, 1, 2.125f, frame, maximum);
        require(source.resolve(next, maximum).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(Insets.NONE), "late callback cannot replace a valid new-rotation sample");
        source.reset(); require(source.resolve(next, maximum) == maximum, "session reset removes the last sample");
    }
    private static boolean observe(CoverWindowInsets source, CoverWindowInsets.Geometry mounted, CoverWindowInsets.Geometry current, int display, float density, DockGeometry.Box frame, WindowInsets insets) {
        return source.observe(mounted, current, display, density, frame, insets, source.sampleRevision());
    }
    private void freshnessChecks(CoverWindowInsets.Geometry key, DockGeometry.Box frame, WindowInsets maximum) {
        CoverWindowInsets source = new CoverWindowInsets();
        WindowInsets zero = insets(null, Insets.NONE, Insets.NONE, Insets.NONE, false);
        WindowInsets sixtyFour = insets(null, edge(key.rotation(), 64), Insets.NONE, Insets.NONE, false);
        source.resolve(key, zero); long oldEvent = source.sampleRevision();
        observe(source, key, key, 1, 2.125f, frame, zero);
        require(source.resolve(key, sixtyFour) == sixtyFour, "same-geometry max0 to64 invalidates stale full sample0");
        require(source.source().equals("最大窗口回退"), "invalidated sample is never labelled current full measurement");
        require(!source.observe(key, key, 1, 2.125f, frame, zero, oldEvent), "layout completion of old inset dispatch cannot revive stale sample");
        require(source.resolve(key, sixtyFour) == sixtyFour, "old layout did not overwrite current maximum64");
        require(observe(source, key, key, 1, 2.125f, frame, sixtyFour), "new dispatch establishes new maximum revision");
        require(!observe(source, key, key, 1, 2.125f, frame, sixtyFour), "duplicate full dispatch is stable");
        source.resolve(key, maximum); observe(source, key, key, 1, 2.125f, frame, zero);
        require(source.resolve(key, maximum).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(Insets.NONE), "unchanged max144 retains valid actual0 correction");
        WindowInsets keyboard = new WindowInsets.Builder(sixtyFour).setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 300)).setVisible(WindowInsets.Type.ime(), true).build();
        require(source.resolve(key, keyboard, true).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(Insets.NONE) && source.deferred(), "editor keeps same-geometry layout during keyboard navigation changes");
        require(!observe(source, key, key, 1, 2.125f, frame, keyboard), "deferred revision cannot accept a new sample");
        require(source.resolve(key, maximum, false).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(Insets.NONE) && !source.deferred(), "keyboard closing to original maximum preserves correction without rebuild");
        source.resolve(key, sixtyFour, true);
        require(source.resolve(key, sixtyFour, false) == sixtyFour, "editor ending applies a genuinely changed maximum");
        long beforeReset = source.sampleRevision(); source.reset(); source.resolve(key, sixtyFour);
        require(!source.observe(key, key, 1, 2.125f, frame, zero, beforeReset), "same geometry after release rejects former session event");
        require(source.resolve(key, null).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(edge(key.rotation(), 64)), "temporary read failure keeps the last known maximum safety");
        observe(source, key, key, 1, 2.125f, frame, sixtyFour);
        require(source.resolve(key, null).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(edge(key.rotation(), 64)), "temporary maximum failure retains validated current evidence");
        source.reset(); source.resolve(key, sixtyFour);
        long beforeEditing = source.sampleRevision();
        require(source.resolve(key, maximum, true).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(edge(key.rotation(), 64)) && source.deferred(), "editor also freezes established fallback before first full sample");
        require(!source.observe(key, key, 1, 2.125f, frame, zero, beforeEditing), "pre-editor pending layout cannot replace a frozen fallback");
        require(source.resolve(key, null, true).getInsets(WindowInsets.Type.mandatorySystemGestures()).equals(edge(key.rotation(), 64)) && source.deferred(), "read failure during editing cannot lose pending refresh");
        require(source.resolve(key, maximum, false) == maximum && !source.deferred(), "ending fallback-only edit applies pending metrics");
        WindowInsets ninetySix = insets(null, edge(key.rotation(), 96), Insets.NONE, Insets.NONE, false);
        source.resolve(key, ninetySix); observe(source, key, key, 1, 2.125f, frame, ninetySix);
        require(source.resolve(key, zero) == zero, "old full96 cannot preserve a high white bar after system safety becomes zero");
        require(source.resolve(key, ninetySix) == ninetySix, "a genuinely current96 remains96 rather than being clamped to66");
    }
    private static Insets edge(int rotation, int value) { return switch (rotation) { case 0 -> Insets.of(0, 0, 0, value); case 1 -> Insets.of(0, 0, value, 0); case 2 -> Insets.of(0, value, 0, 0); default -> Insets.of(value, 0, 0, 0); }; }
    private static WindowInsets insets(DisplayCutout cutout, Insets mandatory, Insets navigation, Insets hiddenNavigation, boolean visible) {
        return new WindowInsets.Builder().setDisplayCutout(cutout).setInsets(WindowInsets.Type.mandatorySystemGestures(), mandatory)
            .setInsets(WindowInsets.Type.navigationBars(), visible ? navigation : Insets.NONE).setVisible(WindowInsets.Type.navigationBars(), visible)
            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), hiddenNavigation).build();
    }
}
