package com.fl1s.rewritten.timetravel.util;

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

    private static final String[] MESSAGES = {
            "message.timetravel_rewritten.doomed.1",
            "message.timetravel_rewritten.doomed.2",
            "message.timetravel_rewritten.doomed.3",
            "message.timetravel_rewritten.doomed.4",
            "message.timetravel_rewritten.doomed.5",
            "message.timetravel_rewritten.doomed.6",
            "message.timetravel_rewritten.doomed.7",
            "message.timetravel_rewritten.doomed.8",
    };

    private static final long COOLDOWN_TICKS = 40;
    private static final Map<UUID, Long> lastShown = new HashMap<>();

    public static void tryShow(ServerPlayer player, ServerLevel level) {
        long now = level.getGameTime();
        Long last = lastShown.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) return;
        lastShown.put(player.getUUID(), now);

        String key = MESSAGES[level.getRandom().nextInt(MESSAGES.length)];
        player.displayClientMessage(Component.translatable(key), true);
    }

    public static void clear() {
        lastShown.clear();
    }
}
