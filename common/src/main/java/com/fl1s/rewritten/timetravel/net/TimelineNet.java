package com.fl1s.rewritten.timetravel.net;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.util.TimeFormat;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class TimelineNet {
    private TimelineNet() {}
    public static BiConsumer<ServerPlayer, CustomPacketPayload> toPlayerSender;
    public static Consumer<CustomPacketPayload> toServerSender;

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (toPlayerSender == null) return;
        try {
            toPlayerSender.accept(player, payload);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.debug("Could not send {} to {}: {}", payload.type().id(), player.getName().getString(), t.toString());
        }
    }

    public static void sendToServer(CustomPacketPayload payload) {
        if (toServerSender != null) toServerSender.accept(payload);
    }

    public static void handleToggleRequest(ServerPlayer player) {
        if (TimeTravel.config == null) return;

        if (TimeTravel.jumpRequiresOp && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("message.timetravel_rewritten.no_permission"), true);
            return;
        }

        if (TimeTravel.isLooping) {
            TimeTravel.stopLoop();
            player.displayClientMessage(Component.translatable("hud.timetravel_rewritten.panel_off"), true);
        } else {
            if (!MocapAdapter.isReady() || TimeTravel.serverLevel == null) {
                player.displayClientMessage(Component.literal("Motion Capture is not ready yet"), true);
                return;
            }
            TimeTravel.startLoop();
            player.displayClientMessage(Component.translatable("hud.timetravel_rewritten.panel_on"), true);
        }
    }

    public static void handleJumpRequest(ServerPlayer player, long targetTick) {
        if (TimeTravel.config == null || TimeTravel.loopSceneManager == null) return;

        if (TimeTravel.jumpRequiresOp && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("message.timetravel_rewritten.no_permission"), true);
            return;
        }

        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive()) return;

        String error = TimeTravel.jumpTo(targetTick, player.getName().getString());
        if (error != null) {
            player.displayClientMessage(Component.literal(error), true);
        } else {
            player.displayClientMessage(Component.literal("-> " + TimeFormat.format(TimeTravel.timelineTick, TimeTravel.displayTimeInTicks)), true);
        }
    }
}
