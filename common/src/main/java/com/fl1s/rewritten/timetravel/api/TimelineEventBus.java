package com.fl1s.rewritten.timetravel.api;

import com.fl1s.rewritten.timetravel.TimeTravel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class TimelineEventBus {
    private TimelineEventBus() {
    }

    private static final List<TimelineListener> LISTENERS = new CopyOnWriteArrayList<>();

    static void add(TimelineListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    static void remove(TimelineListener listener) {
        LISTENERS.remove(listener);
    }

    public static void fireStart() {
        dispatch("onStart", l -> l.onStart());
    }

    public static void fireStop() {
        dispatch("onStop", l -> l.onStop());
    }

    public static void fireBeforeJump(long from, long to) {
        dispatch("onBeforeJump", l -> l.onBeforeJump(from, to));
    }

    public static void fireAfterJump(long from, long to) {
        dispatch("onAfterJump", l -> l.onAfterJump(from, to));
    }

    private static void dispatch(String name, Consumer<TimelineListener> call) {
        for (TimelineListener l : LISTENERS) {
            try {
                call.accept(l);
            } catch (Throwable t) {
                TimeTravel.TIMELINE_LOGGER.error("Timeline listener {} failed in {}", l.getClass().getName(), name, t);
            }
        }
    }
}
