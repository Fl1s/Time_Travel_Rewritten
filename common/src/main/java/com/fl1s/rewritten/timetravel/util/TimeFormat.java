package com.fl1s.rewritten.timetravel.util;

public final class TimeFormat {
    private TimeFormat() {}

    /** HH:MM:SS.d */
    public static String clock(long ticks) {
        long t = Math.max(0, ticks);
        long totalSeconds = t / 20;
        long tenths = (t % 20) / 2;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format("%02d:%02d:%02d.%d", h, m, s, tenths);
    }

    public static String format(long ticks, boolean asTicks) {
        return asTicks ? "T " + Math.max(0, ticks) : clock(ticks);
    }

    //'+' or '-' ratio
    public static String delta(long deltaTicks, boolean asTicks) {
        String sign = deltaTicks < 0 ? "-" : "+";
        long abs = Math.abs(deltaTicks);
        return sign + (asTicks ? abs + "t" : clock(abs));
    }
}
