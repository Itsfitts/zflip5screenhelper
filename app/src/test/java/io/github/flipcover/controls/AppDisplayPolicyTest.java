package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppDisplayPolicyTest {
    private static final String APP = "example.reader", OTHER = "example.maps";
    private String entry(String name, int dpi, double scale) throws Exception { return AppDisplayPolicy.entry(name, new AppDisplayPolicy.Config(dpi, scale)); }
    private static final class Access implements AppDisplayPolicy.Access {
        String raw; int reads, checks; boolean loseDisplay, reject, ignoreWrite, alterAfterWrite; final List<String> writes = new ArrayList<>();
        Access(String value) { raw = value; }
        public String read() { reads++; return raw; }
        public void write(String value) { writes.add(value); if (reject) throw new IllegalStateException("denied"); if (!ignoreWrite) raw = value; if (alterAfterWrite) { raw = ""; alterAfterWrite = false; } }
        public void check() { checks++; if (loseDisplay && checks > 1) throw new IllegalStateException("display gone"); }
    }
    @Test public void packageProtocolRoundTripsAndDoesNotUseDisplayWideCommands() throws Exception {
        // Independent OpenSSL AES-128-CBC vector using the literal Samsung IV bytes.
        assertEquals("L2eF1M3Z1E3OVQ1PaKTFQmJNA1pICRTZxT0HzAlSRQg=", AppDisplayPolicy.encodedPackage("com.android.settings"));
        String value = entry(APP, 480, 2.5); assertEquals(value, AppDisplayPolicy.find(value, APP)); assertEquals("", AppDisplayPolicy.find(value, OTHER));
        assertEquals(480, AppDisplayPolicy.config(value).dpi()); assertEquals(2.5, AppDisplayPolicy.config(value).scale(), 0);
        assertEquals(AppDisplayPolicy.encodedPackage(APP), AppDisplayPolicy.encodedPackage(APP)); assertNotEquals(APP, AppDisplayPolicy.encodedPackage(APP));
    }
    @Test public void presetsAreMonotonicAroundExistingBaseline() throws Exception {
        for (String original : new String[]{"", entry(APP, 480, 2), entry(APP, 640, 2.5)}) {
            double baseline = AppDisplayPolicy.baseline(original).effectiveDpi();
            for (int index : new int[]{0, 1, 3, 4}) assertEquals(AppDisplayPolicy.baseline(original).dpi(), AppDisplayPolicy.preset(original, index).dpi());
            double small = AppDisplayPolicy.preset(original, 0).effectiveDpi(), smaller = AppDisplayPolicy.preset(original, 1).effectiveDpi(), large = AppDisplayPolicy.preset(original, 3).effectiveDpi(), larger = AppDisplayPolicy.preset(original, 4).effectiveDpi();
            assertTrue(small < smaller && smaller < baseline && baseline < large && large < larger);
            assertEquals(BuildConfig.APPEARANCE_APP_DISPLAY_SMALLER_RATIO, small / baseline, .003); assertEquals(BuildConfig.APPEARANCE_APP_DISPLAY_LARGER_RATIO, larger / baseline, .003);
        }
        assertThrows(IllegalArgumentException.class, () -> AppDisplayPolicy.preset("", 2));
    }
    @Test public void compatibilityPresetsChangeScaleWithoutAccumulatingOrReplacingDpi() throws Exception {
        String original = entry(APP, 480, 2);
        assertEquals(new AppDisplayPolicy.Config(480, 2.5), AppDisplayPolicy.preset(original, 0));
        assertEquals(new AppDisplayPolicy.Config(480, 1.667), AppDisplayPolicy.preset(original, 4));
        assertEquals(640, AppDisplayPolicy.preset(entry(APP, 640, 2.5), 1).dpi());
        assertThrows(IllegalArgumentException.class, () -> AppDisplayPolicy.preset(entry(APP, 480, 4), 0));
        assertThrows(IllegalArgumentException.class, () -> AppDisplayPolicy.preset(entry(APP, 480, .5), 4));
    }
    @Test public void oldDpiPresetsRemainCustomUntilExplicitlyReplaced() throws Exception {
        String original = entry(APP, 480, 2), oldDpiPreset = entry(APP, 384, 2);
        assertEquals(AppDisplayPolicy.CUSTOM, AppDisplayPolicy.selectedPreset(original, oldDpiPreset, 0));
        assertEquals(0, AppDisplayPolicy.selectedPreset(original, AppDisplayPolicy.entry(APP, AppDisplayPolicy.preset(original, 0)), 0));
        assertEquals(AppDisplayPolicy.DEFAULT, AppDisplayPolicy.selectedPreset(original, original, AppDisplayPolicy.DEFAULT));
        assertEquals(AppDisplayPolicy.CUSTOM, AppDisplayPolicy.selectedPreset(original, original, AppDisplayPolicy.CUSTOM));
        assertEquals(AppDisplayPolicy.CUSTOM, AppDisplayPolicy.selectedPreset(original, "", 0));
    }
    @Test public void samsungScaleOneIsNotTreatedAsLiteralMagnification() throws Exception {
        String original = AppDisplayPolicy.encodedPackage(APP) + ",480,1.0";
        assertEquals(340, AppDisplayPolicy.baseline(original).effectiveDpi(), 0);
        assertThrows(IllegalArgumentException.class, () -> new AppDisplayPolicy.Config(480, 1).validate());
    }
    @Test public void customInputRejectsNonfiniteAndUnreadableSizes() {
        for (double scale : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0, .499, 1, 4.01}) assertThrows(IllegalArgumentException.class, () -> new AppDisplayPolicy.Config(480, scale).validate());
        for (int dpi : new int[]{-1, 0, 119, 961}) assertThrows(IllegalArgumentException.class, () -> new AppDisplayPolicy.Config(dpi, 2).validate());
        assertThrows(IllegalArgumentException.class, () -> new AppDisplayPolicy.Config(120, 4).validate());
        assertThrows(IllegalArgumentException.class, () -> new AppDisplayPolicy.Config(960, .75).validate());
        new AppDisplayPolicy.Config(480, 4).validate(); new AppDisplayPolicy.Config(960, 2).validate();
    }
    @Test public void measuredMagnificationRangeIsEditableButOneRemainsSpecial() throws Exception {
        for (double scale : new double[]{.5, .75, .9, 1.001, 1.01}) new AppDisplayPolicy.Config(480, scale).validate();
        assertEquals(960, AppDisplayPolicy.config(entry(APP, 480, .5)).effectiveDpi(), 0);
        assertEquals("size_small", assertThrows(IllegalArgumentException.class, () -> new AppDisplayPolicy.Config(120, 1.01).validate()).getMessage());
        assertEquals("size_large", assertThrows(IllegalArgumentException.class, () -> new AppDisplayPolicy.Config(481, .5).validate()).getMessage());
        assertEquals(1.001, AppDisplayPolicy.preset(entry(APP, 480, 1.2), 4).scale(), 0);
    }
    @Test public void unrelatedEntriesAndOrderRemainByteForByte() throws Exception {
        String old = entry(APP, 480, 2), other = entry(OTHER, 540, 2.5), changed = entry(APP, 400, 2);
        assertEquals(other + ";" + changed, AppDisplayPolicy.replace(other + ";" + old, APP, changed));
        assertEquals(other, AppDisplayPolicy.replace(other + ";" + old, APP, ""));
        assertEquals(other + ";" + old, AppDisplayPolicy.replace(other, APP, old));
    }
    @Test public void wrongPackageAndAmbiguousFormatsNeverMutate() throws Exception {
        String own = entry(APP, 480, 2), other = entry(OTHER, 480, 2);
        assertThrows(IllegalArgumentException.class, () -> AppDisplayPolicy.replace(own, APP, other));
        for (String raw : new String[]{own + ";" + own, own + ";", "garbled", own + ";invalid,480,2", own.replace(",2.0", ",NaN")}) assertThrows(IllegalArgumentException.class, () -> AppDisplayPolicy.find(raw, APP));
        for (String name : new String[]{"", "a", "a;b.c", "a.b\n", "a.b $(id)"}) assertThrows(IllegalArgumentException.class, () -> AppDisplayPolicy.packageName(name));
    }
    @Test public void restoreAbsentEntryPreservesOtherApplications() throws Exception {
        String other = entry(OTHER, 480, 2), wanted = entry(APP, 400, 2); Access access = new Access(other);
        AppDisplayPolicy.change(access, APP, "", wanted); AppDisplayPolicy.change(access, APP, wanted, ""); assertEquals(other, access.raw);
    }
    @Test public void restorePreservesOriginalFormattingAndSpecialParameters() throws Exception {
        String original = AppDisplayPolicy.encodedPackage(APP) + ",0,1.000"; String wanted = entry(APP, 480, 2); Access access = new Access(original);
        AppDisplayPolicy.change(access, APP, original, wanted); AppDisplayPolicy.change(access, APP, wanted, original); assertEquals(original, access.raw);
    }
    @Test public void externalTargetChangeStopsWithoutOverwriting() throws Exception {
        String original = entry(APP, 480, 2), external = entry(APP, 520, 2); Access access = new Access(external);
        assertThrows(AppDisplayPolicy.Conflict.class, () -> AppDisplayPolicy.change(access, APP, original, entry(APP, 400, 2))); assertTrue(access.writes.isEmpty()); assertEquals(external, access.raw);
    }
    @Test public void freshReadMergesOtherToolsUnrelatedChanges() throws Exception {
        String original = entry(APP, 480, 2), external = entry(OTHER, 520, 2), wanted = entry(APP, 400, 2); Access access = new Access(original + ";" + external);
        AppDisplayPolicy.change(access, APP, original, wanted); assertEquals(wanted + ";" + external, access.raw);
    }
    @Test public void sessionLossPreventsAWrite() throws Exception {
        Access access = new Access(null); access.loseDisplay = true;
        assertThrows(IllegalStateException.class, () -> AppDisplayPolicy.change(access, APP, "", entry(APP, 480, 2))); assertTrue(access.writes.isEmpty());
    }
    @Test public void acceptedButUnappliedWriteDoesNotClaimSuccess() throws Exception {
        Access access = new Access(null); access.ignoreWrite = true;
        assertThrows(IllegalStateException.class, () -> AppDisplayPolicy.change(access, APP, "", entry(APP, 480, 2))); assertNull(access.raw);
    }
    @Test public void denialAndReadbackMismatchAreFailures() throws Exception {
        Access denied = new Access(null); denied.reject = true;
        assertThrows(IllegalStateException.class, () -> AppDisplayPolicy.change(denied, APP, "", entry(APP, 480, 2)));
        Access changed = new Access(null); changed.alterAfterWrite = true;
        assertThrows(IllegalStateException.class, () -> AppDisplayPolicy.change(changed, APP, "", entry(APP, 480, 2))); assertEquals("", changed.raw); assertEquals(1, changed.writes.size());
    }
    @Test public void unchangedValueIsANoOp() throws Exception {
        String original = entry(APP, 480, 2); Access access = new Access(original); assertEquals(original, AppDisplayPolicy.change(access, APP, original, original)); assertTrue(access.writes.isEmpty());
    }
    @Test public void membershipIsExactAndScopedToUser() {
        assertTrue(AppDisplayPolicy.member(APP + ",0;" + OTHER + ",10", APP, 0, true));
        assertFalse(AppDisplayPolicy.member(APP + ",10", APP, 0, true)); assertFalse(AppDisplayPolicy.member(APP + ".extra,0", APP, 0, true)); assertFalse(AppDisplayPolicy.member(APP, APP, 0, true));
    }
}
