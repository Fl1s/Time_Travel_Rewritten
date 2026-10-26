package com.fl1s.rewritten.timetravel.time;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.journal.HeadWorldView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class HeadScopeSupport {

    private HeadScopeSupport() {
    }

    public static long headFor(Entity entity) {
        if (!TimeTravel.individualTime || !TimeTravel.isLooping) return 0;
        MinecraftServer server = TimeTravel.server;
        if (server == null || !server.isSameThread()) return 0;
        if (!(entity instanceof ServerPlayer player) || Clones.is(player)) return 0;
        if (!HeadWorldView.hasOverlay()) return 0;
        return TimeTravel.headOffsetOf(player);
    }
}
