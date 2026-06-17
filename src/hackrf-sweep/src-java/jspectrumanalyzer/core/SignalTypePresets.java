package jspectrumanalyzer.core;

import java.util.ArrayList;

/**
 * Built-in frequency-allocation tables for common 2.4 GHz radio technologies,
 * surfaced alongside the CSV country tables in the allocation-overlay picker.
 *
 * <p>They let the user overlay and label the known channel structure for a
 * chosen technology (Wi-Fi, Bluetooth / BLE) so energy in the chart and
 * waterfall is easy to attribute. This is a deterministic "where does each
 * technology live" guide built from published channel plans - not an attempt
 * to classify arbitrary signals from their spectral shape.
 */
public final class SignalTypePresets {

    private SignalTypePresets() {
    }

    private static final String WIFI_COLOR = "#3A7BD5";      // blue
    private static final String BLE_CHANNEL_COLOR = "#2ECC71"; // bright green
    private static final String BLE_BAND_COLOR = "#145A32";    // muted green backdrop

    /**
     * Wi-Fi 2.4 GHz: the three globally non-overlapping channels 1 / 6 / 11,
     * each 20 MHz wide, centred at 2412 / 2437 / 2462 MHz.
     */
    public static FrequencyAllocationTable wifi24() {
        ArrayList<FrequencyBand> bands = new ArrayList<>();
        addChannel(bands, "Wi-Fi ch 1", 2412, 20, WIFI_COLOR);
        addChannel(bands, "Wi-Fi ch 6", 2437, 20, WIFI_COLOR);
        addChannel(bands, "Wi-Fi ch 11", 2462, 20, WIFI_COLOR);
        return new FrequencyAllocationTable("Wi-Fi 2.4 GHz (ch 1/6/11)", bands);
    }

    /**
     * Bluetooth / BLE 2.4 GHz: the full operating band as a backdrop plus the
     * three BLE advertising channels (37 / 38 / 39 at 2402 / 2426 / 2480 MHz)
     * where devices are most reliably visible.
     */
    public static FrequencyAllocationTable bluetooth24() {
        ArrayList<FrequencyBand> bands = new ArrayList<>();
        bands.add(band(2402, 2480, "Bluetooth / BLE", BLE_BAND_COLOR));
        addChannel(bands, "BLE adv 37", 2402, 2, BLE_CHANNEL_COLOR);
        addChannel(bands, "BLE adv 38", 2426, 2, BLE_CHANNEL_COLOR);
        addChannel(bands, "BLE adv 39", 2480, 2, BLE_CHANNEL_COLOR);
        return new FrequencyAllocationTable("Bluetooth / BLE 2.4 GHz", bands);
    }

    private static void addChannel(ArrayList<FrequencyBand> bands, String name,
                                   int centerMHz, int widthMHz, String color) {
        double half = widthMHz / 2d;
        bands.add(band(centerMHz - half, centerMHz + half, name, color));
    }

    private static FrequencyBand band(double startMHz, double endMHz,
                                      String name, String color) {
        return new FrequencyBand(
                Math.round(startMHz * 1_000_000d),
                Math.round(endMHz * 1_000_000d),
                name, color);
    }
}
