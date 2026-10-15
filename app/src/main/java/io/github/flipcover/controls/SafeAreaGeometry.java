package io.github.flipcover.controls;

import java.util.List;

/** Display-coordinate safety. Rotation is already applied by the display; never mirror cutouts. */
final class SafeAreaGeometry {
    record Edges(int left, int top, int right, int bottom) {
        static final Edges NONE = new Edges(0, 0, 0, 0);
        boolean valid(int width, int height) {
            return left >= -width && top >= -height && right >= -width && bottom >= -height
                && (long) Math.max(0, left) + Math.max(0, right) <= width - Math.min(width, 160)
                && (long) Math.max(0, top) + Math.max(0, bottom) <= height - Math.min(height, 160);
        }
        Edges clipped() { return new Edges(Math.max(0, left), Math.max(0, top), Math.max(0, right), Math.max(0, bottom)); }
        Edges plus(Edges other) { return new Edges(Math.addExact(left, other.left), Math.addExact(top, other.top), Math.addExact(right, other.right), Math.addExact(bottom, other.bottom)); }
        Edges minus(Edges other) { return new Edges(left - other.left, top - other.top, right - other.right, bottom - other.bottom); }
        DockGeometry.Box frame(int width, int height) { Edges e = clipped(); return new DockGeometry.Box(e.left, e.top, Math.max(0, width - e.left - e.right), Math.max(0, height - e.top - e.bottom)); }
    }
    record Profile(boolean manual, int width, int height, String display, Edges edges, Edges reference) {
        Profile(boolean manual, int width, int height, String display, Edges edges) { this(manual, width, height, display, edges, null); }
        Edges offsets() { return reference == null ? edges : edges.minus(reference); }
        boolean acceptsOffset(int edge, int value) { int extent = edge % 2 == 0 ? width : height; return edge >= 0 && edge < 4 && value >= -extent && value <= Math.max(0, extent - 160); }
        boolean matches(String identity, int w, int h) { return display.equals(identity) && width == w && height == h && edges.valid(w, h); }
    }
    static DockGeometry.Placement constrain(DockGeometry.Placement source, DockGeometry.Box frame, List<DockGeometry.Box> cutouts) {
        DockGeometry.Box touch = source.touch(), visual = source.visual();
        int width = Math.min(touch.width(), frame.width()), height = Math.min(touch.height(), frame.height());
        DockGeometry.Box fitted = new DockGeometry.Box(clamp(touch.x(), frame.x(), frame.right() - width), clamp(touch.y(), frame.y(), frame.bottom() - height), width, height);
        for (DockGeometry.Box cut : cutouts) {
            if (!overlaps(fitted, cut)) continue;
            DockGeometry.Box best = null; long distance = Long.MAX_VALUE;
            for (DockGeometry.Box candidate : List.of(new DockGeometry.Box(cut.x() - width, fitted.y(), width, height), new DockGeometry.Box(cut.right(), fitted.y(), width, height), new DockGeometry.Box(fitted.x(), cut.y() - height, width, height), new DockGeometry.Box(fitted.x(), cut.bottom(), width, height))) {
                if (!candidate.intersect(frame).equals(candidate) || cutouts.stream().anyMatch(c -> overlaps(candidate, c))) continue;
                long delta = Math.abs((long) candidate.x() - touch.x()) + Math.abs((long) candidate.y() - touch.y());
                if (delta < distance) { best = candidate; distance = delta; }
            }
            if (best == null) return new DockGeometry.Placement(new DockGeometry.Box(frame.x(), frame.y(), 0, 0), new DockGeometry.Box(frame.x(), frame.y(), 0, 0), source.panel().intersect(frame), source.edge(), source.measured());
            fitted = best;
        }
        DockGeometry.Box moved = new DockGeometry.Box(visual.x() + fitted.x() - touch.x(), visual.y() + fitted.y() - touch.y(), visual.width(), visual.height()).intersect(fitted);
        return new DockGeometry.Placement(moved, fitted, source.panel().intersect(frame), source.edge(), source.measured());
    }
    static boolean overlaps(DockGeometry.Box a, DockGeometry.Box b) { return a.x() < b.right() && a.right() > b.x() && a.y() < b.bottom() && a.bottom() > b.y(); }
    static DockGeometry.Box localFrame(DockGeometry.Box displayFrame, float scale, int x, int y, int width, int height) {
        return localFrame(displayFrame, scale, scale, x, y, width, height);
    }
    static DockGeometry.Box localFrame(DockGeometry.Box displayFrame, float scaleX, float scaleY, int x, int y, int width, int height) {
        int left = clamp(Math.round(displayFrame.x() * scaleX) - x, 0, width), top = clamp(Math.round(displayFrame.y() * scaleY) - y, 0, height);
        int right = clamp(Math.round(displayFrame.right() * scaleX) - x, left, width), bottom = clamp(Math.round(displayFrame.bottom() * scaleY) - y, top, height);
        return new DockGeometry.Box(left, top, right - left, bottom - top);
    }
    private static int clamp(int n, int min, int max) { return Math.max(min, Math.min(max, n)); }
}
