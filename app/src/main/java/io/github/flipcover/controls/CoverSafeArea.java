package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import java.util.ArrayList;
import java.util.List;

/** The sole automatic/manual policy boundary. Consumers receive resolved geometry only. */
final class CoverSafeArea {
    record Snapshot(int displayId, String identity, int rotation, int width, int height, float density,
                    boolean manual, boolean mismatch, Insets automatic, Insets effective, List<DockGeometry.Box> cutouts) {
        DockGeometry.Box frame() { return new SafeAreaGeometry.Edges(effective.left, effective.top, effective.right, effective.bottom).frame(width, height); }
        SafeAreaGeometry.Edges automaticEdges() { return new SafeAreaGeometry.Edges(automatic.left, automatic.top, automatic.right, automatic.bottom); }
        String diagnostics() { return "安全区=" + (manual ? "手动" : "自动") + (mismatch ? "（校准尺寸或外屏不匹配，手动值已保留）" : "") + ", rotation=" + rotation + ", size=" + width + "×" + height + "\n安全区自动边衬=" + automatic + "\n安全区生效边衬=" + effective + "\n安全区矩形=" + frame(); }
    }
    record Layout(DockGeometry.Placement dock, DockGeometry.Placement entry, DockGeometry.Box card, DockGeometry.Box status,
                  DockGeometry.Box controlStatus, DockGeometry.Box hub, DockGeometry.Box hubAvailable, DockGeometry.Box tasks, DockGeometry.Box widget) { }
    record Chrome(DockGeometry.Placement dock, DockGeometry.Placement anchor, int homeInset, int navigationInset, Insets handleInsets) { }
    static Chrome chrome(Context context, Prefs prefs, Snapshot area, Insets mandatory, Insets navigation, Insets navigationBounds) {
        DockGeometry.Placement anchor = DockGeometry.resolve(area.width(), area.height(), area.cutouts(), area.density(), prefs.corner(area.rotation()), prefs.widthRatio(), prefs.heightRatio(), prefs.data.getBoolean("auto_placement", true), area.rotation(), prefs.reverseLandscapeLayout(area.rotation()));
        DockGeometry.Placement dock = anchor; int navigationInset = 0;
        if (area.manual()) {
            dock = SafeAreaGeometry.constrain(DockGeometry.edgeTouch(anchor, area.width(), area.height()), area.frame(), area.cutouts());
            return new Chrome(dock, dock, area.effective().bottom, 0, area.effective());
        }
        if (prefs.avoidNavigation()) {
            Insets safe = Insets.max(mandatory, navigationBounds);
            navigationInset = switch (dock.edge()) { case DockGeometry.TOP -> safe.top; case DockGeometry.LEFT -> safe.left; case DockGeometry.RIGHT -> safe.right; default -> safe.bottom; };
            dock = DockGeometry.avoidEdge(dock, area.width(), area.height(), Math.max(navigationInset, Ui.dp(context, 16)), Ui.dp(context, prefs.navigationGap()));
        } else dock = DockGeometry.edgeTouch(dock, area.width(), area.height());
        return new Chrome(dock, anchor, Math.max(mandatory.bottom, navigation.bottom), navigationInset, Insets.max(area.effective(), mandatory));
    }
    static String identity(Display display) { return display.getDisplayId() + ":" + display.getName() + ":" + display.getMode().getPhysicalWidth() + "x" + display.getMode().getPhysicalHeight(); }
    static Insets automaticInsets(WindowInsets insets, DisplayCutout cutout) {
        Insets safe = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.mandatorySystemGestures() | WindowInsets.Type.displayCutout());
        return Insets.max(safe, cutoutInsets(cutout));
    }
    static Insets cutoutInsets(DisplayCutout cutout) { return cutout == null ? Insets.NONE : Insets.of(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(), cutout.getSafeInsetRight(), cutout.getSafeInsetBottom()); }
    static Snapshot resolve(Context context, Prefs prefs, Display display, Point size, Insets automatic, SafeAreaGeometry.Profile preview) {
        SafeAreaGeometry.Profile profile = preview == null ? prefs.safeArea(display.getRotation()) : preview;
        boolean matches = profile != null && profile.matches(identity(display), size.x, size.y);
        boolean manual = matches && profile.manual();
        Insets effective = automatic;
        if (manual) { SafeAreaGeometry.Edges e = profile.edges(); effective = Insets.of(e.left(), e.top(), e.right(), e.bottom()); }
        List<DockGeometry.Box> cutouts = new ArrayList<>();
        if (display.getCutout() != null) for (android.graphics.Rect r : display.getCutout().getBoundingRects()) cutouts.add(new DockGeometry.Box(r.left, r.top, r.width(), r.height()));
        return new Snapshot(display.getDisplayId(), identity(display), display.getRotation(), size.x, size.y, context.getResources().getDisplayMetrics().density, manual, profile != null && profile.manual() && !matches, automatic, effective, List.copyOf(cutouts));
    }
    static Layout contents(Context context, Prefs prefs, Snapshot area, DockGeometry.Placement dock, DockGeometry.Placement anchor, int homeInset) {
        int width = area.width(), height = area.height(), rotation = area.rotation(); float density = area.density(); List<DockGeometry.Box> cutouts = area.cutouts();
        DockGeometry.Box systemSafe = area.frame(), physical = DockGeometry.panelContent(dock, width, height, cutouts), card = physical.intersect(systemSafe);
        int statusHeight = Math.round(Ui.dp(context, 20) * prefs.statusScale() / 100f);
        DockGeometry.Box controlStatus = DockGeometry.statusBar(card, width, statusHeight, density).intersect(systemSafe);
        String position = prefs.entryPosition(rotation);
        int topInset = DockGeometry.panelEntryTopInset(position, prefs.statusScale(), density);
        if (!position.equals("bottom_right")) topInset += Math.max(0, card.y() - physical.y());
        DockGeometry.Box entrySpace = area.manual() ? DockGeometry.physicalContent(width, height, cutouts).intersect(systemSafe) : null;
        if (area.manual() && !position.equals("bottom_right")) topInset = DockGeometry.panelEntryTopInset(position, prefs.statusScale(), density) + Math.max(0, card.y() - entrySpace.y());
        DockGeometry.Placement entry = DockGeometry.panelEntry(anchor, dock, width, height, cutouts, density, homeInset, position, topInset, entrySpace);
        if (area.manual()) {
            entry = SafeAreaGeometry.constrain(entry, systemSafe, cutouts);
            entry = new DockGeometry.Placement(entry.visual(), entry.touch(), DockGeometry.panelContent(entry, width, height, cutouts).intersect(systemSafe), entry.edge(), entry.measured());
        }
        DockGeometry.Box content = physical.intersect(entry.panel()), status = prefs.statusEnabled() ? controlStatus : null;
        if (status != null) { int top = Math.max(content.y(), status.bottom() + Ui.dp(context, 4)); content = new DockGeometry.Box(content.x(), top, content.width(), Math.max(0, content.bottom() - top)); }
        DockGeometry.Placement chrome = new DockGeometry.Placement(dock.visual(), dock.touch(), content, dock.edge(), dock.measured());
        DockGeometry.Box hub = DockGeometry.hubContent(chrome, width, height, cutouts, systemSafe), available = hub, tasks = hub;
        content = content.intersect(systemSafe);
        if (rotation == android.view.Surface.ROTATION_180) {
            entry = DockGeometry.compactTopEntry(entry, density);
            DockGeometry.Box space = physical.intersect(entry.panel()).intersect(systemSafe);
            content = DockGeometry.centerVertically(content, space);
            available = new DockGeometry.Box(hub.x(), space.y(), hub.width(), Math.max(0, hub.bottom() - space.y()));
            tasks = DockGeometry.centerVertically(hub, available);
        }
        DockGeometry.Placement result = new DockGeometry.Placement(chrome.visual(), chrome.touch(), content, chrome.edge(), chrome.measured());
        return new Layout(result, entry, card, status, controlStatus, hub, available, tasks, DockGeometry.widgetContent(result, entry, status, width, height, cutouts, density).intersect(systemSafe));
    }
    /** Activity windows can be smaller/offset by their host. Convert once from display to local pixels. */
    static Insets activityInsets(android.app.Activity activity, android.view.View root, WindowInsets insets) {
        Insets fallback = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        Display current = activity.getDisplay(); Prefs prefs = new Prefs(activity); Display selected = Displays.selected(activity, prefs);
        if (current == null || selected == null || current.getDisplayId() != selected.getDisplayId()) return fallback;
        CoverService service = CoverService.instance; Snapshot area = service == null ? null : service.safeAreaSnapshot();
        if (area == null || area.displayId() != current.getDisplayId() || area.rotation() != current.getRotation()) {
            Point size = Displays.size(current); area = resolve(activity, prefs, current, size, automaticInsets(insets, current.getCutout()), null);
        }
        DockGeometry.Box safe = area.frame().intersect(DockGeometry.physicalContent(area.width(), area.height(), area.cutouts()));
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        int w = root.getWidth(), h = root.getHeight(); if (w <= 0 || h <= 0) return fallback;
        // Samsung compatibility Activities may use twice the overlay's pixel/density coordinates.
        float scale = root.getResources().getDisplayMetrics().density / area.density();
        DockGeometry.Box localFrame = SafeAreaGeometry.localFrame(safe, scale, origin[0], origin[1], w, h);
        Insets local = Insets.of(localFrame.x(), localFrame.y(), w - localFrame.right(), h - localFrame.bottom());
        return Insets.max(local, insets.getInsets(WindowInsets.Type.ime()));
    }
}
