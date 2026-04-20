package jspectrumanalyzer.nativebridge;

/**
 * Thrown by {@link HackRFSweepNativeBridge#start} when the native sweep
 * lock is already held by another thread and could not be acquired
 * within the configured timeout.
 *
 * <p>This is the recovery signal for the case where {@code stopSweep()}
 * gave up on a wedged native call (swap-on-abandon): the orphan keeps
 * holding the lock, so a fresh sweep attempt would otherwise block
 * forever on {@code synchronized}. Throwing instead lets the caller
 * back off and try again, keeping the launcher thread responsive.
 */
public final class DeviceBusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DeviceBusyException(String message) {
        super(message);
    }
}
