package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Changes and restores power mode only on a disposable emulator; never clicks a vendor tile. */
final class PowerSaveTileChecks {
    private static final String TILE = "tile:com.samsung.android.lool/com.samsung.android.sm.battery.ui.mode.BatteryModeTile";
    private static final String OTHER = "tile:com.samsung.android.lool/com.samsung.android.sm.battery.ui.setting.tile.BatteryProtectionTile";
    private final Instrumentation test;
    private CoverService owner;
    private Panels panels;
    private PowerManager power;
    private FrameLayout root;
    private View body, tile;
    private int assertions;
    PowerSaveTileChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private Object field(String name) throws Exception { Field value = CoverService.class.getDeclaredField(name); value.setAccessible(true); return value.get(owner); }
    private void panels(Panels value) { try { Field field = CoverService.class.getDeclaredField("panels"); field.setAccessible(true); field.set(owner, value); panels = value; } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); } }
    private void mount() {
        panels(new Panels(owner)); body = panels.build("controls"); root.removeAllViews(); root.addView(body);
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(748, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 720, 748);
        tile = body.findViewWithTag("control-" + TILE);
    }
    private String shell(String command) throws Exception { try (ParcelFileDescriptor descriptor = test.getUiAutomation().executeShellCommand(command); FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) { return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } }
    private void mode(boolean enabled) throws Exception {
        shell("cmd power set-mode " + (enabled ? 1 : 0));
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (power.isPowerSaveMode() != enabled && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40);
        require(power.isPowerSaveMode() == enabled, "emulator confirms actual power mode " + enabled);
        String service = PowerDiagnostics.system(shell("dumpsys power"));
        require(service.contains("系统有效策略：" + enabled) && service.contains("普通省电 full：" + enabled), "independent service dump reports actual effective and full state " + enabled);
    }
    private void awaitTile(boolean enabled) {
        boolean[] matches = {false}; long deadline = SystemClock.uptimeMillis() + 3000;
        do { main(() -> matches[0] = tile.isSelected() == enabled); if (matches[0]) break; SystemClock.sleep(40); } while (SystemClock.uptimeMillis() < deadline);
        require(matches[0], "system power broadcast updates the existing tile to " + enabled);
        main(() -> {
            require(owner.on(TILE) == power.isPowerSaveMode(), "tile reader agrees with diagnostics without Shizuku");
            require(tile.getStateDescription().toString().equals(enabled ? "已开启" : "已关闭"), "accessible state follows confirmed power mode");
            require(body.findViewWithTag("control-" + TILE) == tile && body.getParent() == root, "state changes retain the existing panel and tile");
            require(!body.findViewWithTag("control-" + OTHER).isSelected(), "battery protection is not mistaken for power saving");
            View face = ((Panels.Tile) tile).face; Bitmap pixels = Bitmap.createBitmap(face.getWidth(), face.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                face.getBackground().setBounds(0, 0, face.getWidth(), face.getHeight()); face.getBackground().draw(new Canvas(pixels)); int fill = pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2);
                require(Color.alpha(fill) > 200 && (enabled ? Color.red(fill) > 200 && Color.green(fill) > 200 && Color.blue(fill) > 200 : Color.red(fill) < 100 && Color.green(fill) < 100 && Color.blue(fill) < 100), "rendered fill follows power mode " + enabled + ": " + Integer.toHexString(fill));
            } finally { pixels.recycle(); }
        });
    }
    String run() throws Exception {
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) throw new IllegalStateException("Disposable emulator only");
        require(ActionCatalog.isPowerSaveTile(TILE), "recognizes the observed Samsung component");
        for (String id : new String[]{null, "", "tile:broken", "wifi", OTHER, TILE.replace("tile:", "app:"), TILE.replace("tile:com.samsung.android.lool/", "tile:example.app/"), TILE + "Other"}) require(!ActionCatalog.isPowerSaveTile(id), "unrelated components cannot borrow power state");
        Context context = test.getTargetContext().getApplicationContext();
        power = context.getSystemService(PowerManager.class); boolean originalMode = power.isPowerSaveMode();
        try {
            shell("dumpsys battery unplug"); mode(false);
            main(() -> {
                owner = new CoverService(); owner.screenContext = context; owner.prefs = new Prefs(context); root = new FrameLayout(context);
                try {
                    Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(owner, context);
                    Method register = CoverService.class.getDeclaredMethod("registerScreenEvents"); register.setAccessible(true); register.invoke(owner);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                owner.prefs.data.edit().putBoolean("panel_brightness", false).putBoolean("panel_volume", false).putBoolean("panel_media", false).putBoolean("panel_blur", true).commit();
                owner.prefs.saveActions("panel", List.of(TILE, OTHER));
                mount();
            });
            awaitTile(false); mode(true); awaitTile(true);
            main(() -> {
                android.hardware.display.VirtualDisplay display = context.getSystemService(android.hardware.display.DisplayManager.class).createVirtualDisplay("power-gate-check", 320, 240, 160, null, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                require(display != null, "isolated secondary display for admission diagnostics");
                InterfaceCard card = new InterfaceCard(context, owner.prefs, InterfaceCard.CONTROLS, new FrameLayout(context), () -> { }, () -> { });
                try {
                    int[] completed = {0};
                    card.prepareGlass(null, display.getDisplay(), owner.prefs, () -> true, () -> completed[0]++, false);
                    require(completed[0] == 1 && card.glass() == null && card.glassDiagnostics().contains("blocked:power-save-api"), "admission records the API gate without creating or capturing a session");
                    card.dropGlass("blocked:effect-disabled");
                    require(card.glassDiagnostics().contains("blocked:effect-disabled"), "visible policy fallback keeps its explicit reason");
                } finally { card.release(); display.release(); }
            });
            main(() -> {
                panels.working(TILE, true); require(tile.isSelected() && "正在执行".contentEquals(tile.getStateDescription()), "pending click does not optimistically invert state");
                panels.working(TILE, false); require(tile.isSelected() && "已开启".contentEquals(tile.getStateDescription()), "finishing a request preserves the last confirmed state");
            });
            mode(false); awaitTile(false);
            mode(true); awaitTile(true);
            View previous = tile;
            main(() -> { panels(null); root.removeAllViews(); });
            mode(false); SystemClock.sleep(250); main(() -> require(previous.isSelected() && body.getParent() == null, "late broadcasts do not update a closed panel"));
            main(this::mount);
            awaitTile(false); require(tile != previous, "reopened panel reads current system state");
            return "PASS: " + assertions + " power-save tile checks; actual system broadcasts, rendered highlighting, confirmed state, component identity and panel lifecycle; Samsung click transport requires physical verification";
        } finally {
            try { mode(originalMode); } finally {
                try { shell("dumpsys battery reset"); } finally {
                    if (owner != null) {
                        for (String name : new String[]{"screenReceiver", "systemDialogsReceiver"}) try { owner.unregisterReceiver((BroadcastReceiver) field(name)); } catch (IllegalArgumentException ignored) { }
                        main(() -> { panels(null); root.removeAllViews(); owner.main.removeCallbacksAndMessages(null); });
                        ((java.util.concurrent.ExecutorService) field("files")).shutdown();
                    }
                }
            }
        }
    }
}
