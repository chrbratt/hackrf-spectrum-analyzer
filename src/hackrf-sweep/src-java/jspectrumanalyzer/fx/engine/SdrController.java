package jspectrumanalyzer.fx.engine;

import javafx.application.Platform;
import jspectrumanalyzer.core.FrequencyPlan;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.fx.model.SettingsStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single point of intent for "tell the SDR to do something". Replaces
 * the legacy pattern of every UI component writing directly to
 * {@link SettingsStore} mutators (which conflated "this is the current
 * state" with "this is a command to change state" and required
 * scattered {@code suppressEvents} / {@code lastSelfWrite} / {@code
 * updating} flags to break the resulting feedback loops).
 *
 * <h2>Contract</h2>
 * Every {@code request*} method:
 * <ul>
 *   <li>Is safe to call from any thread (marshals to FX when needed
 *       because all settings observers ultimately fan out on the FX
 *       thread anyway).</li>
 *   <li>Performs the value-equality short-circuit before mutating the
 *       model so callers don't need to dedup themselves.</li>
 *   <li>Treats the {@link SettingsStore} as a read-mostly publication
 *       channel: external readers (charts, overlays, Wi-Fi window)
 *       continue to subscribe to the same {@link
 *       jspectrumanalyzer.core.HackRFSettings} model values and see the
 *       controller's writes the same way they always did.</li>
 * </ul>
 *
 * <h2>What this is NOT</h2>
 * <ul>
 *   <li>Not a debouncer. Each call site already debounces at the level
 *       that makes UX sense (e.g. {@code ChartZoomController}'s scroll
 *       coalescer). Adding a second timer here would just double the
 *       latency without removing real restarts.</li>
 *   <li>Not a state cache. The model value remains the source of
 *       truth - this class only writes through.</li>
 *   <li>Not a command bus. We deliberately keep the API method-shaped
 *       (rather than an opaque {@code Command} hierarchy) because the
 *       set of intents is small and well-known.</li>
 * </ul>
 */
public final class SdrController {

    private static final Logger LOG = LoggerFactory.getLogger(SdrController.class);

    private final SettingsStore settings;

    public SdrController(SettingsStore settings) {
        this.settings = settings;
    }

    /**
     * Switch to a single-range scan plan. Clears any active multi-segment
     * plan first so {@link SettingsStore#getEffectivePlan()} resolves to
     * the new single range immediately (otherwise the engine would
     * still see the old multi-segment plan and ignore the new
     * frequency).
     *
     * <p>Used by: chart drag/scroll/right-click zoom, single-band Wi-Fi
     * window selection, manual frequency entry in the Frequency
     * selector, the Scan tab's "Off" multi-band entry.
     */
    public void requestRetune(FrequencyRange range) {
        if (range == null) return;
        runOnFx(() -> {
            FrequencyPlan currentPlan = settings.getFrequencyPlan().getValue();
            if (currentPlan != null) {
                settings.getFrequencyPlan().setValue(null);
            }
            FrequencyRange currentFreq = settings.getFrequency().getValue();
            if (!range.equals(currentFreq)) {
                settings.getFrequency().setValue(range);
            }
        });
    }

    /**
     * Switch to a multi-segment scan plan (e.g. Wi-Fi 2.4 + 5 + 6E,
     * a custom multi-range from the Scan tab).
     *
     * <p>The single-range {@link SettingsStore#getFrequency()} value is
     * left untouched so reverting from multi-segment back to single
     * mode keeps whatever the user had picked last in the slider.
     */
    public void requestRetunePlan(FrequencyPlan plan) {
        if (plan == null) {
            requestClearPlan();
            return;
        }
        runOnFx(() -> {
            FrequencyPlan currentPlan = settings.getFrequencyPlan().getValue();
            if (!plan.equals(currentPlan)) {
                settings.getFrequencyPlan().setValue(plan);
            }
        });
    }

    /**
     * Drop the active multi-segment plan, falling back to whatever
     * {@link SettingsStore#getFrequency()} currently holds. Used by
     * "Off" in the multi-band picker.
     */
    public void requestClearPlan() {
        runOnFx(() -> {
            if (settings.getFrequencyPlan().getValue() != null) {
                settings.getFrequencyPlan().setValue(null);
            }
        });
    }

    /**
     * Ask the engine to enter the given run state. The engine's
     * launcher thread reconciles the actual native state from this
     * model value (see {@code SpectrumEngine.wireRunStateObserver}).
     */
    public void requestSetRunning(boolean running) {
        runOnFx(() -> {
            if (settings.isRunningRequested().getValue() != running) {
                settings.isRunningRequested().setValue(running);
            }
        });
    }

    /**
     * Convenience for the Start/Stop button and the F5 keyboard
     * shortcut: flip the run-requested flag without the caller having
     * to read-and-write.
     */
    public void toggleRunning() {
        runOnFx(() -> settings.isRunningRequested().setValue(
                !settings.isRunningRequested().getValue()));
    }

    /**
     * All settings observers expect to fire on the FX thread (they
     * touch JavaFX controls). Marshalling here means the call sites
     * don't have to wrap their own {@code Platform.runLater} - and a
     * future move to a different threading model only changes one
     * place.
     */
    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            try {
                r.run();
            } catch (RuntimeException ex) {
                LOG.error("SdrController action failed", ex);
            }
        } else {
            Platform.runLater(() -> {
                try {
                    r.run();
                } catch (RuntimeException ex) {
                    LOG.error("SdrController action failed", ex);
                }
            });
        }
    }
}
