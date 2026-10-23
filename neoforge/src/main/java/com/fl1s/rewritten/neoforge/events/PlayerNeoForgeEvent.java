package com.fl1s.rewritten.neoforge.events;

import com.fl1s.rewritten.timetravel.events.PlayerEvent;
import net.minecraft.server.level.ServerPlayer;

public class PlayerNeoForgeEvent {
    public static void dimensionChange(ServerPlayer player) {
        PlayerEvent.dimensionChange(player);
    }
    public static void respawn(ServerPlayer player) {PlayerEvent.respawn(player);}
}
