package io.github.flipcover.controls;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class DockVisibilityTest {
    private final Set<String> hidden = Set.of("systemui", "launcher", "hidden.app");
    @Test public void arrowModeRetainsBothStatesAcrossAppsAndKeyboardWhenAutoHideIsOff() {
        DockVisibility state = new DockVisibility(); state.expandable(true);
        for (boolean expanded : new boolean[]{false, true, false}) {
            for (String app : new String[]{"browser", "launcher", "systemui", "browser"}) {
                state.foreground(app); state.keyboard(true); assertEquals(!expanded, state.compact(false, hidden));
                state.keyboard(false); assertEquals(!expanded, state.compact(false, hidden));
            }
            state.toggle(false, hidden);
        }
    }
    @Test public void appAutoHideWinsOverBothModesAndDoesNotChangeExpansion() {
        for (boolean arrowMode : new boolean[]{false, true}) {
            DockVisibility state = new DockVisibility(); state.foreground("browser"); state.expandable(arrowMode); state.toggle(true, hidden);
            assertFalse(state.compact(true, hidden));
            for (String app : hidden) {
                state.foreground(app); assertTrue(state.automaticallyCompact(true, hidden)); assertTrue(state.compact(true, hidden));
                state.toggle(true, hidden); state.keyboard(true); state.keyboard(false); assertTrue(state.compact(true, hidden));
            }
            state.foreground("browser"); assertFalse(state.compact(true, hidden));
        }
    }
    @Test public void leavingHiddenAppDoesNotExpandACollapsedArrow() {
        DockVisibility state = new DockVisibility(); state.expandable(true); state.foreground("hidden.app");
        assertTrue(state.compact(true, hidden)); state.foreground("browser"); assertTrue(state.compact(true, hidden));
    }
    @Test public void automaticSwitchControlsTheSameSavedApplicationList() {
        DockVisibility state = new DockVisibility(); state.expandable(true); state.foreground("hidden.app"); state.toggle(false, hidden);
        assertFalse(state.compact(false, hidden)); assertTrue(state.compact(true, hidden)); assertFalse(state.compact(false, hidden));
        state.expandable(false); assertTrue(state.compact(true, hidden)); assertFalse(state.compact(false, hidden));
    }
    @Test public void preferenceRefreshAndUnknownEventsKeepExpansion() {
        DockVisibility state = new DockVisibility(); state.foreground("browser"); state.expandable(true); state.toggle(true, hidden);
        state.expandable(true); state.rulesChanged(); state.foreground(null); state.foreground(""); assertFalse(state.compact(true, hidden));
    }
    @Test public void residentKeyboardAvoidanceResumesWithoutOverridingAppHiding() {
        DockVisibility state = new DockVisibility(); state.foreground("browser"); state.keyboard(true); assertTrue(state.compact(false, hidden));
        state.keyboard(false); assertFalse(state.compact(false, hidden));
        state.foreground("hidden.app"); state.keyboard(true); state.keyboard(false); assertTrue(state.compact(true, hidden));
    }
    @Test public void changingModesStartsCollapsedAndReturningResidentUsesRules() {
        DockVisibility state = new DockVisibility(); state.foreground("browser"); state.expandable(true); assertTrue(state.compact(true, hidden));
        state.toggle(true, hidden); assertFalse(state.compact(true, hidden)); state.expandable(false); assertFalse(state.compact(true, hidden));
        state.foreground("launcher"); assertTrue(state.compact(true, hidden)); state.expandable(true); assertTrue(state.compact(true, hidden));
    }
    @Test public void newScreenSessionKeepsModeButStartsCollapsed() {
        DockVisibility state = new DockVisibility(); state.expandable(true); state.toggle(false, hidden); state.reset();
        assertTrue(state.expandable()); assertTrue(state.compact(false, hidden)); state.toggle(false, hidden); assertFalse(state.compact(false, hidden));
    }
    @Test public void emptyAndDisabledRulesNeverHideByApplication() {
        DockVisibility state = new DockVisibility(); state.foreground("hidden.app");
        assertFalse(state.automaticallyCompact(false, hidden)); assertFalse(state.automaticallyCompact(true, Set.of()));
        assertTrue(state.automaticallyCompact(true, hidden)); assertFalse(state.automaticallyCompact(true, Set.of("other")));
    }
    @Test public void unknownForegroundDoesNotHideTheArrowOrBorrowThePreviousAppRule() {
        DockVisibility state = new DockVisibility(); state.expandable(true);
        assertFalse(state.automaticallyCompact(true, hidden)); state.toggle(true, hidden); assertFalse(state.compact(true, hidden));
        state.foreground("hidden.app"); assertTrue(state.compact(true, hidden));
        state.foreground(null); assertFalse(state.automaticallyCompact(true, hidden)); assertFalse(state.compact(true, hidden));
        state.expandable(false); assertFalse(state.compact(true, hidden));
    }
    @Test public void clockDetectionUsesOnlyActiveSystemUiApplicationMetadata() {
        assertTrue(DockVisibility.clockPage("com.android.systemui", "系统界面", "系统界面", true));
        assertTrue(DockVisibility.clockPage(null, "System UI", "System UI", true));
        assertFalse(DockVisibility.clockPage("com.android.systemui", "SubLauncherWindow", "系统界面", true));
        assertFalse(DockVisibility.clockPage("com.android.systemui", "系统界面", "系统界面", false));
        assertFalse(DockVisibility.clockPage("example.app", "系统界面", "系统界面", true));
        assertFalse(DockVisibility.clockPage(null, "应用", "系统界面", true));
        assertFalse(DockVisibility.clockPage(null, null, "系统界面", true));
    }
    @Test public void clockKeepsAToggleInBothModesEvenWhenSystemUiIsHidden() {
        for (boolean arrowMode : new boolean[]{false, true}) for (boolean automatic : new boolean[]{false, true}) {
            DockVisibility state = new DockVisibility(); state.expandable(arrowMode); state.foreground("systemui"); state.clock(true); state.keyboard(true);
            assertFalse(state.automaticallyCompact(automatic, hidden)); assertTrue(state.compact(automatic, hidden));
            state.toggle(automatic, hidden); assertFalse(state.compact(automatic, hidden));
            state.clock(true); state.rulesChanged(); assertFalse(state.compact(automatic, hidden));
            state.toggle(automatic, hidden); assertTrue(state.compact(automatic, hidden));
            state.clock(false); assertEquals(automatic, state.automaticallyCompact(automatic, hidden));
        }
    }
    @Test public void residentClockExpansionIsTemporaryAndCannotOverrideHiddenApps() {
        DockVisibility state = new DockVisibility(); state.clock(true); state.foreground("systemui"); state.toggle(true, hidden);
        assertFalse(state.compact(true, hidden)); assertFalse(state.expandable());
        state.clock(false); state.foreground("hidden.app"); assertTrue(state.compact(true, hidden));
        state.foreground("browser"); assertFalse(state.compact(true, hidden));
        state.clock(true); assertTrue(state.compact(true, hidden));
        state.toggle(true, hidden); state.reset(); state.clock(true); assertTrue(state.compact(true, hidden));
    }
    @Test public void forcedClockCollapseIsIndependentOfTheExpandedAppSession() {
        DockVisibility state = new DockVisibility(); state.expandable(true); state.foreground("browser"); state.toggle(true, hidden);
        state.foreground("systemui"); state.clock(true); assertTrue(state.compact(true, hidden));
        state.toggle(true, hidden); assertFalse(state.compact(true, hidden));
        state.clock(false); assertTrue(state.compact(true, hidden));
        state.foreground("browser"); assertFalse(state.compact(true, hidden));
        state.clock(true); assertTrue(state.compact(true, hidden)); state.clock(false); assertFalse(state.compact(true, hidden));
    }
    @Test public void followingClockUsesBothModesAndAutomaticApplicationRules() {
        for (boolean arrowMode : new boolean[]{false, true}) for (boolean automatic : new boolean[]{false, true}) {
            DockVisibility state = new DockVisibility(); state.clockFollowsRules(true); state.expandable(arrowMode); state.foreground("browser"); state.toggle(automatic, hidden);
            state.foreground("systemui"); state.clock(true);
            assertFalse(state.clockOverride()); assertEquals(automatic, state.automaticallyCompact(automatic, hidden)); assertEquals(automatic, state.compact(automatic, hidden));
            state.toggle(automatic, hidden); assertEquals(automatic || arrowMode, state.compact(automatic, hidden));
            state.clock(false); state.foreground("browser"); assertEquals(arrowMode && !automatic, state.compact(automatic, hidden));
        }
    }
    @Test public void followingClockHonorsResidentKeyboardAvoidanceAndDisabledOrEmptyLists() {
        DockVisibility state = new DockVisibility(); state.clockFollowsRules(true); state.clock(true); state.foreground("systemui");
        assertFalse(state.compact(false, hidden)); assertFalse(state.compact(true, Set.of()));
        state.keyboard(true); assertTrue(state.compact(false, hidden)); state.keyboard(false); assertFalse(state.compact(false, hidden));
        state.foreground(null); assertFalse(state.compact(true, hidden));
    }
    @Test public void changingClockPolicyClearsOnlyTemporaryExpansionAndResetKeepsThePolicy() {
        DockVisibility state = new DockVisibility(); state.expandable(true); state.toggle(false, hidden); state.clock(true);
        state.toggle(false, hidden); assertFalse(state.compact(false, hidden));
        state.clockFollowsRules(true); assertFalse(state.compact(false, hidden));
        state.clockFollowsRules(false); assertTrue(state.compact(false, hidden));
        state.clock(false); assertFalse(state.compact(false, hidden));
        state.clockFollowsRules(true); state.reset(); state.clock(true); assertFalse(state.clockOverride()); assertTrue(state.compact(false, hidden));
    }
    @Test public void clockPagingGuardFollowsDestinationStateInsteadOfTheApplicationList() {
        DockVisibility state = new DockVisibility();
        for (boolean arrowMode : new boolean[]{false, true}) {
            state.expandable(arrowMode); state.toggle(false, Set.of());
            assertTrue(state.watchClockPaging()); assertTrue(state.clockNeedsCollapse());
        }
        state.clockFollowsRules(true); state.expandable(false);
        assertFalse(state.watchClockPaging()); assertFalse(state.clockNeedsCollapse());
        state.expandable(true); assertTrue(state.watchClockPaging()); assertTrue(state.clockNeedsCollapse());
        state.toggle(false, Set.of()); assertTrue(state.watchClockPaging()); assertFalse(state.clockNeedsCollapse());
        state.clockFollowsRules(false); assertTrue(state.clockNeedsCollapse());
    }
}
