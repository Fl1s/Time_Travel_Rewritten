package com.fl1s.rewritten.fabric.events;

import com.fl1s.rewritten.timetravel.events.LifecycleEvent;
import net.minecraft.server.MinecraftServer;

public class LifecycleFabricEvent {
    public static void onServerStart(MinecraftServer server) {
        LifecycleEvent.onServerStart(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        LifecycleEvent.onServerStopping();
    }
}
