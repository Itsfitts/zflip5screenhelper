package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppPortraitBoundsTest {
    @Test public void defaultCoverUsesTwelveToSixteenAboveCameraCutout() {
        assertEquals(new AppPortraitBounds.Bounds(129, 0, 490, 654), AppPortraitBounds.fit(748, 720, 0, 0, 0, 66, AppPortraitBounds.DEFAULT_RATIO));
    }
    @Test public void allFourRatiosPreserveHeightAndAvoidCutout() {
        int[] widths = {294, 366, 490, 612};
        for (int i = 0; i < widths.length; i++) {
            AppPortraitBounds.Bounds value = AppPortraitBounds.fit(748, 720, 0, 0, 0, 66, AppPortraitBounds.RATIOS.get(i));
            assertEquals(widths[i], value.width()); assertEquals(654, value.height());
            assertEquals((748 - widths[i]) / 2, value.x()); assertEquals(0, value.y());
        }
    }
    @Test public void reversedCoverKeepsRealTopInset() {
        assertEquals(new AppPortraitBounds.Bounds(129, 66, 490, 654), AppPortraitBounds.fit(748, 720, 0, 66, 0, 0, "12:16"));
    }
    @Test public void rotatedCoverKeepsAreaInsideSafeRectangle() {
        AppPortraitBounds.Bounds value = AppPortraitBounds.fit(720, 748, 66, 0, 0, 0, "12:16");
        assertTrue(value.x() >= 66 && value.x() + value.width() <= 720);
        assertTrue(value.width() < value.height()); assertEquals(748, value.height());
    }
    @Test public void widthLimitedAreaReducesHeightWithoutStretching() {
        assertEquals(new AppPortraitBounds.Bounds(0, 208, 360, 384), AppPortraitBounds.fit(360, 800, 0, 0, 0, 0, "15:16"));
    }
    @Test(expected = IllegalArgumentException.class) public void unknownRatioIsRejected() { AppPortraitBounds.fit(748, 720, 0, 0, 0, 66, "16:9"); }
    @Test(expected = IllegalArgumentException.class) public void impossibleSafeAreaIsRejected() { AppPortraitBounds.fit(748, 720, 0, 0, 0, 650, "12:16"); }
}
