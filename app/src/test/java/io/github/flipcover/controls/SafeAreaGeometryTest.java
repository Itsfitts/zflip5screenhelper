package io.github.flipcover.controls;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SafeAreaGeometryTest {
    @Test public void calibrationIsBoundToDisplayAndLayoutSize() {
        var profile = new SafeAreaGeometry.Profile(true, 748, 720, "cover", new SafeAreaGeometry.Edges(0, 0, 0, 0));
        assertTrue(profile.matches("cover", 748, 720)); assertFalse(profile.matches("other", 748, 720)); assertFalse(profile.matches("cover", 1496, 1440)); assertFalse(profile.matches("cover", 720, 748));
    }
    @Test public void invalidAndOverflowingInsetsCannotCollapseTheScreen() {
        assertFalse(new SafeAreaGeometry.Edges(-1, 0, 0, 0).valid(748, 720));
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
}
