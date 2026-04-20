package jspectrumanalyzer.wifi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-Wi-Fi-channel interference tally derived from {@link WifiScanService}
 * snapshots.
 *
 * <p>For every fresh AP snapshot we walk {@link WifiChannelCatalog#ALL} and
 * for each 20 MHz primary channel count two things:
 * <ul>
 *   <li><b>Co-channel APs</b> - APs whose primary 20 MHz centre falls
 *       inside this channel's {@code [lowMhz, highMhz]} window. These
 *       compete for the same airtime as a station tuned to the channel.</li>
 *   <li><b>Adjacent-channel APs</b> - APs whose primary is on a different
 *       channel but whose operating bandwidth (HT/VHT/HE) bleeds into this
 *       channel. These cause analog interference even when the MAC layer
 *       does not see them as collisions.</li>
 * </ul>
 *
 * <p>The split mirrors what Chanalyzer calls "Co-channel utilization" vs
 * "Overlapping channels". It is the single most actionable Wi-Fi metric
 * for a deployment audit: a channel with 5 co-channel APs is a busy mesh,
 * a channel with 0 co-channel + 8 adjacent APs needs to move to a cleaner
 * primary.
 *
 * <h2>How "primary" and "footprint" are decided</h2>
 * The Windows {@code WLAN_BSS_ENTRY} {@code ulChCenterFrequency} field is
 * the centre of the primary 20 MHz channel for HT/VHT/HE APs.
 * "Centre frequency falls in {@code [low, high)}" is therefore exactly
 * the right co-channel test - it answers "does this AP's CSMA primary
 * land on this channel?".
 *
 * <p>The adjacent test uses the AP's <em>bonded</em> centre frequency
 * (parsed from the VHT/HE Operation IE, see {@code WifiIeParser}) plus
 * the bonded bandwidth as the spectral footprint. That gives the true
 * 80 / 160 MHz block extent for VHT/HE radios; for legacy HT 40 the
 * bonded centre is derived from the HT secondary-channel offset. APs
 * whose IE blob did not carry seg0 fall back to the primary centre,
 * which can mis-anchor the footprint by up to {@code bandwidth/2 - 10}
 * MHz - we accept that as the safer of two evils (the alternative
 * would be claiming "no overlap" when there obviously is).
 *
 * <h2>Threading</h2>
 * {@link #onScan} runs on the {@link WifiScanService} polling thread.
 * Listeners receive immutable snapshots from the same thread; FX
 * consumers wrap their callbacks in {@code Platform.runLater}.
 */
public final class ChannelInterferenceService {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelInterferenceService.class);

    /** Per-channel result. Counts can both be zero (clean channel). */
    public record ChannelInterference(WifiChannelCatalog.Channel channel,
                                      int coChannelCount,
                                      int adjacentChannelCount) {}

    /** Immutable snapshot of all channels. */
    public record Snapshot(List<ChannelInterference> entries) {}

    private final AtomicReference<Snapshot> latest =
            new AtomicReference<>(new Snapshot(emptyEntries()));
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    public ChannelInterferenceService(WifiScanService scanService) {
        scanService.addListener(this::onScan);
    }

    public Snapshot getLatest() {
        return latest.get();
    }

    public void addListener(Consumer<Snapshot> l) {
        listeners.add(l);
        l.accept(latest.get());
    }

    public void removeListener(Consumer<Snapshot> l) {
        listeners.remove(l);
    }

    private void onScan(List<WifiAccessPoint> snapshot) {
        try {
            List<ChannelInterference> out = new ArrayList<>(WifiChannelCatalog.ALL.size());
            for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
                int co = 0;
                int adj = 0;
                for (WifiAccessPoint ap : snapshot) {
                    if (ap.band() == null) continue;
                    if (ap.band() != ch.band()) continue;
                    int primary = ap.centerFrequencyMhz();
                    boolean primaryHere = primary >= ch.lowMhz() && primary < ch.highMhz();
                    if (primaryHere) {
                        co++;
                        continue;
                    }
                    // Spectral footprint anchored on the bonded centre, not
                    // the primary, so 80 / 160 MHz blocks correctly overlap
                    // every 20 MHz sub-channel they actually occupy. The
                    // record's compact constructor falls bondedCenterMhz
                    // back to primary when the IE did not carry seg0, so
                    // legacy HT 20 / 40 APs still get a sane footprint.
                    int bw = Math.max(20, ap.bandwidthMhz());
                    int bondedCenter = ap.bondedCenterMhz();
                    int apLow = bondedCenter - bw / 2;
                    int apHigh = bondedCenter + bw / 2;
                    if (apLow < ch.highMhz() && apHigh > ch.lowMhz()) {
                        adj++;
                    }
                }
                out.add(new ChannelInterference(ch, co, adj));
            }
            Snapshot snap = new Snapshot(Collections.unmodifiableList(out));
            latest.set(snap);
            for (Consumer<Snapshot> l : listeners) {
                try {
                    l.accept(snap);
                } catch (RuntimeException ex) {
                    LOG.warn("Interference listener threw", ex);
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("Channel interference compute failed", ex);
        }
    }

    private static List<ChannelInterference> emptyEntries() {
        List<ChannelInterference> out = new ArrayList<>(WifiChannelCatalog.ALL.size());
        for (WifiChannelCatalog.Channel ch : WifiChannelCatalog.ALL) {
            out.add(new ChannelInterference(ch, 0, 0));
        }
        return Collections.unmodifiableList(out);
    }
}
