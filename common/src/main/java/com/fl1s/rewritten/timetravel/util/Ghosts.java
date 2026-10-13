package com.fl1s.rewritten.timetravel.util;

/**
 * Fast, allocation-free detection of mocap FakePlayers.
 */
public final class Ghosts {
    private Ghosts() {
    }

    private static final ClassValue<Boolean> FAKE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().contains("FakePlayer");
        }
    };

    public static boolean is(Object o) {
        return o != null && FAKE.get(o.getClass());
    }
}
