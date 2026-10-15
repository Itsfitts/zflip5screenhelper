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
    /** The page is fixed. Fit chrome only in exterior regions, never reserve space inside it. */
    static DockGeometry.Placement outside(DockGeometry.Placement source, DockGeometry.Box page, int width, int height, List<DockGeometry.Box> cutouts, float density) {
        return outside(source, page, width, height, cutouts, density, new DockGeometry.Box(0, 0, width, height));
    }
    static DockGeometry.Placement outside(DockGeometry.Placement source, DockGeometry.Box page, int width, int height, List<DockGeometry.Box> cutouts, float density, DockGeometry.Box allowed) {
        allowed = allowed.intersect(new DockGeometry.Box(0, 0, width, height));
        if (source.touch().intersect(allowed).equals(source.touch()) && !overlaps(source.touch(), page) && cutouts.stream().noneMatch(c -> overlaps(source.touch(), c))) return new DockGeometry.Placement(source.visual(), source.touch(), page, source.edge(), source.measured());
        DockGeometry.Placement best = null; long bestLoss = Long.MAX_VALUE, bestDistance = Long.MAX_VALUE;
        int margin = Math.max(4, Math.round(3 * density));
        for (int edge = 0; edge < 4; edge++) {
            DockGeometry.Box band = switch (edge) {
                case DockGeometry.TOP -> new DockGeometry.Box(0, 0, width, page.y());
                case DockGeometry.LEFT -> new DockGeometry.Box(0, 0, page.x(), height);
                case DockGeometry.RIGHT -> new DockGeometry.Box(page.right(), 0, width - page.right(), height);
                default -> new DockGeometry.Box(0, page.bottom(), width, height - page.bottom());
            };
            java.util.ArrayList<DockGeometry.Box> spaces = new java.util.ArrayList<>(); spaces.add(band.intersect(allowed));
            for (DockGeometry.Box cut : cutouts) {
                java.util.ArrayList<DockGeometry.Box> clear = new java.util.ArrayList<>();
                for (DockGeometry.Box space : spaces) {
                    DockGeometry.Box hit = space.intersect(cut);
                    if (hit.width() == 0 || hit.height() == 0) { clear.add(space); continue; }
                    clear.add(new DockGeometry.Box(space.x(), space.y(), space.width(), hit.y() - space.y()));
                    clear.add(new DockGeometry.Box(space.x(), hit.bottom(), space.width(), space.bottom() - hit.bottom()));
                    clear.add(new DockGeometry.Box(space.x(), hit.y(), hit.x() - space.x(), hit.height()));
                    clear.add(new DockGeometry.Box(hit.right(), hit.y(), space.right() - hit.right(), hit.height()));
                }
                spaces = clear;
            }
            boolean vertical = edge == DockGeometry.LEFT || edge == DockGeometry.RIGHT;
            int oldLength = source.vertical() ? source.touch().height() : source.touch().width(), oldThickness = source.vertical() ? source.touch().width() : source.touch().height();
            if (oldLength <= 0 || oldThickness <= 0) continue;
            for (DockGeometry.Box space : spaces) {
                int length = Math.min(oldLength, (vertical ? space.height() : space.width()) - margin * 2);
                int thickness = Math.min(oldThickness, (vertical ? space.width() : space.height()) - margin);
                if (length < Math.round(90 * density) || thickness < Math.round(14 * density)) continue;
                int start = vertical ? space.y() : space.x(), end = vertical ? space.bottom() : space.right();
                int wanted = edge == source.edge() ? (vertical ? source.touch().y() : source.touch().x()) : (start + end > (vertical ? height : width) ? end - margin - length : start + margin);
                int along = clamp(wanted, start + margin, end - margin - length);
                int across = switch (edge) { case DockGeometry.LEFT -> space.x(); case DockGeometry.RIGHT -> space.right() - thickness; case DockGeometry.TOP -> space.y(); default -> space.bottom() - thickness; };
                DockGeometry.Box touch = vertical ? new DockGeometry.Box(across, along, thickness, length) : new DockGeometry.Box(along, across, length, thickness);
                int oldVisualLength = source.vertical() ? source.visual().height() : source.visual().width(), oldVisualThickness = source.vertical() ? source.visual().width() : source.visual().height();
                int oldOffset = source.vertical() ? source.visual().y() - source.touch().y() : source.visual().x() - source.touch().x();
                int visualLength = Math.max(1, Math.round(oldVisualLength * length / (float) oldLength)), visualThickness = Math.max(1, Math.round(oldVisualThickness * thickness / (float) oldThickness));
                int visualAlong = along + Math.round(oldOffset * length / (float) oldLength);
                int visualAcross = across + (edge == DockGeometry.LEFT || edge == DockGeometry.TOP ? thickness - visualThickness : 0);
                DockGeometry.Box visual = (vertical ? new DockGeometry.Box(visualAcross, visualAlong, visualThickness, visualLength) : new DockGeometry.Box(visualAlong, visualAcross, visualLength, visualThickness)).intersect(touch);
                long loss = (long) oldLength * oldThickness - (long) length * thickness, distance = Math.abs((long) touch.x() - source.touch().x()) + Math.abs((long) touch.y() - source.touch().y());
                boolean preferred = edge == source.edge(), wasPreferred = best != null && best.edge() == source.edge();
                if (best == null || preferred && !wasPreferred || preferred == wasPreferred && (loss < bestLoss || loss == bestLoss && distance < bestDistance)) {
                    best = new DockGeometry.Placement(visual, touch, page, edge, source.measured()); bestLoss = loss; bestDistance = distance;
                }
            }
        }
        DockGeometry.Box empty = new DockGeometry.Box(0, 0, 0, 0);
        return best == null ? new DockGeometry.Placement(empty, empty, page, source.edge(), source.measured()) : best;
    }
    static boolean overlaps(DockGeometry.Box a, DockGeometry.Box b) { return a.width() > 0 && a.height() > 0 && b.width() > 0 && b.height() > 0 && a.x() < b.right() && a.right() > b.x() && a.y() < b.bottom() && a.bottom() > b.y(); }
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
