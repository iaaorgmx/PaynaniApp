package com.iaaorgmx.paynani.webchat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** #5: una foto que termina de bajar después de que Webchat se destruyó no cierra la app. */
public class SafeIoTest {

    @Test
    public void runsWhileAlive() throws Exception {
        SafeIo io = new SafeIo();
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(io.run(done::countDown));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        io.shutdown();
    }

    @Test
    public void afterShutdownTheTaskIsDroppedWithoutThrowing() {
        SafeIo io = new SafeIo();
        io.shutdown();
        AtomicInteger ran = new AtomicInteger();
        // Antes de #5 esto era io.execute(...) sobre un executor terminado: RejectedExecutionException.
        assertFalse(io.run(ran::incrementAndGet));
        assertEquals(0, ran.get());
    }

    @Test
    public void aRejectionThatSlipsPastTheCheckIsAlsoDropped() {
        // isShutdown() dice que no, pero execute rechaza: el caso de la carrera entre los dos.
        ExecutorService racing = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public java.util.List<Runnable> shutdownNow() { return java.util.Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            @Override public void execute(Runnable r) { throw new java.util.concurrent.RejectedExecutionException("terminado"); }
        };
        assertFalse(new SafeIo(racing).run(() -> {}));
    }

    @Test
    public void theRealExecutorThrowsWithoutTheGuard() {
        // El comportamiento que provocaba el cierre, para que la prueba de arriba signifique algo.
        ExecutorService raw = Executors.newSingleThreadExecutor();
        raw.shutdownNow();
        try {
            raw.execute(() -> {});
            throw new AssertionError("se esperaba RejectedExecutionException");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            // ok
        }
    }
}
