package com.fl1s.rewritten.timetravel.api;

public interface TimelineListener {
    default void onStart() {
    }

    default void onStop() {
    }

    default void onBeforeJump(long from, long to) {
    }

    default void onAfterJump(long from, long to) {
    }
}
