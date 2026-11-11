package com.fl1s.rewritten.fabric.events;

import com.fl1s.rewritten.timetravel.events.PlayerEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class PlayerFabricEvent {
    public static void dimensionChange(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        PlayerEvent.dimensionChange(player);
    }

    public static void respawn(ServerPlayer player) {
        PlayerEvent.respawn(player);
    }
}
