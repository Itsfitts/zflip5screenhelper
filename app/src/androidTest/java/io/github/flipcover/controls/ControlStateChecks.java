package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.List;

/** Rendered state semantics only; privileged services are checked separately as shell. */
final class ControlStateChecks {
    private final Instrumentation test;
    private int assertions;
    ControlStateChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    String run() throws Exception {
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) throw new IllegalStateException("Disposable emulator only");
        Throwable[] failure = {null};
        test.runOnMainSync(() -> {
            CoverService owner = new CoverService(); Context context = test.getTargetContext(); Prefs prefs = new Prefs(context);
            List<String> original = prefs.actions("panel"); boolean brightness = prefs.panelBrightness(), volume = prefs.panelVolume(), media = prefs.panelMedia();
            try {
                var attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(owner, context);
                owner.screenContext = context; owner.prefs = prefs;
                prefs.data.edit().putBoolean("panel_brightness", false).putBoolean("panel_volume", false).putBoolean("panel_media", false).commit();
                String external = "tile:example.test/example.test.ToggleTile";
                prefs.saveActions("panel", List.of("wifi", "data", "rotation", external, "home", "media", "screenshot", "external_devices"));
                owner.states.put("wifi", 1); owner.states.put("data", 0); owner.states.put("rotation_mode", 0);
                Panels panels = new Panels(owner); View body = panels.build("controls"); FrameLayout root = new FrameLayout(context); root.addView(body);
                root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(748, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 720, 748);
                Panels.Tile wifi = body.findViewWithTag("control-wifi"), data = body.findViewWithTag("control-data"), rotation = body.findViewWithTag("control-rotation"), unknown = body.findViewWithTag("control-" + external);
                require(wifi.isSelected() && !data.isSelected(), "confirmed on and off differ");
                require("已开启".contentEquals(wifi.getStateDescription()) && "已关闭".contentEquals(data.getStateDescription()), "confirmed accessible states");
                require(unknown.unknown.getVisibility() == View.VISIBLE && "状态未知".contentEquals(unknown.getStateDescription()), "external tile is unknown before clicking");
                require("系统旋转".contentEquals(rotation.label.getText()) && !rotation.isSelected(), "system ownership is not a lock");
                int width = wifi.getWidth(), height = wifi.getHeight(); panels.working("wifi", true);
                require(wifi.isSelected() && "正在执行".contentEquals(wifi.getStateDescription()) && !wifi.isEnabled(), "pending preserves confirmed state and blocks duplicate clicks");
                panels.working("wifi", false); owner.states.put("wifi", 0); panels.updateStates();
                require(!wifi.isSelected() && wifi.isEnabled(), "confirmed off clears highlight");
                owner.states.put("wifi", -1); owner.states.put("rotation_mode", -1); panels.updateStates();
                require(wifi.unknown.getVisibility() == View.VISIBLE && "状态未知".contentEquals(wifi.getStateDescription()), "unavailable is not off");
                require("旋转状态未知".contentEquals(rotation.label.getText()) && rotation.unknown.getVisibility() == View.VISIBLE, "unknown rotation is explicit");
                require(wifi.getWidth() == width && wifi.getHeight() == height, "stable hit geometry");
                for (String id : List.of("home", "media", "screenshot", "external_devices")) {
                    Panels.Tile action = body.findViewWithTag("control-" + id);
                    require(!action.isSelected() && action.unknown.getVisibility() == View.GONE && action.getStateDescription() == null, "action has no on/off state: " + id);
                }
                owner.states.put("wifi", 1); owner.states.put("rotation_mode", 1); panels.updateStates();
                require("旋转锁定".contentEquals(rotation.label.getText()), "confirmed lock label");
                Bitmap pixels = Bitmap.createBitmap(wifi.face.getWidth(), wifi.face.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    wifi.face.getBackground().setBounds(0, 0, pixels.getWidth(), pixels.getHeight()); wifi.face.getBackground().draw(new Canvas(pixels));
                    int fill = pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2);
                    require(Color.red(fill) > 200 && Color.alpha(fill) > 200, "rendered selected surface is bright");
                } finally { pixels.recycle(); }
            } catch (Throwable error) { failure[0] = error; }
            finally {
                prefs.saveActions("panel", original); prefs.data.edit().putBoolean("panel_brightness", brightness).putBoolean("panel_volume", volume).putBoolean("panel_media", media).commit();
                owner.main.removeCallbacksAndMessages(null);
                try { Field files = CoverService.class.getDeclaredField("files"); files.setAccessible(true); ((java.util.concurrent.ExecutorService) files.get(owner)).shutdown(); } catch (ReflectiveOperationException ignored) { }
            }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
        return "PASS: " + assertions + " rendered control-state checks; privileged services require separate verification";
    }
}
