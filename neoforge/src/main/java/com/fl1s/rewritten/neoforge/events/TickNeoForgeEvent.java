package com.fl1s.rewritten.neoforge.events;

import com.fl1s.rewritten.timetravel.events.TickEvent;
import net.minecraft.server.MinecraftServer;

public class TickNeoForgeEvent {
    public static void onEndServerTick(MinecraftServer server) {
        TickEvent.onEndServerTick();
    }
}
