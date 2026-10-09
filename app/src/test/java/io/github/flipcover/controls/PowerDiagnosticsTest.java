package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class PowerDiagnosticsTest {
    @Test public void unsetOrUnrecognizedConfigurationIsNotReportedAsOff() {
        assertEquals("未提供", PowerDiagnostics.setting(null));
        assertEquals("0（关闭配置）", PowerDiagnostics.setting("0"));
        assertEquals("1（开启配置）", PowerDiagnostics.setting("1"));
        assertEquals("未知值", PowerDiagnostics.setting("unexpected-private-value"));
    }
    @Test public void adaptivePolicyCanBeAdvertisedWhileStandardSaverIsOff() {
        String report = PowerDiagnostics.system("""
                Power Manager State:
                  Enabled=false
                  unrelated-app-wakelock
                Battery saver state machine:
                  Enabled=true
                    full=false
                    adaptive=true (advertise=true)
                  mSettingBatterySaverEnabled=false
                Next section:
                  full=true
                """);
        assertTrue(report.contains("系统有效策略：true"));
        assertTrue(report.contains("普通省电 full：false"));
        assertTrue(report.contains("自适应省电 adaptive：true"));
        assertTrue(report.contains("自适应对外报告 advertise：true"));
        assertTrue(report.contains("服务侧标准省电配置：false"));
        assertFalse(report.contains("unrelated-app-wakelock"));
    }
    @Test public void ordinarySaverAndIndentedSectionsAreParsed() {
        String report = PowerDiagnostics.system("  Battery saver state machine:\r\n    Enabled=true\r\n      full=true\r\n      adaptive=false (advertise=false)\r\n    mSettingBatterySaverEnabled=true\r\n");
        assertTrue(report.contains("系统有效策略：true"));
        assertTrue(report.contains("普通省电 full：true"));
        assertTrue(report.contains("自适应省电 adaptive：false"));
        assertTrue(report.contains("自适应对外报告 advertise：false"));
        assertTrue(report.contains("服务侧标准省电配置：true"));
    }
    @Test public void unsupportedOrTruncatedDumpRemainsUnknown() {
        for (String dump : new String[]{"Permission Denial: dumpsys power", "full=false\nadaptive=false", "Battery saver state machine:\n  Enabled=vendor-value\n  full=unknown"}) {
            String report = PowerDiagnostics.system(dump);
            assertFalse(report.contains("：false")); assertFalse(report.contains("：true"));
            assertTrue(report.contains("系统有效策略：未知"));
        }
        String partial = PowerDiagnostics.system("Battery saver state machine:\n  Enabled=false\n    adaptive=true\n");
        assertTrue(partial.contains("系统有效策略：false"));
        assertTrue(partial.contains("自适应省电 adaptive：true"));
        assertTrue(partial.contains("自适应对外报告 advertise：未知"));
        assertTrue(partial.contains("普通省电 full：未知"));
    }
}
