package com.fl1s.rewritten.timetravel.events;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.server.level.ServerPlayer;

public class PlayerEvent {
    public static void dimensionChange(ServerPlayer player) {
        if (!TimeTravel.isLooping) return;
        TimeTravel.handlePlayerDimensionChange(player);
    }
}
