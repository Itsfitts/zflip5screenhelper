package io.github.flipcover.controls;

import android.accessibilityservice.AccessibilityService;
import android.app.Instrumentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the production reader and glass timeout against a stalled system call. */
final class WindowQueryChecks {
    private final Instrumentation test;
    WindowQueryChecks(Instrumentation test) { this.test = test; }
    String run() throws Exception {
        Context context = test.getTargetContext();
        VirtualDisplay display = context.getSystemService(DisplayManager.class).createVirtualDisplay("window-query-check", 320, 240, 160, null, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        if (display == null) throw new AssertionError("Isolated secondary display required");
        Probe blocked = new Probe(context, true), queued = new Probe(context, false);
        PanelGlassSession[] sessions = new PanelGlassSession[2];
        AtomicInteger finished = new AtomicInteger(), heartbeat = new AtomicInteger();
        try {
            test.runOnMainSync(() -> {
                sessions[0] = new PanelGlassSession(context, display.getDisplay());
                sessions[0].capture(blocked, () -> true, finished::incrementAndGet, false, blocked);
                new android.os.Handler(Looper.getMainLooper()).postDelayed(heartbeat::incrementAndGet, 30);
            });
            require(blocked.entered.await(2, TimeUnit.SECONDS), "Production window reader reaches worker");
            test.runOnMainSync(() -> {
                sessions[1] = new PanelGlassSession(context, display.getDisplay());
                sessions[1].capture(queued, () -> true, () -> { throw new AssertionError("Closed queued session must not complete"); }, false, queued);
                sessions[1].close();
            });
            SystemClock.sleep(230); test.waitForIdleSync();
            require(blocked.offMain, "Binder read is off the Android main looper");
            require(heartbeat.get() == 1, "Main looper still handles events while Binder is blocked");
            require(finished.get() == 1 && "timeout".equals(sessions[0].state), "150ms glass fallback completes while the read remains blocked");
            require(blocked.screenshots.get() == 0, "No screenshot starts before window lookup completes");
            blocked.release.countDown(); require(blocked.returned.await(2, TimeUnit.SECONDS), "Stalled call eventually returns");
            // A sentinel on the same worker proves canceled work did not run behind it.
            var field = PanelGlassSession.class.getDeclaredField("WORK"); field.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor worker = (java.util.concurrent.ThreadPoolExecutor) field.get(null);
            worker.submit(() -> { }).get(2, TimeUnit.SECONDS); test.waitForIdleSync();
            require(queued.reads.get() == 0 && queued.screenshots.get() == 0, "Closing a queued session cancels its window read");
            require(blocked.screenshots.get() == 0 && finished.get() == 1, "Late result cannot capture or complete the timed-out session again");
            test.runOnMainSync(() -> {
                try { CoverWindowQuery.readWindows(queued, display.getDisplay().getDisplayId()); throw new AssertionError("Main-thread guard missing"); }
                catch (IllegalStateException expected) { }
            });
            return "PASS: blocked production window read leaves Android main responsive; 150ms fallback, queued cancellation, late-result rejection and main-thread guard";
        } finally {
            blocked.release.countDown();
            test.runOnMainSync(() -> { for (PanelGlassSession session : sessions) if (session != null) session.close(); });
            display.release();
        }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private static final class Probe extends AccessibilityService implements CoverWindowQuery.Reader {
        final boolean block;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger(), screenshots = new AtomicInteger();
        volatile boolean offMain;
        Probe(Context context, boolean block) { attachBaseContext(context); this.block = block; }
        @Override public List<CoverWindowQuery.Window> read(int displayId) {
            offMain = Looper.myLooper() != Looper.getMainLooper(); reads.incrementAndGet(); entered.countDown();
            if (block) { boolean waiting = true; while (waiting) try { release.await(); waiting = false; } catch (InterruptedException ignored) { } }
            returned.countDown(); return List.of();
        }
        @Override public void takeScreenshot(int display, Executor executor, TakeScreenshotCallback callback) { screenshots.incrementAndGet(); }
        @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
        @Override public void onInterrupt() { }
    }
}
