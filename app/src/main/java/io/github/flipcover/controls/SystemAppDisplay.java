package io.github.flipcover.controls;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import org.json.JSONObject;

/** Fixed Samsung policy access in the owning Shizuku service. No permission grants or display-wide writes. */
final class SystemAppDisplay {
    static final String LEGACY = "multistar_setting_repository", JSON = "multistar_setting_json_repository", BACKUP = "multistar_cover_widget_backup_list";
    interface Settings { String read(String key) throws Exception; void write(String key, String value) throws Exception; }
    static String execute(Context context, int ownerUid, int displayId, boolean write, String text, Settings settings) throws Exception {
        if (text == null || text.length() > 8192) throw new IllegalArgumentException("request too large");
        JSONObject request = new JSONObject(text); String name = request.getString("package"); AppDisplayPolicy.packageName(name);
        if (name.equals(BuildConfig.APPLICATION_ID) || name.equals("com.android.systemui") || name.equals("com.sec.android.app.launcher") || name.equals("android")) return result(false, "protected", "");
        if (!"samsung".equalsIgnoreCase(android.os.Build.MANUFACTURER) || displayId != 1 || context == null) return result(false, "unsupported", "");
        try { android.app.ActivityOptions.class.getDeclaredMethod("getCustomizedCoverDensity"); }
        catch (ReflectiveOperationException error) { return result(false, "unsupported", ""); }
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (!target(display, request)) return result(false, "display", "");
        String legacy = settings.read(LEGACY), json = settings.read(JSON), backup = settings.read(BACKUP);
        // This version understands the newer legacy repository slot and its JSON mirror.
        // An absent/older repository is not proof that creating one would be supported.
        if (legacy == null || legacy.split("/", -1).length < 26) return result(false, "unsupported", "");
        int user = ownerUid / 100000;
        boolean enabled = AppDisplayPolicy.member(legacy.split("/", -1)[25], name, user, true) || AppDisplayPolicy.member(backup, name, user, true);
        if (json != null && !json.isEmpty()) {
            JSONObject values = new JSONObject(json).optJSONObject("settings");
            if (values != null) enabled |= AppDisplayPolicy.member(values.optString("coverWidgetListIncludedUserId"), name, user, true);
        }
        String entry = AppDisplayPolicy.find(settings.read(AppDisplayPolicy.KEY), name);
        if (!write) return result(true, enabled ? "ready" : "membership", entry);
        boolean restore = request.optBoolean("restore");
        if (!enabled && !restore) return result(false, "membership", entry);
        String expected = request.getString("expected"), wanted;
        if (restore) wanted = request.getString("original");
        else {
            double dpi = request.getDouble("dpi");
            if (dpi != (int) dpi) throw new IllegalArgumentException("DPI must be an integer");
            wanted = AppDisplayPolicy.entry(name, new AppDisplayPolicy.Config((int) dpi, request.getDouble("scale")));
        }
        try {
            AppDisplayPolicy.change(new AppDisplayPolicy.Access() {
                public String read() throws Exception { return settings.read(AppDisplayPolicy.KEY); }
                public void write(String value) throws Exception { settings.write(AppDisplayPolicy.KEY, value); }
                public void check() {
                    Display current = context.getSystemService(DisplayManager.class).getDisplay(displayId);
                    if (!target(current, request) || current.getState() != Display.STATE_ON || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new IllegalStateException("cover unavailable");
                }
            }, name, expected, wanted);
            return result(true, "saved", wanted);
        } catch (AppDisplayPolicy.Conflict conflict) { return result(false, "conflict", AppDisplayPolicy.find(settings.read(AppDisplayPolicy.KEY), name)); }
    }
    static boolean target(Display display, JSONObject request) {
        if (display == null || !display.isValid() || !display.getName().equals(request.optString("displayName"))) return false;
        Display.Mode mode = display.getMode();
        return Displays.coverSize(mode.getPhysicalWidth(), mode.getPhysicalHeight()) && mode.getPhysicalWidth() == request.optInt("width") && mode.getPhysicalHeight() == request.optInt("height");
    }
    static JSONObject request(String name, Display display) throws Exception {
        return new JSONObject().put("package", name).put("displayName", display.getName()).put("width", display.getMode().getPhysicalWidth()).put("height", display.getMode().getPhysicalHeight());
    }
    private static String result(boolean ok, String state, String entry) throws Exception {
        return new JSONObject().put("ok", ok).put("message", state).put("output", new JSONObject().put("state", state).put("entry", entry).toString()).toString();
    }
}
