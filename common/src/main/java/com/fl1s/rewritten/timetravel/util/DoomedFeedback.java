package com.fl1s.rewritten.timetravel.util;

import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Random flavour text shown to a real player who tries to hit a doomed mob.
 * Purely cosmetic; has absoolute no effect on gameplay (c:u my beloved, hehe).
 */
public final class DoomedFeedback {
    private DoomedFeedback() {}

    private static final String[] DOOMED_MESSAGES = {
            "message.timetravel_rewritten.doomed.1",
            "message.timetravel_rewritten.doomed.2",
            "message.timetravel_rewritten.doomed.3",
            "message.timetravel_rewritten.doomed.4",
            "message.timetravel_rewritten.doomed.5",
            "message.timetravel_rewritten.doomed.6",
            "message.timetravel_rewritten.doomed.7",
            "message.timetravel_rewritten.doomed.8",
    };

    private static final String[] REPLAY_MESSAGES = {
            "message.timetravel_rewritten.replay.1",
            "message.timetravel_rewritten.replay.2",
            "message.timetravel_rewritten.replay.3",
            "message.timetravel_rewritten.replay.4",
            "message.timetravel_rewritten.replay.5",
            "message.timetravel_rewritten.replay.6",
    };

    private static final String[] VEHICLE_MESSAGES = {
            "message.timetravel_rewritten.vehicle.1",
            "message.timetravel_rewritten.vehicle.2",
            "message.timetravel_rewritten.vehicle.3",
            "message.timetravel_rewritten.vehicle.4",
    };

    private static final String[] BLOCK_MESSAGES = {
            "message.timetravel_rewritten.block.1",
            "message.timetravel_rewritten.block.2",
            "message.timetravel_rewritten.block.3",
            "message.timetravel_rewritten.block.4",
            "message.timetravel_rewritten.block.5",
            "message.timetravel_rewritten.block.6",
    };

    private static final long COOLDOWN_TICKS = 40;
    private static final Map<UUID, Long> lastShown = new HashMap<>();

    public static void tryShow(ServerPlayer player, ServerLevel level, boolean doomed) {
        show(player, level, doomed ? DOOMED_MESSAGES : REPLAY_MESSAGES);
    }

    public static void tryShowVehicle(ServerPlayer player, ServerLevel level) {
        show(player, level, VEHICLE_MESSAGES);
    }

    public static void tryShowBlock(ServerPlayer player, ServerLevel level) {
        show(player, level, BLOCK_MESSAGES);
    }

    private static void show(ServerPlayer player, ServerLevel level, String[] pool) {
        if (Clones.is(player)) return;
        long now = level.getGameTime();
        Long last = lastShown.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) return;
        lastShown.put(player.getUUID(), now);

        String key = pool[level.getRandom().nextInt(pool.length)];
        player.displayClientMessage(Component.translatable(key), true);
    }

    public static void clear() {
        lastShown.clear();
    }
}
