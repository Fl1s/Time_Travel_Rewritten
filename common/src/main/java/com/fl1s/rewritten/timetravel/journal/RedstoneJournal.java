package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Journal for {@code Level.scheduleTick} events.
 */
public final class RedstoneJournal {
    private RedstoneJournal() {}

    public static final class Entry {
        public String dim;
        public int x, y, z;
        public String block;
        public int delay;
        public long tick;
        public long seq;
        public int visit;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_redstone.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<Long, Entry> pendingByKey = new HashMap<>();
    private static boolean restoring = false;
    private static ServerLevel currentLevel;

    public static ServerLevel currentLevel() {
        return currentLevel;
    }

    public static void setCurrentLevel(ServerLevel level) {
        currentLevel = level;
    }

    public static void onSchedule(Level level, BlockPos pos, Block block, int delay) {
        if (restoring) return;
        if (!TimeTravel.isLooping) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (TimeTravel.loopSceneManager == null) return;

        PlayerData pd = BlockJournal.nearestRecorder(serverLevel, pos);
        if (pd == null || pd.getCurrentVisit() == null) return;

        long now = TimeTravel.timelineTick;
        long key = ((long) level.dimension().location().hashCode() << 32) ^ pos.asLong();

        Entry existing = pendingByKey.get(key);
        if (existing != null && existing.tick + existing.delay > now) return;

        Entry e = new Entry();
        e.dim = level.dimension().location().toString();
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.block = BuiltInRegistries.BLOCK.getKey(block).toString();
        e.delay = delay;
        e.tick = now;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        STORE.entries().add(e);
        pendingByKey.put(key, e);
        STORE.markDirty();
    }

    public static void tick() {}

    public static void onJump(long from, long to) {
        pendingByKey.clear();
        if (to < from) restorePending();
    }

    private static void restorePending() {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        restoring = true;
        try {
            for (Entry e : STORE.entries()) {
                long due = e.tick + e.delay;
                if (due <= now) continue;
                ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
                if (level == null) continue;
                Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(e.block));
                if (block == null) continue;
                BlockPos pos = new BlockPos(e.x, e.y, e.z);
                if (!level.isLoaded(pos)) continue;
                int delay = (int) Math.min(Integer.MAX_VALUE, due - now);
                level.scheduleTick(pos, block, delay);
                pendingByKey.put(
                        ((long) e.dim.hashCode() << 32) ^ pos.asLong(),
                        e);
            }
        } finally {
            restoring = false;
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        pendingByKey.clear();
    }

    public static void rebuildUpcoming() {}

    public static void load() {
        pendingByKey.clear();
        STORE.load();
    }

    public static void save() { STORE.save(); }
    public static void saveIfDirty() { STORE.saveIfDirty(); }

    public static void clear() {
        pendingByKey.clear();
        STORE.clear();
    }
}
