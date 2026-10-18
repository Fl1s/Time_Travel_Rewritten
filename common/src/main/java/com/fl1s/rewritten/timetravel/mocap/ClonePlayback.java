package com.fl1s.rewritten.timetravel.mocap;

/**
 * Opaque handle to a running Mocap playback.
 * <p>
 * Keeps {@code net.mt1006.mocap.*} types out of {@code TimeTravel} so that
 * callers can manage playback without importing Mocap classes directly.
 */
public interface ClonePlayback {
    boolean isFinished();
    void stop();
    void tick(int count);
}