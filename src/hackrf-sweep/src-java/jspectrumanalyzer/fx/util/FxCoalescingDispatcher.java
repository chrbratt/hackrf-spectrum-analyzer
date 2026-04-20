package jspectrumanalyzer.fx.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;

/**
 * Coalescing dispatcher that funnels a stream of values produced on a
 * background thread into at most one {@link Platform#runLater} call in
 * flight at any time. The latest value always wins; intermediate values
 * are dropped.
 *
 * <p>Why we need this: services like
 * {@link jspectrumanalyzer.wifi.DensityHistogramService} and
 * {@link jspectrumanalyzer.wifi.ChannelOccupancyService} publish
 * snapshots on the engine thread. Subscribing them with a naive
 * {@code service.addListener(snap -> Platform.runLater(() -> view.setSnapshot(snap)))}
 * means every publish enqueues a fresh runLater that <em>retains</em>
 * its snapshot until the FX thread drains it. If the FX thread can't
 * keep up - which is exactly what happens when the view's repaint is
 * expensive - the queue accumulates pending lambdas (and therefore
 * snapshots), heap balloons, and after a few minutes GC trashing
 * freezes the app.</p>
 *
 * <p>This dispatcher pins the in-flight runLater count to at most one,
 * regardless of how fast the producer runs, while still ensuring the
 * most recently published value always reaches the consumer.</p>
 *
 * @param <T> snapshot type
 */
public final class FxCoalescingDispatcher<T> implements Consumer<T> {

    private final Consumer<T> fxConsumer;
    private final AtomicReference<T> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    /**
     * @param fxConsumer callback invoked on the JavaFX application
     *                   thread with the latest pending value. Never
     *                   invoked with {@code null}.
     */
    public FxCoalescingDispatcher(Consumer<T> fxConsumer) {
        this.fxConsumer = fxConsumer;
    }

    @Override
    public void accept(T value) {
        if (value == null) return;
        pending.set(value);
        if (scheduled.compareAndSet(false, true)) {
            Platform.runLater(this::drain);
        }
    }

    private void drain() {
        try {
            T v = pending.getAndSet(null);
            if (v != null) fxConsumer.accept(v);
        } finally {
            scheduled.set(false);
            // If a producer published while we were running the consumer
            // (between getAndSet(null) and scheduled.set(false)), the new
            // value is in `pending` and `scheduled` is now false, so the
            // next accept() call will reschedule. No value lost.
            if (pending.get() != null && scheduled.compareAndSet(false, true)) {
                Platform.runLater(this::drain);
            }
        }
    }
}
