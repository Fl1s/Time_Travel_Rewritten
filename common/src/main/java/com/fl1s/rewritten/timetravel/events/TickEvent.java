package com.fl1s.rewritten.timetravel.events;

import com.fl1s.rewritten.timetravel.TimeTravel;

public class TickEvent {
    public static void onEndServerTick() {
        TimeTravel.onServerTick();
    }
}
