package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.mixin.ChunkMapAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class HeadTracking {

    private HeadTracking() {
    }

    private static boolean dirty;

    public static void markDirty() {
        dirty = true;
    }

    public static void refreshIfDirty() {
        if (!dirty) return;
        dirty = false;
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) refresh(level);
    }

    public static void refresh(ServerLevel level) {
        if (level == null) return;
        List<ServerPlayer> viewers = level.players();
        if (viewers.isEmpty()) return;

        try {
            ChunkMapAccessor map = (ChunkMapAccessor) level.getChunkSource().chunkMap;
            for (Object tracked : new ArrayList<>(map.tt$entityMap().values())) {
                if (tracked instanceof HeadTracked entry) entry.tt$refreshTracking(viewers);
            }
        } catch (Exception e) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to refresh entity tracking for a head change", e);
        }
    }
}
