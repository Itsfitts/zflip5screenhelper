package io.github.flipcover.controls;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/** Serial window reads; request/invalidate/close and delivery belong to the main thread. */
final class CoverWindowQuery implements AutoCloseable {
    record Target(int displayId, String name, int rotation, int width, int height) { }
    record Window(int id, int type, int layer, String title, boolean active, boolean focused, int left, int top, int right, int bottom) { }
    interface Reader { List<Window> read(int displayId); }

    static java.util.List<Window> readWindows(android.accessibilityservice.AccessibilityService service, int displayId) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) throw new IllegalStateException("Window queries must run off main");
        android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all = service.getWindowsOnAllDisplays();
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> items = all.get(displayId);
            if (items == null) return null;
            java.util.ArrayList<Window> snapshot = new java.util.ArrayList<>();
            android.graphics.Rect bounds = new android.graphics.Rect();
            for (android.view.accessibility.AccessibilityWindowInfo window : items) {
                window.getBoundsInScreen(bounds); CharSequence title = window.getTitle();
                snapshot.add(new Window(window.getId(), window.getType(), window.getLayer(), title == null ? null : title.toString(), window.isActive(), window.isFocused(), bounds.left, bounds.top, bounds.right, bounds.bottom));
            }
            return java.util.List.copyOf(snapshot);
        } finally { for (int i = 0; i < all.size(); i++) for (android.view.accessibility.AccessibilityWindowInfo window : all.valueAt(i)) window.recycle(); }
    }

    private final ExecutorService worker;
    private final Executor main;
    private final Reader reader;
    private final BiConsumer<Target, List<Window>> result;
    private Target target, pending;
    private long generation;
    private boolean running;
    private volatile boolean closed;

    CoverWindowQuery(ExecutorService worker, Executor main, Reader reader, BiConsumer<Target, List<Window>> result) {
        this.worker = worker; this.main = main; this.reader = reader; this.result = result;
    }

    void request(Target next) {
        request(next, false);
    }
    void request(Target next, boolean windowChanged) {
        if (closed) return;
        if (next.displayId() <= 0) { invalidate(); return; }
        if (windowChanged || !next.equals(target)) generation++;
        target = next;
        pending = next;
        start();
    }

    private void start() {
        if (closed || running || pending == null) return;
        Target requested = pending; pending = null;
        long session = generation; running = true;
        worker.execute(() -> {
            if (closed) return;
            List<Window> windows;
            try { windows = reader.read(requested.displayId()); }
            catch (RuntimeException ignored) { windows = null; }
            List<Window> snapshot = windows;
            if (!closed) main.execute(() -> complete(session, requested, snapshot));
        });
    }

    private void complete(long session, Target requested, List<Window> windows) {
        if (closed) return;
        running = false;
        try {
            if (session == generation && requested.equals(target)) result.accept(requested, windows);
        } finally { start(); }
    }

    void invalidate() {
        generation++; target = null; pending = null;
        // Binder calls cannot reliably be interrupted. Keep the occupied worker;
        // never start a replacement thread or queue one job per accessibility event.
    }

    @Override public void close() {
        closed = true; invalidate(); worker.shutdownNow();
    }
}
