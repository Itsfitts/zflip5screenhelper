package io.github.flipcover.controls;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CoverWindowQueryTest {
    private static final CoverWindowQuery.Target COVER = new CoverWindowQuery.Target(1, "cover", 0, 720, 748);
    private static final class Worker extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean stopped;
        public void execute(Runnable task) { assertFalse(stopped); tasks.add(task); }
        public void shutdown() { stopped = true; }
        public List<Runnable> shutdownNow() { stopped = true; List<Runnable> canceled = new ArrayList<>(tasks); tasks.clear(); return canceled; }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
        void next() { tasks.remove().run(); }
    }
    private static final class Host {
        final Worker worker = new Worker();
        final ArrayDeque<Runnable> main = new ArrayDeque<>();
        final List<CoverWindowQuery.Target> delivered = new ArrayList<>();
        int reads;
        boolean fail;
        final CoverWindowQuery query = new CoverWindowQuery(worker, main::add, id -> {
            reads++; if (fail) throw new IllegalStateException("service disconnected"); return List.of();
        }, (target, windows) -> { assertEquals(fail, windows == null); delivered.add(target); });
        void finish() { worker.next(); main.remove().run(); }
    }

    @Test public void eventBurstKeepsOnlyOneTrailingReadAndDoesNotPoll() {
        Host host = new Host(); host.query.request(COVER);
        for (int i = 0; i < 1000; i++) host.query.request(COVER);
        assertEquals(0, host.reads); assertEquals(1, host.worker.tasks.size());
        host.finish(); assertEquals(List.of(COVER), host.delivered); assertEquals(1, host.worker.tasks.size());
        host.finish(); assertEquals(2, host.reads); assertTrue(host.worker.tasks.isEmpty()); assertTrue(host.main.isEmpty());
        host.query.close();
    }

    @Test public void sleepThenWakeRejectsOldResultEvenOnSameDisplay() {
        Host host = new Host(); host.query.request(COVER); host.worker.next();
        host.query.invalidate(); host.query.request(COVER);
        assertTrue(host.worker.tasks.isEmpty()); // Old completion still owns the slot.
        host.main.remove().run(); assertTrue(host.delivered.isEmpty());
        host.finish(); assertEquals(List.of(COVER), host.delivered); host.query.close();
    }

    @Test public void newerWindowEventsSupersedeSnapshotButPlainRefreshDoesNot() {
        Host host = new Host(); host.query.request(COVER, true);
        for (int i = 0; i < 1000; i++) host.query.request(COVER, true);
        assertEquals(1, host.worker.tasks.size());
        host.finish(); assertTrue(host.delivered.isEmpty());
        host.query.request(COVER); // Layout refresh must not starve the event's snapshot.
        host.finish(); assertEquals(List.of(COVER), host.delivered);
        host.finish(); assertEquals(3, host.reads); assertTrue(host.worker.tasks.isEmpty());
        host.query.close();
    }

    @Test public void rotationAndDisplayReplacementRejectStaleResults() {
        for (CoverWindowQuery.Target next : List.of(new CoverWindowQuery.Target(1, "cover", 1, 748, 720), new CoverWindowQuery.Target(1, "replacement", 0, 720, 748), new CoverWindowQuery.Target(2, "other", 0, 720, 748))) {
            Host host = new Host(); host.query.request(COVER); host.query.request(next);
            host.finish(); assertTrue(host.delivered.isEmpty());
            host.finish(); assertEquals(List.of(next), host.delivered); host.query.close();
        }
    }

    @Test public void canceledOrPrimaryDisplayNeverStartsAnotherRead() {
        Host host = new Host(); host.query.request(COVER); host.query.request(COVER);
        host.query.request(new CoverWindowQuery.Target(0, "main", 0, 800, 1280));
        host.finish(); assertTrue(host.delivered.isEmpty()); assertTrue(host.worker.tasks.isEmpty());
        host.query.close();
    }

    @Test public void closeDropsAlreadyPostedCompletionAndPendingRefresh() {
        Host host = new Host(); host.query.request(COVER); host.query.request(COVER); host.worker.next();
        host.query.close(); host.main.remove().run(); host.query.request(COVER);
        assertTrue(host.delivered.isEmpty()); assertTrue(host.worker.stopped); assertTrue(host.worker.tasks.isEmpty());
    }

    @Test public void failureDoesNotRetryButNextEventCanRecover() {
        Host host = new Host(); host.fail = true; host.query.request(COVER); host.finish();
        assertEquals(1, host.delivered.size()); assertTrue(host.worker.tasks.isEmpty());
        host.fail = false; host.query.request(COVER); host.finish();
        assertEquals(2, host.delivered.size()); host.query.close();
    }

    @Test public void blockedSystemReadLeavesMainResponsiveAndCloseDiscardsIt() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var main = new LinkedBlockingQueue<Runnable>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger(), delivered = new AtomicInteger();
        Thread owner = Thread.currentThread();
        CoverWindowQuery query = new CoverWindowQuery(worker, main::add, id -> {
            assertNotSame(owner, Thread.currentThread()); reads.incrementAndGet(); entered.countDown();
            // Model a Binder transaction that ignores Java thread interruption.
            boolean waiting = true;
            while (waiting) try { release.await(); waiting = false; } catch (InterruptedException ignored) { }
            return List.of();
        }, (target, windows) -> delivered.incrementAndGet());
        try {
            query.request(COVER); assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) query.request(COVER);
            AtomicInteger heartbeat = new AtomicInteger(); main.add(heartbeat::incrementAndGet);
            main.remove().run(); assertEquals(1, heartbeat.get()); assertEquals(1, reads.get());
            query.close(); release.countDown(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            for (Runnable action; (action = main.poll()) != null;) action.run();
            assertEquals(0, delivered.get()); assertEquals(1, reads.get());
        } finally { release.countDown(); query.close(); }
    }
}
