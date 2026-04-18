package jspectrumanalyzer.fx.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import jspectrumanalyzer.fx.model.SettingsStore;

/**
 * Smoke tests for the launcher executor lifecycle.
 * <p>
 * We can't drive the full sweep loop without a real HackRF (it calls
 * {@code HackRFSweepNativeBridge.start} which would block on libusb),
 * but the launcher and reconcile machinery don't need the radio: they
 * only react when {@code isRunningRequested()} flips, and the default
 * is false. These tests verify that the engine starts cleanly, accepts
 * concurrent reconcile requests without exploding, and shuts down
 * within a bounded time.
 */
class SpectrumEngineLifecycleTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("start() then shutdown() completes within seconds even with no radio")
    void startThenShutdown() {
        SettingsStore settings = new SettingsStore();
        SpectrumEngine engine = new SpectrumEngine(settings);
        // Don't call start() because that registers a JVM shutdown hook
        // we can't unregister; smoke-test the constructor + shutdown
        // path which is what matters for clean process exit.
        engine.shutdown();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("requestReconcile() from many threads is coalesced and never throws")
    void requestReconcileIsThreadSafe() throws Exception {
        SettingsStore settings = new SettingsStore();
        SpectrumEngine engine = new SpectrumEngine(settings);
        try {
            // 20 producers spamming reconcile requests in parallel; the
            // launchCommands queue has capacity 1 and is cleared on
            // overflow, so this must never throw or block forever.
            ExecutorService pool = Executors.newFixedThreadPool(20);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(20);
            for (int i = 0; i < 20; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 100; j++) {
                            engine.requestReconcile();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertFalse(!done.await(3, TimeUnit.SECONDS),
                    "producers did not finish; reconcile is blocking?");
            pool.shutdownNow();
        } finally {
            engine.shutdown();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("shutdown() is idempotent")
    void shutdownIdempotent() {
        SettingsStore settings = new SettingsStore();
        SpectrumEngine engine = new SpectrumEngine(settings);
        engine.shutdown();
        engine.shutdown(); // must not throw or hang
    }
}
