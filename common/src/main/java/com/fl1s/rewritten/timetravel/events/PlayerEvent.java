package com.fl1s.rewritten.timetravel.events;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import net.minecraft.server.level.ServerPlayer;

public class PlayerEvent {
    public static void dimensionChange(ServerPlayer player) {
        if (!TimeTravel.isLooping) return;
        TimeTravel.handlePlayerDimensionChange(player);
    }

    public static void respawn(ServerPlayer player) {
        if (!TimeTravel.isLooping) return;
        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive()) return;
        if (pd.getCurrentVisit() == null) {
            TimeTravel.session().visits.beginVisit(player, pd);
        }
    }
}
