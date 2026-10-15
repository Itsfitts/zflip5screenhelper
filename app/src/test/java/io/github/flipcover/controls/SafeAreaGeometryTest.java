package io.github.flipcover.controls;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SafeAreaGeometryTest {
    @Test public void dockNeverEntersThePageInAnyRotationOrPlacementMode() {
        for (int rotation = 0; rotation < 4; rotation++) for (boolean automatic : new boolean[]{false, true}) for (boolean reversed : new boolean[]{false, true}) {
            int w = rotation % 2 == 0 ? 748 : 720, h = rotation % 2 == 0 ? 720 : 748;
            var cut = switch (rotation) { case 0 -> new DockGeometry.Box(379, 654, 369, 66); case 1 -> new DockGeometry.Box(654, 0, 66, 369); case 2 -> new DockGeometry.Box(0, 0, 369, 66); default -> new DockGeometry.Box(0, 379, 66, 369); };
            var cuts = List.of(cut); var physical = DockGeometry.physicalContent(w, h, cuts);
            var source = DockGeometry.edgeTouch(DockGeometry.resolve(w, h, cuts, 2.125f, (rotation + 3) % 4, .46f, .088f, automatic, rotation, reversed), w, h);
            for (int inset : new int[]{0, 20, 42, 96}) {
                var page = physical.intersect(new SafeAreaGeometry.Edges(inset, inset, inset, inset).frame(w, h));
                var result = SafeAreaGeometry.outside(source, page, w, h, cuts, 2.125f);
                assertTrue(result.touch().width() > 0 && result.touch().height() > 0);
                assertFalse(SafeAreaGeometry.overlaps(result.touch(), page)); assertFalse(SafeAreaGeometry.overlaps(result.visual(), page));
                assertFalse(SafeAreaGeometry.overlaps(result.touch(), cut));
                assertEquals(result.touch(), result.touch().intersect(new DockGeometry.Box(0, 0, w, h)));
                assertEquals(result.visual(), result.visual().intersect(result.touch())); assertEquals(page, result.panel());
            }
        }
    }
    @Test public void reportedLandscapeBottomInsetCannotPushDockIntoContent() {
        for (int rotation : new int[]{1, 3}) {
            var cut = rotation == 1 ? new DockGeometry.Box(654, 0, 66, 369) : new DockGeometry.Box(0, 379, 66, 369);
            var page = rotation == 1 ? new DockGeometry.Box(0, 0, 654, 706) : new DockGeometry.Box(66, 0, 654, 706);
            var anchor = DockGeometry.edgeTouch(DockGeometry.resolve(720, 748, List.of(cut), 2.125f, (rotation + 3) % 4, .46f, .088f, true), 720, 748);
            var result = SafeAreaGeometry.outside(anchor, page, 720, 748, List.of(cut), 2.125f);
            assertEquals(anchor.visual(), result.visual()); assertEquals(anchor.touch(), result.touch());
            assertEquals(654, result.panel().width()); assertFalse(SafeAreaGeometry.overlaps(result.touch(), page));
        }
    }
    @Test public void missingExteriorSpaceHidesDockWithoutShrinkingThePage() {
        var page = new DockGeometry.Box(0, 0, 748, 720);
        var dock = DockGeometry.edgeTouch(DockGeometry.resolve(748, 720, List.of(), 2.125f, 3, .46f, .088f, true), 748, 720);
        var result = SafeAreaGeometry.outside(dock, page, 748, 720, List.of(), 2.125f);
        assertEquals(0, result.touch().width()); assertEquals(0, result.visual().width()); assertEquals(page, result.panel());
        assertFalse(SafeAreaGeometry.overlaps(result.touch(), page));
    }
    @Test public void exteriorFallbackKeepsNavigationClearanceOnEveryEdge() {
        var cut = new DockGeometry.Box(379, 654, 369, 66);
        var source = DockGeometry.edgeTouch(DockGeometry.resolve(748, 720, List.of(cut), 2.125f, 3, .46f, .088f, true), 748, 720);
        var page = new DockGeometry.Box(160, 160, 428, 400);
        for (int gap : new int[]{0, 17, 102}) {
            var allowed = new SafeAreaGeometry.Edges(34 + gap, 34 + gap, 34 + gap, 300 + gap).frame(748, 720);
            var result = SafeAreaGeometry.outside(source, page, 748, 720, List.of(cut), 2.125f, allowed);
            assertEquals(page, result.panel()); assertFalse(SafeAreaGeometry.overlaps(result.touch(), page));
            if (result.touch().width() > 0) {
                assertNotEquals(DockGeometry.BOTTOM, result.edge());
                assertEquals(result.touch(), result.touch().intersect(allowed));
            } else assertEquals(102, gap);
        }
    }
    @Test public void hiddenDockReservesNoContentOrWhiteBarSpace() {
        var full = new DockGeometry.Box(0, 0, 748, 720); var empty = new DockGeometry.Box(0, 0, 0, 0);
        var anchor = DockGeometry.resolve(748, 720, List.of(), 2.125f, 3, .46f, .088f, true);
        for (int edge = 0; edge < 4; edge++) {
            var dock = new DockGeometry.Placement(empty, empty, full, edge, false);
            assertEquals(full, DockGeometry.panelContent(dock, 748, 720, List.of()));
            var entry = DockGeometry.panelEntry(anchor, dock, 748, 720, List.of(), 2.125f, 0, "bottom_right", 0);
            assertEquals(720, entry.visual().bottom()); assertTrue(entry.visual().width() > 200);
            assertEquals(748, entry.panel().width()); assertEquals(686, entry.panel().height());
        }
    }
    @Test public void calibrationIsBoundToDisplayAndLayoutSize() {
        var profile = new SafeAreaGeometry.Profile(true, 748, 720, "cover", new SafeAreaGeometry.Edges(0, 0, 0, 0));
        assertTrue(profile.matches("cover", 748, 720)); assertFalse(profile.matches("other", 748, 720)); assertFalse(profile.matches("cover", 1496, 1440)); assertFalse(profile.matches("cover", 720, 748));
    }
    @Test public void invalidAndOverflowingInsetsCannotCollapseTheScreen() {
        assertFalse(new SafeAreaGeometry.Edges(-749, 0, 0, 0).valid(748, 720));
        assertFalse(new SafeAreaGeometry.Edges(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0).valid(748, 720));
        assertFalse(new SafeAreaGeometry.Edges(0, 350, 0, 350).valid(748, 720));
        assertTrue(new SafeAreaGeometry.Edges(12, 14, 16, 18).valid(748, 720));
    }
    @Test public void visualAndTouchMoveTogetherInsideEveryRotatedBoundary() {
        for (int rotation = 0; rotation < 4; rotation++) {
            int w = rotation % 2 == 0 ? 748 : 720, h = rotation % 2 == 0 ? 720 : 748;
            var cut = switch (rotation) { case 0 -> new DockGeometry.Box(379, 654, 369, 66); case 1 -> new DockGeometry.Box(654, 0, 66, 369); case 2 -> new DockGeometry.Box(0, 0, 369, 66); default -> new DockGeometry.Box(0, 379, 66, 369); };
            var dock = DockGeometry.edgeTouch(DockGeometry.resolve(w, h, List.of(cut), 2.125f, (rotation + 3) % 4, .46f, .088f, true), w, h);
            var frame = new SafeAreaGeometry.Edges(20, 20, 20, 20).frame(w, h);
            var result = SafeAreaGeometry.constrain(dock, frame, List.of(cut));
            assertTrue(result.touch().width() > 0); assertTrue(result.touch().height() > 0);
            assertEquals(result.touch(), result.touch().intersect(frame)); assertEquals(result.visual(), result.visual().intersect(result.touch()));
            assertFalse(SafeAreaGeometry.overlaps(result.touch(), cut));
            assertEquals(dock.visual().x() - dock.touch().x(), result.visual().x() - result.touch().x());
            assertEquals(dock.visual().y() - dock.touch().y(), result.visual().y() - result.touch().y());
        }
    }
    @Test public void zeroManualInsetsPreserveTheUsableNotchSideLane() {
        var cut = new DockGeometry.Box(379, 654, 369, 66);
        var dock = DockGeometry.edgeTouch(DockGeometry.resolve(748, 720, List.of(cut), 2.125f, 3, .46f, .088f, true), 748, 720);
        assertEquals(dock, SafeAreaGeometry.constrain(dock, SafeAreaGeometry.Edges.NONE.frame(748, 720), List.of(cut)));
    }
    @Test public void compatibilityScaledAndOffsetWindowsConvertTheSameDisplayFrameOnce() {
        var safe = new DockGeometry.Box(12, 20, 724, 634);
        assertEquals(new DockGeometry.Box(24, 40, 1448, 1268), SafeAreaGeometry.localFrame(safe, 2, 0, 0, 1496, 1440));
        assertEquals(new DockGeometry.Box(24, 0, 1448, 1268), SafeAreaGeometry.localFrame(safe, 2, 0, 40, 1496, 1400));
        assertEquals(new DockGeometry.Box(0, 0, 300, 400), SafeAreaGeometry.localFrame(safe, 1, 20, 30, 300, 400));
    }
    @Test public void samsungCompatibilityBoundsDoNotUseTheFontDensityRatio() {
        var safe = new DockGeometry.Box(0, 0, 748, 654);
        assertEquals(new DockGeometry.Box(0, 0, 854, 747), SafeAreaGeometry.localFrame(safe, 854f / 748, 822f / 720, 0, 0, 854, 822));
        var rotated = new DockGeometry.Box(0, 0, 654, 684);
        assertEquals(new DockGeometry.Box(0, 0, 747, 781), SafeAreaGeometry.localFrame(rotated, 822f / 720, 854f / 748, 0, 0, 822, 854));
    }
    @Test public void negativeOffsetsExpandFromAFrozenReferenceAndStopAtThePhysicalScreen() {
        var base = new SafeAreaGeometry.Edges(0, 0, 0, 96);
        var absolute = base.plus(new SafeAreaGeometry.Edges(0, 0, 0, -30));
        assertEquals(66, absolute.bottom());
        var profile = new SafeAreaGeometry.Profile(true, 748, 720, "cover", absolute, base);
        assertEquals(-30, profile.offsets().bottom());
        assertTrue(profile.acceptsOffset(3, -30)); assertTrue(profile.acceptsOffset(3, -720));
        assertFalse(profile.acceptsOffset(3, -800)); assertFalse(profile.acceptsOffset(3, Integer.MAX_VALUE));
        var outside = base.plus(new SafeAreaGeometry.Edges(-20, -20, -20, -160));
        assertTrue(outside.valid(748, 720)); assertEquals(new DockGeometry.Box(0, 0, 748, 720), outside.frame(748, 720));
        assertFalse(new SafeAreaGeometry.Edges(-500, 0, 700, 0).valid(748, 720));
    }
}
