package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemSwitchesTest {
    private static final class Switch implements SystemSwitches.Access {
        int state, reads, waits, validCalls, expireAfter = Integer.MAX_VALUE, settlesAfter;
        boolean accepted = true, apply = true;
        final List<Boolean> writes = new ArrayList<>();
        Switch(int state) { this.state = state; }
        public boolean valid() { return ++validCalls <= expireAfter; }
        public int read() { reads++; return state; }
        public boolean write(boolean enabled) { writes.add(enabled); if (accepted && apply && settlesAfter == 0) state = enabled ? 1 : 0; return accepted; }
        public void await() { waits++; if (apply && settlesAfter == waits) state = writes.get(0) ? 1 : 0; }
    }
    @Test public void radioTransitionsAreUnknownAndBleOnlyIsNotBluetoothOn() {
        assertEquals(1, SystemSwitches.wifiState(3)); assertEquals(0, SystemSwitches.wifiState(1));
        for (int state : new int[]{-1, 0, 2, 4, 99}) assertEquals(-1, SystemSwitches.wifiState(state));
        assertEquals(1, SystemSwitches.bluetoothState(12)); assertEquals(0, SystemSwitches.bluetoothState(10)); assertEquals(0, SystemSwitches.bluetoothState(15));
        for (int state : new int[]{-1, 11, 13, 14, 16, 99}) assertEquals(-1, SystemSwitches.bluetoothState(state));
        for (int state : new int[]{1, 2, 3}) assertEquals(1, SystemSwitches.zenState(state));
        assertEquals(0, SystemSwitches.zenState(0)); assertEquals(-1, SystemSwitches.zenState(4));
    }
    @Test public void everyClickUsesFreshStateInsteadOfTheLastUiHighlight() throws Exception {
        Switch access = new Switch(0);
        assertTrue(SystemSwitches.change(-1, access).ok());
        access.state = 0; // Changed by system settings while the previous UI still looks enabled.
        assertTrue(SystemSwitches.change(-1, access).ok());
        assertTrue(SystemSwitches.change(-1, access).ok());
        assertEquals(List.of(true, true, false), access.writes);
    }
    @Test public void unknownCannotBeInvertedButExplicitChoiceCanRecoverIt() throws Exception {
        Switch access = new Switch(-1);
        assertFalse(SystemSwitches.change(-1, access).ok()); assertTrue(access.writes.isEmpty());
        assertTrue(SystemSwitches.change(1, access).ok()); assertEquals(List.of(true), access.writes);
    }
    @Test public void settingAlreadyAtTargetDoesNotWrite() throws Exception {
        Switch access = new Switch(1); assertTrue(SystemSwitches.change(1, access).ok()); assertTrue(access.writes.isEmpty());
    }
    @Test public void asynchronousTransitionIsConfirmedBeforeSuccess() throws Exception {
        Switch access = new Switch(0); access.settlesAfter = 3;
        assertEquals(1, SystemSwitches.change(1, access).state()); assertEquals(3, access.waits);
    }
    @Test public void commandAcceptanceWithoutStateChangeDoesNotClaimSuccess() throws Exception {
        Switch access = new Switch(0); access.apply = false;
        SystemSwitches.Result result = SystemSwitches.change(1, access);
        assertFalse(result.ok()); assertEquals(0, result.state()); assertEquals(30, access.waits);
    }
    @Test public void rejectedWriteRetainsActualState() throws Exception {
        Switch access = new Switch(1); access.accepted = false;
        SystemSwitches.Result result = SystemSwitches.change(0, access);
        assertFalse(result.ok()); assertEquals(1, result.state()); assertEquals(0, access.waits);
    }
    @Test public void changedDisplayOrDataSimCancelsBeforeWrite() throws Exception {
        for (int expires : new int[]{0, 1}) {
            Switch access = new Switch(0); access.expireAfter = expires;
            assertFalse(SystemSwitches.change(1, access).ok()); assertTrue(access.writes.isEmpty());
        }
    }
    @Test public void changedDataSimCannotConfirmTheOldSubscriptionsResult() throws Exception {
        for (int expires : new int[]{2, 3}) {
            Switch access = new Switch(0); access.expireAfter = expires;
            SystemSwitches.Result result = SystemSwitches.change(1, access);
            assertFalse(result.ok()); assertEquals(-1, result.state()); assertEquals(List.of(true), access.writes);
        }
    }
    @Test public void invalidCommandsNeverReadOrWrite() {
        Switch access = new Switch(0);
        for (int value : new int[]{-2, 2, Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class, () -> SystemSwitches.change(value, access));
        assertEquals(0, access.reads); assertTrue(access.writes.isEmpty());
    }
    @Test public void onlyRelevantSettingsInvalidateState() {
        for (String key : new String[]{"wifi_on", "bluetooth_on", "zen_mode", "airplane_mode_on", "multi_sim_data_call", "mobile_data", "mobile_data12"}) assertTrue(SystemSwitches.settingChanged(key));
        assertFalse(SystemSwitches.settingChanged("screen_brightness")); assertFalse(SystemSwitches.settingChanged("device_name"));
    }
    @Test public void actionsDoNotPretendToHaveAnOffState() {
        for (String id : new String[]{"wifi", "bluetooth", "data", "dnd", "airplane", "rotation", "tile:example/.Tile"}) assertTrue(ActionCatalog.showsState(id));
        for (String id : new String[]{"media", "home", "screenshot", "lock", "app:example/.Activity", "external_devices", "app_hub", "configure"}) assertFalse(ActionCatalog.showsState(id));
    }
}
