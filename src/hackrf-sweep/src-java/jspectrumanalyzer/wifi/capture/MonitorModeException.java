package jspectrumanalyzer.wifi.capture;

/**
 * Thrown by {@link MonitorModeCapture#start} when the capture handle
 * could not be opened. Phase-2 code translates this into a single
 * "monitor mode unavailable" status banner instead of leaking backend
 * details to the UI.
 */
public class MonitorModeException extends Exception {
    public MonitorModeException(String message) {
        super(message);
    }

    public MonitorModeException(String message, Throwable cause) {
        super(message, cause);
    }
}
