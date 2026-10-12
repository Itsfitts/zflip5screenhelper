package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production button route with an isolated display and a fake privileged service. */
final class SystemRecentsChecks {
    private final Instrumentation test;
    private int assertions;
    SystemRecentsChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    String run() throws Exception {
        Context context = test.getTargetContext(); Prefs prefs = new Prefs(context);
        boolean hadDisplay = prefs.data.contains("display"), hadEnabled = prefs.data.contains("enabled");
        int previousDisplay = prefs.displayId(); boolean previousEnabled = prefs.enabled();
        CoverService previous = CoverService.instance;
        ShizukuBridge bridge = CoverApp.bridge(context);
        Field remote = ShizukuBridge.class.getDeclaredField("remote"), worker = ShizukuBridge.class.getDeclaredField("executor"); remote.setAccessible(true); worker.setAccessible(true);
        Object original = remote.get(bridge); ExecutorService executor = (ExecutorService) worker.get(bridge);
        ImageReader surface = ImageReader.newInstance(748, 720, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay target = context.getSystemService(DisplayManager.class).createVirtualDisplay("system-recents-test", 748, 720, 340, surface.getSurface(), 0);
        CoverService[] owner = {null}; AtomicInteger calls = new AtomicInteger(); CountDownLatch release = new CountDownLatch(1), duplicateRelease = new CountDownLatch(1);
        try {
            require(target != null && target.getDisplay().getState() == android.view.Display.STATE_ON, "selected test display is awake");
            int display = target.getDisplay().getDisplayId();
            remote.set(bridge, new IShellService.Stub() {
                public void watchSwitches(IConnectivityListener listener) { }
                public void watchConnectivity(IConnectivityListener listener) { }
                public String execute(String operation, int screen, int value, String component) {
                    if (!operation.equals("system_recents") || screen != display || value != 0 || !component.isEmpty()) throw new AssertionError("request must use fixed operation and selected display");
                    int count = calls.incrementAndGet();
                    return "{\"ok\":" + (count > 1) + ",\"message\":\"test result\",\"output\":\"\"}";
                }
                public Bundle taskSnapshot(int displayId, String task) { throw new AssertionError("system entry does not read task snapshots"); }
                public void destroy() { }
            });
            test.runOnMainSync(() -> {
                prefs.data.edit().putInt("display", display).putBoolean("enabled", true).commit();
                CoverService service = new CoverService(); owner[0] = service; service.prefs = prefs; service.screenContext = context; service.display = target.getDisplay(); CoverService.instance = service;
                try { var attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(service, context); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            for (int attempt = 1; attempt <= 3; attempt++) {
                test.runOnMainSync(() -> owner[0].act("system_recents")); drain(executor);
                require(calls.get() == attempt && !bridge.busy(), "failure or previous success never disables the next click");
            }
            test.runOnMainSync(() -> { owner[0].display = context.getSystemService(DisplayManager.class).getDisplay(0); owner[0].act("system_recents"); owner[0].display = target.getDisplay(); });
            drain(executor); require(calls.get() == 3, "main display is rejected without a global fallback");
            CountDownLatch duplicateWaiting = new CountDownLatch(1);
            executor.execute(() -> { duplicateWaiting.countDown(); try { duplicateRelease.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            require(duplicateWaiting.await(3, TimeUnit.SECONDS), "worker held for duplicate clicks in a valid session");
            test.runOnMainSync(() -> { owner[0].act("system_recents"); owner[0].act("system_recents"); });
            duplicateRelease.countDown(); drain(executor);
            require(calls.get() == 4 && !bridge.busy(), "two clicks while busy execute only once in a valid session");
            CountDownLatch waiting = new CountDownLatch(1);
            executor.execute(() -> { waiting.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            require(waiting.await(3, TimeUnit.SECONDS), "worker held before submission");
            test.runOnMainSync(() -> { owner[0].act("system_recents"); owner[0].screenContext = null; });
            release.countDown(); drain(executor);
            require(calls.get() == 4 && !bridge.busy(), "stale queued request is cancelled");
            test.runOnMainSync(() -> { owner[0].screenContext = context; owner[0].act("system_recents"); }); drain(executor);
            require(calls.get() == 5, "new session can open after cancellation");
            test.runOnMainSync(() -> { prefs.data.edit().putBoolean("enabled", false).commit(); owner[0].act("system_recents"); }); drain(executor);
            require(calls.get() == 5, "disabled service cannot open recents");
            return "PASS " + assertions + " system recents request checks; Samsung rendering requires device acceptance";
        } finally {
            duplicateRelease.countDown(); release.countDown(); drain(executor); remote.set(bridge, original);
            test.runOnMainSync(() -> {
                CoverService.instance = previous;
                if (owner[0] != null) owner[0].main.removeCallbacksAndMessages(null);
                var editor = prefs.data.edit();
                if (hadDisplay) editor.putInt("display", previousDisplay); else editor.remove("display");
                if (hadEnabled) editor.putBoolean("enabled", previousEnabled); else editor.remove("enabled");
                editor.commit();
            });
            if (target != null) target.release(); surface.close();
        }
    }
    private void drain(ExecutorService executor) throws Exception { executor.submit(() -> { }).get(4, TimeUnit.SECONDS); test.waitForIdleSync(); }
}
