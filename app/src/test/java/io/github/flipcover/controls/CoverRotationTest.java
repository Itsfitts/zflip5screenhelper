package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoverRotationTest {
    @Test public void systemOwnershipIsNotReportedAsALock() {
        assertEquals(0, CoverRotation.systemMode(true, "free\n"));
        for (int angle = 0; angle < 4; angle++) assertEquals(1, CoverRotation.systemMode(true, "lock " + angle));
        for (String text : new String[]{null, "", "lock 4", "free\nError:", "unknown"}) assertEquals(-1, CoverRotation.systemMode(true, text));
        assertEquals(-1, CoverRotation.systemMode(false, "lock 0"));
        assertEquals("系统旋转", CoverRotation.label(false, 0)); assertEquals("旋转锁定", CoverRotation.label(false, 1));
        assertEquals("旋转状态未知", CoverRotation.label(false, -1)); assertEquals("工具自动旋转", CoverRotation.label(true, -1));
    }
    @Test public void calibratedOrientationSupportsEveryMountAndWraparound() {
        for (int rotation = 0; rotation < 4; rotation++) {
            assertEquals(rotation, CoverRotation.target(350, 350, rotation));
            assertEquals((rotation + 3) % 4, CoverRotation.target(350, 80, rotation));
            assertEquals((rotation + 2) % 4, CoverRotation.target(350, 170, rotation));
            assertEquals((rotation + 1) % 4, CoverRotation.target(350, 260, rotation));
        }
    }
    @Test public void intermediateAndFlatAnglesDoNotTriggerRotation() {
        assertEquals(-1, CoverRotation.target(0, -1, 0)); assertEquals(-1, CoverRotation.target(0, 45, 0)); assertEquals(-1, CoverRotation.target(0, 130, 0)); assertEquals(0, CoverRotation.target(0, 20, 0));
    }
}
