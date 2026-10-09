package io.github.flipcover.controls;

import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;

/** Read-only evidence: configured battery saver and the effective power policy are distinct. */
final class PowerDiagnostics {
    private PowerDiagnostics() { }
    static String local(Context context) {
        String configured;
        try { configured = setting(Settings.Global.getString(context.getContentResolver(), "low_power")); }
        catch (SecurityException denied) { configured = "读取被拒绝"; }
        return "省电 API（系统有效策略）：" + context.getSystemService(PowerManager.class).isPowerSaveMode() + "\n标准省电配置（global.low_power）：" + configured + "\n";
    }
    static String setting(String value) {
        if (value == null) return "未提供";
        return switch (value) { case "0" -> "0（关闭配置）"; case "1" -> "1（开启配置）"; default -> "未知值"; };
    }
    private static String booleanValue(String value) { return value.equals("true") || value.equals("false") ? value : "未知"; }
    static String system(String dump) {
        String enabled = "未知", full = "未知", adaptive = "未知", advertise = "未知", configured = "未知";
        int section = -1;
        for (String line : dump.split("\n")) {
            String text = line.trim(); if (text.isEmpty()) continue;
            int indent = line.indexOf(text);
            if (section < 0) { if (text.equals("Battery saver state machine:")) section = indent; continue; }
            if (indent <= section) break;
            if (text.startsWith("Enabled=")) enabled = booleanValue(text.substring(8));
            else if (text.startsWith("full=")) full = booleanValue(text.substring(5));
            else if (text.startsWith("adaptive=")) {
                int split = text.indexOf(" (advertise=");
                adaptive = booleanValue(text.substring(9, split < 0 ? text.length() : split));
                if (split >= 0 && text.endsWith(")")) advertise = booleanValue(text.substring(split + 12, text.length() - 1));
            } else if (text.startsWith("mSettingBatterySaverEnabled=")) configured = booleanValue(text.substring("mSettingBatterySaverEnabled=".length()));
        }
        return "电源服务省电状态（只读）：\n系统有效策略：" + enabled + "\n普通省电 full：" + full + "\n自适应省电 adaptive：" + adaptive + "\n自适应对外报告 advertise：" + advertise + "\n服务侧标准省电配置：" + configured + "\n";
    }
}
