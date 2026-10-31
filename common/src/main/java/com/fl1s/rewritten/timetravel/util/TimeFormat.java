package com.fl1s.rewritten.timetravel.util;

public final class TimeFormat {
    private TimeFormat() {}

    public static String clock(long ticks) {
        long t = Math.max(0, ticks);
        long totalSeconds = t / 20;
        long tenths = (t % 20) / 2;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder(11);
        append2(sb, h);
        sb.append(':');
        append2(sb, m);
        sb.append(':');
        append2(sb, s);
        sb.append('.');
        sb.append((char) ('0' + tenths));
        return sb.toString();
    }

    private static void append2(StringBuilder sb, long v) {
        if (v < 10) sb.append('0');
        sb.append(v);
    }

    public static String format(long ticks, boolean asTicks) {
        return asTicks ? "T " + Math.max(0, ticks) : clock(ticks);
    }

    public static String delta(long deltaTicks, boolean asTicks) {
        String sign = deltaTicks < 0 ? "-" : "+";
        long abs = Math.abs(deltaTicks);
        return sign + (asTicks ? abs + "t" : clock(abs));
    }
}
