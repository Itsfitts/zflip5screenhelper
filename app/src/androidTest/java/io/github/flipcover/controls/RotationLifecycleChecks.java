package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.view.OrientationEventListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Production rotation lifecycle with an isolated display, controlled sensor and fake shell. */
final class RotationLifecycleChecks {
    private final Instrumentation test;
    private int assertions;
    RotationLifecycleChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private static Field field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Object get(Object target, String name) { try { return field(target, name).get(target); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private static void set(Object target, String name, Object value) { try { field(target, name).set(target, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private static void orientation(CoverRotation rotation, int angle) {
        try { Method method = CoverRotation.class.getDeclaredMethod("orientation", int.class); method.setAccessible(true); method.invoke(rotation, angle); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static final class Sensors implements java.util.function.Function<java.util.function.IntConsumer, OrientationEventListener> {
        final Context context;
        boolean listening;
        int starts;
        Sensor current;
        Sensors(Context context) { this.context = context; }
        public OrientationEventListener apply(java.util.function.IntConsumer callback) { current = new Sensor(callback); return current; }
        final class Sensor extends OrientationEventListener {
            final java.util.function.IntConsumer callback;
            int lastAngle = -1;
            Sensor(java.util.function.IntConsumer callback) { super(context); this.callback = callback; }
            void emit(int angle) { if (angle != lastAngle) { lastAngle = angle; onOrientationChanged(angle); } }
            @Override public void onOrientationChanged(int angle) { callback.accept(angle); }
            @Override public void enable() { listening = true; starts++; }
            @Override public void disable() { listening = false; }
        }
    }
    String run() throws Exception {
        Context context = test.getTargetContext(); Prefs prefs = new Prefs(context);
        boolean hadDisplay = prefs.data.contains("display"), hadEnabled = prefs.data.contains("enabled");
        int oldDisplay = prefs.displayId(); boolean oldEnabled = prefs.enabled();
        CoverService previous = CoverService.instance; String status = CoverService.status;
        ShizukuBridge bridge = CoverApp.bridge(context); Object remote = get(bridge, "remote");
        ExecutorService worker = (ExecutorService) get(bridge, "executor");
        ImageReader surface = ImageReader.newInstance(748, 720, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay target = context.getSystemService(DisplayManager.class).createVirtualDisplay("rotation-lifecycle-test", 748, 720, 340, surface.getSurface(), 0);
        CoverService[] service = {null}; CoverRotation[] mode = {null}; Sensors sensor = new Sensors(context);
        AtomicInteger serviceLookups = new AtomicInteger();
        ContextWrapper counted = new ContextWrapper(context) {
            @Override public Object getSystemService(String name) { if (name.equals(Context.DISPLAY_SERVICE) || name.equals(Context.KEYGUARD_SERVICE)) serviceLookups.incrementAndGet(); return super.getSystemService(name); }
        };
        AtomicInteger commands = new AtomicInteger(); CountDownLatch release = new CountDownLatch(1), rapidRelease = new CountDownLatch(1);
        try {
            require(target != null && target.getDisplay().getState() == android.view.Display.STATE_ON, "isolated secondary display is awake");
            require(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(), "test emulator starts unlocked");
            set(bridge, "remote", new IShellService.Stub() {
                public void watchSwitches(IConnectivityListener listener) { }
                public void watchConnectivity(IConnectivityListener listener) { }
                public String execute(String operation, int display, int value, String component) {
                    if (!operation.equals("rotation") || display != target.getDisplay().getDisplayId()) throw new AssertionError("rotation escaped the selected display");
                    commands.incrementAndGet(); return "{\"ok\":true,\"message\":\"test\",\"output\":\"lock " + value + "\"}";
                }
                public Bundle taskSnapshot(int display, String task) { throw new AssertionError("rotation must not request snapshots"); }
                public void destroy() { }
            });
            test.runOnMainSync(() -> {
                CoverService owner = new CoverService(); service[0] = owner;
                try { Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(owner, counted); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                owner.prefs = prefs; owner.display = target.getDisplay(); CoverService.instance = owner;
                prefs.data.edit().putInt("display", owner.display.getDisplayId()).putBoolean("enabled", true).commit();
                CoverRotation rotation = new CoverRotation(owner, sensor); mode[0] = rotation; set(owner, "automaticRotation", rotation);
                // Seed the state after user authorization without granting Shizuku or using a real sensor.
                set(rotation, "enabled", true); set(rotation, "displayId", owner.display.getDisplayId()); sensor.current.enable();
                orientation(rotation, 350); orientation(rotation, 80);
                Runnable apply = (Runnable) get(rotation, "apply");
                require(owner.main.hasCallbacks(apply), "a changed posture queues rotation before sleep");
                ((BroadcastReceiver) get(owner, "screenReceiver")).onReceive(context, new Intent(Intent.ACTION_SCREEN_OFF));
                require(rotation.enabled() && !sensor.listening && !rotation.valid(), "screen off preserves switch and suspends sensor");
                require(!owner.main.hasCallbacks(apply) && (int) get(rotation, "candidate") == -1, "sleep cancels queued candidate");
                orientation(rotation, 170); apply.run();
                require(!sensor.listening && !owner.main.hasCallbacks(apply), "late sensor and timer callbacks cannot resume a suspended session");
                rotation.checkActive(); rotation.checkActive();
                require(rotation.enabled() && sensor.listening && sensor.starts == 2, "awake unlocked display resumes sensor once");
                require((int) get(rotation, "reference") == 350 && !owner.main.hasCallbacks(apply), "resume retains calibration and waits for a fresh reading");
                orientation(rotation, 80); require((int) get(rotation, "candidate") == 3, "fresh reading retains original calibrated direction");
                owner.main.removeCallbacks(apply); apply.run();
            });
            drain(worker); require(commands.get() == 1, "resumed mode rotates through the selected-display shell route");
            test.runOnMainSync(() -> {
                CoverRotation rotation = mode[0]; Runnable apply = (Runnable) get(rotation, "apply"), sample = (Runnable) get(rotation, "sample");
                serviceLookups.set(0);
                for (int angle = 70; angle < 90; angle++) orientation(rotation, angle);
                require(serviceLookups.get() == 0, "twenty direction updates do not rediscover displays or query keyguard");
                require(rotation.valid() && serviceLookups.get() > 0, "command safety validation still resolves the selected display and keyguard");
                service[0].main.removeCallbacks(apply); service[0].main.removeCallbacks(sample);
                sensor.current.emit(80); Sensors.Sensor old = sensor.current;
                rotation.suspend(); rotation.checkActive();
                require(sensor.current != old && (int) get(rotation, "candidate") == -1, "resume creates a fresh listener without using the old angle cache");
                old.onOrientationChanged(170);
                require((int) get(rotation, "candidate") == -1, "old listener cannot feed a new sensor session");
                sensor.current.emit(80);
                require((int) get(rotation, "candidate") == 3, "identical gravity after wake is delivered by the fresh listener");
                service[0].main.removeCallbacks(apply);
                sensor.current.emit(170); sensor.current.emit(260);
                require((int) get(rotation, "candidate") == 3 && (int) get(rotation, "pendingAngle") == 260 && service[0].main.hasCallbacks(sample), "rapid sensor updates coalesce to the newest angle without another immediate evaluation");
            });
            android.os.SystemClock.sleep(2050);
            test.runOnMainSync(() -> {
                require((int) get(mode[0], "candidate") == 1 && !service[0].main.hasCallbacks((Runnable) get(mode[0], "sample")), "latest angle is processed at two seconds without a repeating timer");
                service[0].main.removeCallbacks((Runnable) get(mode[0], "apply"));
                java.util.concurrent.atomic.AtomicBoolean bridgeBusy = (java.util.concurrent.atomic.AtomicBoolean) get(bridge, "busy");
                bridgeBusy.set(true);
                try {
                    Runnable apply = (Runnable) get(mode[0], "apply");
                    orientation(mode[0], 170); service[0].main.removeCallbacks(apply);
                    for (int attempt = 0; attempt < 4; attempt++) {
                        apply.run(); require(mode[0].enabled(), "temporary Shizuku contention preserves the switch");
                        require(service[0].main.hasCallbacks(apply) == (attempt < 3), "same direction has exactly three bounded retries"); service[0].main.removeCallbacks(apply);
                    }
                    require((int) get(mode[0], "busyRetries") == 3, "retry budget stops growing after exhaustion");
                    orientation(mode[0], 175); require(!service[0].main.hasCallbacks(apply), "same target direction does not restart exhausted retries");
                    orientation(mode[0], 260); service[0].main.removeCallbacks(apply); apply.run();
                    require(service[0].main.hasCallbacks(apply), "a new target direction gets its own retry budget");
                    mode[0].suspend(); require(!service[0].main.hasCallbacks(apply), "sleep cancels busy retries"); mode[0].checkActive();
                } finally { bridgeBusy.set(false); }
            });
            CountDownLatch waiting = new CountDownLatch(1);
            worker.execute(() -> { waiting.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            require(waiting.await(3, TimeUnit.SECONDS), "serial worker held before queued rotation");
            test.runOnMainSync(() -> {
                CoverRotation rotation = mode[0]; Runnable apply = (Runnable) get(rotation, "apply");
                orientation(rotation, 170); service[0].main.removeCallbacks(apply); apply.run();
                rotation.suspend(); rotation.checkActive();
            });
            release.countDown(); drain(worker);
            require(commands.get() == 1 && mode[0].enabled() && sensor.listening, "sleep invalidates queued command even after unlock; stale cancellation does not disable resumed mode");
            CountDownLatch rapidWaiting = new CountDownLatch(1);
            worker.execute(() -> { rapidWaiting.countDown(); try { rapidRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            require(rapidWaiting.await(3, TimeUnit.SECONDS), "worker held for rapid sleep/wake race");
            test.runOnMainSync(() -> {
                Runnable apply = (Runnable) get(mode[0], "apply");
                orientation(mode[0], 170); service[0].main.removeCallbacks(apply); apply.run();
                int generation = (int) get(mode[0], "generation");
                target.setSurface(null); awaitState(target, android.view.Display.STATE_OFF);
                // Hold the main thread until the worker rejects the request, then wake before its callback.
                rapidRelease.countDown();
                try { worker.submit(() -> { }).get(3, TimeUnit.SECONDS); } catch (Exception error) { throw new AssertionError(error); }
                target.setSurface(surface.getSurface()); awaitState(target, android.view.Display.STATE_ON);
                require((int) get(mode[0], "generation") == generation, "no lifecycle event has invalidated the generation yet");
            });
            drain(worker);
            require(commands.get() == 1 && mode[0].enabled() && sensor.listening, "worker-detected sleep cancellation cannot disable the mode after rapid wake");
            target.setSurface(null);
            long deadline = android.os.SystemClock.uptimeMillis() + 2000;
            while (target.getDisplay().getState() != android.view.Display.STATE_OFF && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20);
            require(target.getDisplay().getState() == android.view.Display.STATE_OFF, "selected display can sleep without a global screen-off broadcast");
            test.runOnMainSync(() -> { mode[0].checkActive(); require(mode[0].enabled() && !sensor.listening, "selected-display sleep also preserves the switch"); });
            target.setSurface(surface.getSurface());
            deadline = android.os.SystemClock.uptimeMillis() + 2000;
            while (target.getDisplay().getState() != android.view.Display.STATE_ON && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20);
            require(target.getDisplay().getState() == android.view.Display.STATE_ON, "selected display wakes");
            test.runOnMainSync(() -> {
                CoverRotation rotation = mode[0]; rotation.checkActive();
                require(rotation.enabled() && sensor.listening, "selected-display wake resumes the same mode");
                rotation.suspend(); service[0].stopAutomaticRotation(); rotation.checkActive();
                require(!rotation.enabled() && !sensor.listening, "explicit stop while suspended remains off after wake");
                set(rotation, "busy", true); rotation.afterCurrent(() -> { throw new AssertionError("stale manual action survived sleep"); }); rotation.suspend();
                require(get(rotation, "afterFlight") == null, "sleep also cancels a manual rotation waiting after automatic mode was disabled"); set(rotation, "busy", false);
                set(rotation, "enabled", true); set(rotation, "suspended", true); prefs.data.edit().putBoolean("enabled", false).commit(); rotation.checkActive();
                require(!rotation.enabled() && !sensor.listening, "disabled assistant clears suspended mode");
                set(rotation, "enabled", true); set(rotation, "suspended", true); prefs.data.edit().putBoolean("enabled", true).putInt("display", Integer.MAX_VALUE).commit(); rotation.checkActive();
                require(!rotation.enabled() && !sensor.listening && commands.get() == 1, "missing selected display stops without primary fallback");
            });
            test.runOnMainSync(() -> {
                prefs.data.edit().putInt("display", target.getDisplay().getDisplayId()).commit();
                set(mode[0], "enabled", true); set(mode[0], "suspended", true); mode[0].checkActive();
                orientation(mode[0], 350); orientation(mode[0], 80);
                Runnable apply = (Runnable) get(mode[0], "apply"); service[0].main.removeCallbacks(apply);
                java.util.concurrent.atomic.AtomicBoolean bridgeBusy = (java.util.concurrent.atomic.AtomicBoolean) get(bridge, "busy");
                bridgeBusy.set(true); try { apply.run(); } finally { bridgeBusy.set(false); }
                require(mode[0].enabled() && service[0].main.hasCallbacks(apply), "busy request schedules recovery while keeping mode enabled");
            });
            android.os.SystemClock.sleep(2050); drain(worker);
            require(commands.get() == 2 && mode[0].enabled() && (int) get(mode[0], "busyRetries") == 0, "scheduled retry succeeds after bridge contention clears");
            return "PASS: " + assertions + " automatic rotation lifecycle checks; Samsung physical lock/unlock remains unverified";
        } finally {
            release.countDown(); rapidRelease.countDown(); drain(worker); set(bridge, "remote", remote);
            test.runOnMainSync(() -> {
                if (mode[0] != null) mode[0].stop();
                if (service[0] != null) { service[0].main.removeCallbacksAndMessages(null); ((ExecutorService) get(service[0], "files")).shutdown(); }
                CoverService.instance = previous; CoverService.status = status;
                var edit = prefs.data.edit(); if (hadDisplay) edit.putInt("display", oldDisplay); else edit.remove("display"); if (hadEnabled) edit.putBoolean("enabled", oldEnabled); else edit.remove("enabled"); edit.commit();
            });
            if (target != null) target.release(); surface.close();
        }
    }
    private void awaitState(VirtualDisplay target, int state) {
        long deadline = android.os.SystemClock.uptimeMillis() + 2000;
        while (target.getDisplay().getState() != state && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20);
        require(target.getDisplay().getState() == state, "isolated display reaches state " + state);
    }
    private void drain(ExecutorService worker) throws Exception { worker.submit(() -> { }).get(5, TimeUnit.SECONDS); test.waitForIdleSync(); }
}
