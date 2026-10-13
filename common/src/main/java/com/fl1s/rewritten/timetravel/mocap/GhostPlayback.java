package com.fl1s.rewritten.timetravel.mocap;

/**
 * Opaque handle to a running Mocap playback.
 */
public interface GhostPlayback {
    boolean isFinished();
    void stop();
}