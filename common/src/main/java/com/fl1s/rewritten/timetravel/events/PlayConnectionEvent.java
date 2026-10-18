package com.fl1s.rewritten.timetravel.events;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class PlayConnectionEvent {
    public static void onJoin(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        ServerPlayer player = handler.player;
        String playerName = player.getName().getString();

        PlayerData existingData = TimeTravel.timelineSceneManager.getRecordingPlayer(playerName);
        if (existingData != null) {
            existingData.setActive(true);
        } else {
            TimeTravel.timelineSceneManager.addPlayer(playerName, player.position());
            existingData = TimeTravel.timelineSceneManager.getRecordingPlayer(playerName);
        }
        final PlayerData playerData = existingData;

        if (TimeTravel.config != null && TimeTravel.config.firstStart) {
            TimeTravel.config.firstStart = false;
            TimeTravel.config.save();

            try {
                GameProfile profile = player.getGameProfile();
                if (server.getPlayerList().isOp(profile)) {
                    player.sendSystemMessage(Component.literal("Use '/timetravel start' to start the timeline (shared server time). '/timetravel help' lists all commands."));
                }
            } catch (Exception e) {
                TimeTravel.TIMELINE_LOGGER.error("Exception: " + e);
            }
        }

        TimeTravel.queueTask(() -> TimeTravel.onPlayerJoined(player, playerData));
    }

    public static void onDisconnect(ServerGamePacketListenerImpl handler) {
        ServerPlayer player = handler.player;
        String playerName = player.getName().getString();

        PlayerData playerData = TimeTravel.timelineSceneManager.getRecordingPlayer(playerName);
        TimeTravel.onPlayerLeft(playerData);

        if (playerData != null) {
            playerData.setActive(false);
        }
    }
}
