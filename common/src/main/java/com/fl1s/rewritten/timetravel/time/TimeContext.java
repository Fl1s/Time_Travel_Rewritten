package com.fl1s.rewritten.timetravel.time;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.world.level.Level;

public final class TimeContext {

    private TimeContext() {
    }

    private static final int MAX_DEPTH = 32;
    private static final long[] STACK = new long[MAX_DEPTH];
    private static int depth = 0;

    public static long now() {
        if (depth > 0) return STACK[depth - 1];
        return TimeTravel.sharedTick;
    }

    public static boolean overridden() {
        return depth > 0;
    }

    public static long positionIn(Level level) {
        if (depth > 0) return STACK[depth - 1];
        TimeHeads heads = TimeTravel.heads();
        if (heads != null && level != null) {
            TimeHead head = heads.owning(level);
            if (head != null) return head.position();
        }
        return TimeTravel.sharedTick;
    }

    public static void runAt(long position, Runnable body) {
        int mark = depth;
        push(position);
        try {
            body.run();
        } finally {
            depth = mark;
        }
    }

    public static void push(long position) {
        if (depth >= MAX_DEPTH) {
            TimeTravel.TIMELINE_LOGGER.error("TimeContext stack overflow at position {} - ignoring push", position);
            return;
        }
        STACK[depth++] = position;
    }

    public static void pop() {
        if (depth > 0) depth--;
    }

    public static void reset() {
        depth = 0;
    }
}
