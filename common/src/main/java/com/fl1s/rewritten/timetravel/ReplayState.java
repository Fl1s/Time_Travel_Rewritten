package com.fl1s.rewritten.timetravel;

/**
 * Shared replay-state flag used by every trail journal.
 */
public final class ReplayState {
    private ReplayState() {}

    private static long frontierTick = -1;

    public static volatile boolean SUPPRESS = false;

    public static void observe(long tick) {
        if (tick > frontierTick) frontierTick = tick;
    }

    public static long frontier() {
        return frontierTick;
    }

    public static boolean isReplaying() {
        return frontierTick >= 0 && TimeTravel.timelineTick < frontierTick;
    }

    public static void reset() {
        frontierTick = -1;
        SUPPRESS = false;
    }
}
