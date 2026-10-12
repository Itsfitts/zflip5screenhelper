package io.github.flipcover.controls;

import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs only as a Shizuku UserService. No arbitrary command or shell string enters this boundary. */
public final class ShellService extends IShellService.Stub {
    private final int ownerUid;
    private final Context ownerContext;
    private final SystemControlCenter systemControlCenter = new SystemControlCenter();
    private long lastWifiScan;
    private SystemConnectivity connectivity;
    private SystemSwitches switches;
    private SystemAppPortrait portrait;
    private SystemSwitches switches() { if (switches == null) switches = new SystemSwitches(); return switches; }
    private SystemConnectivity connectivity() { if (connectivity == null) connectivity = new SystemConnectivity(ownerContext); return connectivity; }
    @Override public synchronized void watchConnectivity(IConnectivityListener listener) { checkCaller(); connectivity().watch(listener); }
    @Override public synchronized void watchSwitches(IConnectivityListener listener) { checkCaller(); switches().watch(listener); }
    public ShellService() { ownerUid = -1; ownerContext = null; }
    public ShellService(Context context) { ownerUid = context.getApplicationInfo().uid; ownerContext = context; }
    private void checkCaller() {
        if (ownerUid < 0 || Binder.getCallingUid() != ownerUid) throw new SecurityException("Caller is not the owning application");
    }
    @Override public synchronized String execute(String operation, int displayId, int value, String component) {
        checkCaller();
        try {
            return switch (operation) {
                case "rotation" -> rotation(displayId, value, false);
                case "rotation_auto" -> rotation(displayId, 0, true);
                case "brightness", "brightness_read" -> brightness(displayId, value, operation.equals("brightness"));
                case "states" -> states(displayId);
                case "system_controls" -> systemControls(value);
                case "location" -> location(displayId, value);
                case "app_display_read", "app_display_write" -> appDisplay(displayId, operation.equals("app_display_write"), component);
                case "app_portrait_open", "app_portrait_close" -> appPortrait(displayId, operation.equals("app_portrait_open"), component);
                case "wifi_details" -> wifiDetails(value);
                case "connectivity_states", "nfc_details", "hotspot_details", "nfc", "hotspot", "nfc_secure", "hotspot_config" -> connectivity().execute(operation, displayId, value, component);
                case "wifi", "bluetooth", "data", "airplane", "dnd" -> switchSetting(operation, displayId, value);
                case "tile_add", "tile_click" -> tile(operation, component);
                case "diagnostics" -> diagnostics(displayId);
                case "recent_tasks", "recent_page", "recent_clear", "recent_dismiss" -> recentTasks(displayId, component, operation.equals("recent_clear") || operation.equals("recent_dismiss"), operation.equals("recent_dismiss"), operation.equals("recent_page") || operation.equals("recent_dismiss"));
                case "recent_open" -> reply(true, "任务恢复结果", new SystemRecentTasks(ownerContext, ownerUid).open(displayId, component).toString());
                case "native_home" -> nativeHome(displayId);
                case "system_recents" -> systemRecents(displayId);
                default -> reply(false, "不支持的操作", "");
            };
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            return reply(false, "系统拒绝或接口不可用：" + cause.getClass().getSimpleName(), String.valueOf(cause.getMessage()));
        }
    }
    @Override public synchronized android.os.Bundle taskSnapshot(int displayId, String task) {
        checkCaller();
        try { return new SystemRecentTasks(ownerContext, ownerUid).snapshot(displayId, task); }
        catch (Exception error) { android.os.Bundle result = new android.os.Bundle(); result.putString("message", "预览不可用"); return result; }
    }
    private String recentTasks(int display, String request, boolean clear, boolean explicit, boolean page) throws Exception {
        JSONObject result = new SystemRecentTasks(ownerContext, ownerUid).execute(display, request, clear, explicit, page);
        String message = clear ? "已从系统移除 " + result.getInt("removed") + " 个任务" + (result.getInt("retained") > 0 ? "，部分任务仍保留" : "") : "已同步外屏最近任务";
        return reply(true, message, result.toString());
    }
    private String rotation(int display, int rotation, boolean automatic) throws Exception {
        secondary(display);
        if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("Invalid rotation");
        Output fixed = run("wm", "fixed-to-user-rotation", "-d", "" + display, automatic ? "default" : "enabled");
        if (!fixed.ok()) return reply(false, "外屏旋转策略设置失败", fixed.text);
        Output write = automatic ? run("cmd", "window", "user-rotation", "-d", "" + display, "free")
            : run("cmd", "window", "user-rotation", "-d", "" + display, "lock", "" + rotation);
        if (!write.ok()) return reply(false, "外屏方向设置失败，可尝试交回系统", write.text);
        Output read = run("cmd", "window", "user-rotation", "-d", "" + display);
        String expected = automatic ? "free" : "lock " + rotation;
        boolean verified = read.ok() && read.text.trim().equals(expected);
        return reply(verified, verified ? automatic ? "外屏旋转已交回系统" : "系统已确认锁定 " + (rotation * 90) + "°" : "已提交旋转，但无法确认结果", read.text);
    }
    private String brightness(int display, int value, boolean write) throws Exception {
        activeSecondaryDisplay(display);
        Class<?> type = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object manager = type.getMethod("getInstance").invoke(null);
        if (write) {
            if (value < 5 || value > 100) throw new IllegalArgumentException("Brightness must be 5..100");
            java.lang.reflect.Method temporary = type.getMethod("setTemporaryBrightness", int.class, float.class);
            java.lang.reflect.Method persist = type.getMethod("setBrightness", int.class, float.class);
            // Match SystemUI's display-scoped slider: immediate brightness, then saved setting.
            temporary.invoke(manager, display, value / 100f);
            try { activeSecondaryDisplay(display); persist.invoke(manager, display, value / 100f); }
            catch (Exception error) {
                try { temporary.invoke(manager, display, Float.NaN); } catch (Exception ignored) { }
                throw error;
            }
        }
        java.lang.reflect.Method read = type.getMethod("getBrightness", int.class);
        activeSecondaryDisplay(display);
        float actual = (float) read.invoke(manager, display);
        // DPC applies updates asynchronously; this bounded confirmation is only for this request.
        for (int attempt = 0; write && attempt < 4 && (!Float.isFinite(actual) || Math.abs(actual - value / 100f) > .02f); attempt++) {
            android.os.SystemClock.sleep(60); activeSecondaryDisplay(display); actual = (float) read.invoke(manager, display);
        }
        if (!Float.isFinite(actual) || actual < 0 || actual > 1) return reply(false, "此显示器没有返回可用亮度", "");
        boolean confirmed = !write || Math.abs(actual - value / 100f) <= .02f;
        return reply(confirmed, !write ? "外屏亮度" : confirmed ? "已提交外屏亮度，系统设置返回 " + Math.round(actual * 100) + "%" : "请求 " + value + "% 后系统仍返回 " + Math.round(actual * 100) + "%，请检查系统自动亮度或显示限制", String.valueOf(actual));
    }
    private String nativeHome(int display) throws Exception {
        activeSecondaryDisplay(display);
        if (portrait != null) portrait.close();
        // Fixed component and owner user only; callers cannot supply an intent or shell command.
        Output result = run("am", "start", "-W", "--user", String.valueOf(ownerUid / 100000), "--display", String.valueOf(display), "-n", "com.android.systemui/.subscreen.SubHomeActivity", "-f", "0x10000000");
        activeSecondaryDisplay(display);
        boolean accepted = result.ok() && result.text.contains("Status: ok") && result.text.contains("Activity: com.android.systemui/.subscreen.SubHomeActivity");
        return reply(accepted, accepted ? "已返回三星原生卡片" : "三星未确认返回原生卡片，请重试", result.text);
    }
    private String systemRecents(int display) throws Exception {
        activeSecondaryDisplay(display);
        // Fixed Samsung component, selected display and owner user; never restart the launcher.
        String component = "com.sec.android.app.launcher/com.android.quickstep.RecentsActivity";
        Output result = run("am", "start", "-W", "--user", String.valueOf(ownerUid / 100000), "--display", String.valueOf(display), "-n", component);
        activeSecondaryDisplay(display);
        boolean accepted = result.ok() && result.text.contains("Status: ok") && result.text.contains("Activity: " + component);
        return reply(accepted, accepted ? "已请求在外屏打开系统最近任务" : "三星未接受最近任务请求，请重试或使用外屏多任务", result.text);
    }
    private void activeSecondaryDisplay(int id) {
        secondary(id);
        if (ownerContext == null) throw new IllegalStateException("外屏服务未就绪");
        android.view.Display target = ownerContext.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(id);
        if (target == null || !target.isValid() || target.getState() != android.view.Display.STATE_ON || ownerContext.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) throw new IllegalStateException("目标外屏已改变、息屏或锁定");
    }
    private String appPortrait(int display, boolean open, String request) throws Exception {
        secondary(display);
        if (request == null || request.length() > 8192) throw new IllegalArgumentException("Invalid portrait request");
        if (open) {
            activeSecondaryDisplay(display);
            JSONObject read = new JSONObject(appDisplay(display, false, request));
            if (!read.optBoolean("ok") || !new JSONObject(read.getString("output")).optString("state").equals("ready")) return reply(false, "清晰竖屏暂不可用，请检查外屏授权和应用显示大小设置", "");
            if (portrait == null) portrait = new SystemAppPortrait(ownerContext, ownerUid);
            portrait.open(display, request);
        } else if (portrait != null) portrait.close(request);
        return reply(true, open ? "已提交清晰竖屏启动" : "已关闭清晰竖屏区域", "");
    }
    private String appDisplay(int display, boolean write, String request) throws Exception {
        secondary(display);
        return SystemAppDisplay.execute(ownerContext, ownerUid, display, write, request, new SystemAppDisplay.Settings() {
            public String read(String key) throws Exception {
                Output output = run(65536, "settings", "--user", String.valueOf(ownerUid / 100000), "get", "secure", key);
                if (!output.ok() || output.text.length() >= 60001) throw new IllegalStateException("Unable to read cover policy");
                String value = output.text.endsWith("\n") ? output.text.substring(0, output.text.length() - 1) : output.text;
                return value.equals("null") ? null : value;
            }
            public void write(String key, String value) throws Exception {
                if (!AppDisplayPolicy.KEY.equals(key)) throw new SecurityException("Not a cover size policy");
                Output output = value == null ? run("settings", "--user", String.valueOf(ownerUid / 100000), "delete", "secure", key) : run("settings", "--user", String.valueOf(ownerUid / 100000), "put", "secure", key, value);
                if (!output.ok()) throw new IllegalStateException("Unable to write cover policy");
            }
        });
    }
    private String states(int display) throws Exception {
        JSONObject values = new JSONObject();
        for (String id : SystemSwitches.IDS) values.put(id, switches().state(id));
        values.put("rotation_mode", -1);
        if (display > 0) { Output read = run("cmd", "window", "user-rotation", "-d", String.valueOf(display)); values.put("rotation_mode", CoverRotation.systemMode(read.ok(), read.text)); }
        values.put("system_controls", systemControlsState());
        values.put("nfc", connectivity().state("nfc")).put("hotspot", connectivity().state("hotspot"));
        return reply(true, "已读取系统状态", values.toString());
    }
    static int airplaneState() throws Exception {
        Output read = run("settings", "get", "global", "airplane_mode_on");
        return !read.ok() ? -1 : read.text.trim().equals("1") ? 1 : read.text.trim().equals("0") ? 0 : -1;
    }
    private int systemControlsState() throws Exception {
        Output read = run("dumpsys", "statusbar");
        return read.ok() ? SystemControlCenter.state(read.text) : -1;
    }
    private String systemControls(int value) throws Exception {
        if (value < -1 || value > 1) throw new IllegalArgumentException("Expected toggle, off or on");
        int before = systemControlsState();
        if (value == -1 && before < 0) return reply(false, "内外屏控制中心状态未知，请长按选择开启或关闭", "-1");
        boolean enabled = value == -1 ? before == 0 : value == 1;
        systemControlCenter.setEnabled(enabled);
        int after = systemControlsState();
        boolean confirmed = after == (enabled ? 1 : 0);
        String message = confirmed ? enabled ? "已开启内外屏控制中心" : "已关闭内外屏控制中心"
            : after < 0 ? "请求已提交，无法确认内外屏控制中心状态" : "已解除本工具限制，内外屏控制中心仍受其他系统限制";
        return reply(confirmed, message, String.valueOf(after));
    }
    private String wifiDetails(int refresh) throws Exception {
        if (refresh != 0 && refresh != 1) throw new IllegalArgumentException("Invalid scan request");
        String scanMessage = "扫描缓存";
        if (refresh == 1) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastWifiScan != 0 && now - lastWifiScan < 30000) scanMessage = "刷新过于频繁，稍后再试";
            else { lastWifiScan = now; Output scan = run("cmd", "wifi", "start-scan"); scanMessage = scan.ok() ? "扫描已请求，再次刷新可读取更新" : "系统未接受扫描"; }
        }
        Output status = run("cmd", "wifi", "status"), saved = run("cmd", "wifi", "list-networks"), nearby = run("cmd", "wifi", "list-scan-results");
        JSONObject data = new JSONObject().put("status", status.ok() ? status.text : "").put("saved", saved.ok() ? saved.text : "").put("nearby", nearby.ok() ? nearby.text : "").put("scanMessage", scanMessage);
        return reply(status.ok() || saved.ok() || nearby.ok(), "Wi-Fi 读取完成", data.toString());
    }
    private String location(int display, int value) throws Exception {
        String user = String.valueOf(ownerUid / 100000);
        SystemLocation.Result result = SystemLocation.change(value, new SystemLocation.Access() {
            @Override public int read() throws Exception {
                activeSecondaryDisplay(display);
                Output output = run("cmd", "location", "is-location-enabled", "--user", user);
                return SystemLocation.state(output.ok(), output.text);
            }
            @Override public boolean write(boolean enabled) throws Exception {
                activeSecondaryDisplay(display);
                return run("cmd", "location", "set-location-enabled", String.valueOf(enabled), "--user", user).ok();
            }
        });
        return reply(result.ok(), result.message(), String.valueOf(result.state()));
    }
    private String switchSetting(String operation, int display, int value) throws Exception {
        int subscription = operation.equals("data") ? switches().dataSubscription() : -1;
        SystemSwitches.Result result = SystemSwitches.change(value, new SystemSwitches.Access() {
            @Override public boolean valid() {
                try { activeSecondaryDisplay(display); return !operation.equals("data") || subscription >= 0 && subscription == switches().dataSubscription(); }
                catch (Exception error) { return false; }
            }
            @Override public int read() throws Exception { return switches().state(operation, subscription); }
            @Override public boolean write(boolean enabled) throws Exception {
                if (operation.equals("data")) { switches().dataEnabled(subscription, enabled); return true; }
                String verb = enabled ? "enable" : "disable";
                return switch (operation) {
                    case "wifi" -> run("svc", "wifi", verb).ok();
                    case "bluetooth" -> run("cmd", "bluetooth_manager", verb).ok();
                    case "airplane" -> run("cmd", "connectivity", "airplane-mode", verb).ok();
                    case "dnd" -> run("cmd", "notification", "set_dnd", enabled ? "priority" : "all").ok();
                    default -> false;
                };
            }
        });
        return reply(result.ok(), result.message(), String.valueOf(result.state()));
    }
    private String tile(String operation, String encoded) throws Exception {
        ComponentName name = ComponentName.unflattenFromString(encoded);
        if (name == null || encoded.length() > 512) throw new IllegalArgumentException("Invalid tile component");
        Output help = run("cmd", "statusbar", "help");
        String command = operation.equals("tile_add") ? "add-tile" : "click-tile";
        if (!help.ok() || !help.text.contains(command)) return reply(false, "本机未提供此磁贴接口", help.text);
        if (operation.equals("tile_click")) {
            Output registered = run("settings", "get", "secure", "sysui_qs_tiles");
            if (!registered.ok() || !hasTile(registered.text, name)) return reply(false, "无法确认磁贴已在系统中注册，请先添加到系统快捷设置", registered.text);
        }
        Output output = run("cmd", "statusbar", command, name.flattenToString());
        return reply(output.ok(), output.ok() ? operation.equals("tile_add") ? "已提交系统磁贴注册，稍后可尝试点击" : "磁贴点击已发送；完成情况和状态由原应用决定" : "系统磁贴请求失败", output.text);
    }
    static boolean hasTile(String list, ComponentName wanted) {
        for (String entry : list.trim().split(",")) {
            String item = entry.trim();
            if (item.startsWith("custom(") && item.endsWith(")")) {
                ComponentName found = ComponentName.unflattenFromString(item.substring(7, item.length() - 1));
                if (wanted.equals(found)) return true;
            }
        }
        return false;
    }
    private String diagnostics(int display) throws Exception {
        StringBuilder report = new StringBuilder("Shizuku UserService uid=").append(android.os.Process.myUid()).append('\n');
        if (display > 0) {
            report.append("外屏尺寸：\n").append(run("wm", "size", "-d", "" + display).text);
            report.append("外屏密度：\n").append(run("wm", "density", "-d", "" + display).text);
            report.append("外屏旋转：\n").append(run("cmd", "window", "user-rotation", "-d", "" + display).text);
        }
        Output help = run("cmd", "statusbar", "help");
        report.append("系统磁贴 add-tile：").append(help.text.contains("add-tile")).append('\n');
        report.append("系统磁贴 click-tile：").append(help.text.contains("click-tile")).append('\n');
        Output power = run(262144, "dumpsys", "power");
        if (power.ok()) report.append(PowerDiagnostics.system(power.text));
        else report.append("电源服务省电状态：读取失败（退出码 ").append(power.code).append("）\n");
        return reply(true, "只读检测完成", report.toString());
    }
    private static void secondary(int id) { if (id <= 0) throw new IllegalArgumentException("A secondary display is required"); }
    private record Output(int code, String text) { boolean ok() { return code == 0 && !text.contains("Exception") && !text.contains("Error:"); } }
    private static Output run(String... arguments) throws Exception { return run(16384, arguments); }
    private static Output run(int limit, String... arguments) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (bytes) { if (bytes.size() < limit) bytes.write(buffer, 0, Math.min(count, limit - bytes.size())); }
                }
            } catch (Exception ignored) { }
        }, "shell-output");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) { process.destroyForcibly(); return new Output(-1, "操作超时"); }
            reader.join(500);
            synchronized (bytes) { return new Output(process.exitValue(), new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
        } finally {
            process.destroy();
            try { process.getInputStream().close(); process.getOutputStream().close(); } catch (Exception ignored) { }
        }
    }
    private static String reply(boolean ok, String message, String output) {
        try { return new JSONObject().put("ok", ok).put("message", message).put("output", output).toString(); }
        catch (Exception e) { return "{\"ok\":false,\"message\":\"结果编码失败\"}"; }
    }
    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller == ownerUid || caller == 0 || caller == 2000) { if (portrait != null) portrait.close(); System.exit(0); }
    }
}
