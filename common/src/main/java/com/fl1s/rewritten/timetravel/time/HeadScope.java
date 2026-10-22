package com.fl1s.rewritten.timetravel.time;

import com.fl1s.rewritten.timetravel.TimeTravel;

public final class HeadScope {

    private HeadScope() {
    }

    public static final long NONE = Long.MIN_VALUE;

    private static final int MAX_DEPTH = 16;
    private static final long[] STACK = new long[MAX_DEPTH];
    private static int depth = 0;
    private static Thread owner;

    public static boolean active() {
        return depth > 0 && Thread.currentThread() == owner;
    }

    public static long current() {
        return active() ? STACK[depth - 1] : NONE;
    }

    public static void push(long head) {
        if (depth >= MAX_DEPTH) {
            TimeTravel.TIMELINE_LOGGER.error("HeadScope stack overflow at head {} - ignoring push", head);
            return;
        }
        owner = Thread.currentThread();
        STACK[depth++] = head;
    }

    public static void pop() {
        if (depth > 0) depth--;
    }

    public static void reset() {
        depth = 0;
    }
}
