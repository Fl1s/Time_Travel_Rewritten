package com.fl1s.rewritten.fabric.events;

import com.fl1s.rewritten.timetravel.events.TickEvent;
import net.minecraft.server.MinecraftServer;

public class TickFabricEvent {
    public static void onEndServerTick(MinecraftServer server) {
        TickEvent.onEndServerTick();
    }
}
