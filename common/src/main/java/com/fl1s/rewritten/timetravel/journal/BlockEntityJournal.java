package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.api.TimelineApi;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlockEntityJournal {
    private BlockEntityJournal() {}

    private static final int MAX_NBT_LENGTH = 65536;

    public static final TagKey<BlockEntityType<?>> IGNORED = TagKey.create(Registries.BLOCK_ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "ignored"));

    public static final class Entry {
        String dim;
        int x, y, z;
        String oldNbt;
        String newNbt;
        long tick;
        long seq;
        int visit;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_block_entities.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<Long, Entry> last = new HashMap<>();
    private static final Map<Long, Long> lastPollTick = new HashMap<>();
    private static boolean applying = false;

    public static boolean isApplying() { return applying; }

    public static boolean isTracked(BlockEntity be) {
        if (be == null) return false;
        if (BuiltInRegistries.BLOCK_ENTITY_TYPE.wrapAsHolder(be.getType()).is(IGNORED)) return false;
        if (TimelineApi.isBlockEntityExcluded(be)) return false;
        if (be instanceof JukeboxBlockEntity) return true;
        if (be instanceof Container) return false;
        if (be instanceof AbstractFurnaceBlockEntity) return false;
        if (be instanceof BrewingStandBlockEntity) return false;
        return true;
    }

    private static long keyOf(String dim, BlockPos pos) {
        return ((long) dim.hashCode() << 32) ^ pos.asLong();
    }

    public static void snapshotInitial() {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        Set<String> visitedDims = new HashSet<>();
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) {
            if (Clones.is(player)) continue;
            ServerLevel level = player.serverLevel();
            String dimId = level.dimension().location().toString();
            if (!visitedDims.add(dimId)) continue;

            ChunkPos center = player.chunkPosition();
            int radius = 5;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                    if (chunk == null) continue;
                    for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (!isTracked(be)) continue;
                        recordInitial(level, be, now);
                    }
                }
            }
        }
    }

    private static void recordInitial(ServerLevel level, BlockEntity be, long tick) {
        String dim = level.dimension().location().toString().intern();
        String nbt = be.saveWithoutMetadata(TimeTravel.server.registryAccess()).toString();
        if (nbt.length() > MAX_NBT_LENGTH) return;
        long key = keyOf(dim, be.getBlockPos());
        if (last.containsKey(key)) return;

        Entry e = new Entry();
        e.dim = dim;
        e.x = be.getBlockPos().getX();
        e.y = be.getBlockPos().getY();
        e.z = be.getBlockPos().getZ();
        e.oldNbt = nbt;
        e.newNbt = nbt;
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = -1;
        STORE.entries().add(e);
        last.put(key, e);
        STORE.markDirty();
    }

    public static void onBlockEntityInit(ServerLevel level, BlockEntity be) {
        if (applying || ReplayState.SUPPRESS) return;
        if (!TimeTravel.isLooping) return;
        if (!isTracked(be)) return;

        BlockPos pos = be.getBlockPos();
        if (!level.isLoaded(pos)) return;

        String dim = level.dimension().location().toString().intern();
        long key = keyOf(dim, pos);
        if (last.containsKey(key)) return;

        String nbt = be.saveWithoutMetadata(TimeTravel.server.registryAccess()).toString();
        if (nbt.length() > MAX_NBT_LENGTH) return;

        Entry e = new Entry();
        e.dim = dim;
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.oldNbt = nbt;
        e.newNbt = nbt;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = -1;
        STORE.entries().add(e);
        last.put(key, e);
        STORE.markDirty();
    }

    public static void onChanged(BlockEntity be) {
        if (applying || ReplayState.SUPPRESS) return;
        if (!TimeTravel.isLooping) return;
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        if (!isTracked(be)) return;
        if (TimeTravel.timelineSceneManager == null) return;

        BlockPos pos = be.getBlockPos();
        if (!level.isLoaded(pos)) return;

        String dim = level.dimension().location().toString().intern();
        long key = keyOf(dim, pos);
        long now = TimeTravel.timelineTick;

        Long prevTick = lastPollTick.get(key);
        if (prevTick != null && prevTick.longValue() == now) return;
        lastPollTick.put(key, now);

        Entry prev = last.get(key);

        PlayerData pd = null;
        if (prev != null) {
            pd = BlockJournal.nearestRecorder(level, pos);
            if (pd == null || pd.getCurrentVisit() == null) return;
        }

        String nbt = be.saveWithoutMetadata(TimeTravel.server.registryAccess()).toString();
        if (nbt.length() > MAX_NBT_LENGTH) return;

        if (prev == null) {
            Entry base = new Entry();
            base.dim = dim;
            base.x = pos.getX();
            base.y = pos.getY();
            base.z = pos.getZ();
            base.oldNbt = nbt;
            base.newNbt = nbt;
            base.tick = TimeTravel.timelineTick;
            base.seq = STORE.nextSeq();
            base.visit = -1;
            STORE.entries().add(base);
            last.put(key, base);
            STORE.markDirty();
            return;
        }

        if (prev.newNbt.equals(nbt)) return;

        Entry e = new Entry();
        e.dim = dim;
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.oldNbt = prev.newNbt;
        e.newNbt = nbt;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        STORE.entries().add(e);
        last.put(key, e);
        STORE.markDirty();
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        STORE.tick(e -> {
            if (e.visit != -1) apply(e, true);
        });
    }

    public static void onJump(long from, long to) {
        List<Entry> affected = new ArrayList<>();
        if (to < from) {
            for (Entry e : STORE.entries()) {
                if (e.visit != -1 && e.tick > to && e.tick <= from) affected.add(e);
            }
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
            for (Entry e : affected) apply(e, false);
        } else {
            for (Entry e : STORE.entries()) {
                if (e.visit != -1 && e.tick > from && e.tick <= to) affected.add(e);
            }
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
            for (Entry e : affected) apply(e, true);
        }
        rebuildLastIndex();
        STORE.rebuildUpcoming();
    }

    private static void apply(Entry e, boolean toNew) {
        applying = true;
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) return;
            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            if (!level.isLoaded(pos)) return;
            BlockEntity be = level.getBlockEntity(pos);
            if (!isTracked(be)) return;

            String nbt = toNew ? e.newNbt : e.oldNbt;
            CompoundTag tag = TagParser.parseTag(nbt);
            be.loadWithComponents(tag, TimeTravel.server.registryAccess());
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);

            if (be instanceof JukeboxBlockEntity) BlockJournal.markJukebox(level, pos);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to apply BE journal entry at {} {} {}", e.x, e.y, e.z, ex);
        } finally {
            applying = false;
        }
    }

    private static void rebuildLastIndex() {
        last.clear();
        for (Entry e : STORE.entries()) {
            long key = keyOf(e.dim, new BlockPos(e.x, e.y, e.z));
            Entry prev = last.get(key);
            if (prev == null
                    || e.tick > prev.tick
                    || (e.tick == prev.tick && e.seq >= prev.seq)) {
                last.put(key, e);
            }
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.entries().removeIf(e -> visitIds.contains(e.visit));
        STORE.upcoming().removeIf(e -> visitIds.contains(e.visit));
        STORE.markDirty();
        rebuildLastIndex();
    }

    public static void rebuildUpcoming() { STORE.rebuildUpcoming(); }

    public static void load() {
        last.clear();
        lastPollTick.clear();
        STORE.load();
        rebuildLastIndex();
    }

    public static void save() { STORE.save(); }
    public static void saveIfDirty() { STORE.saveIfDirty(); }

    public static void clear() {
        last.clear();
        lastPollTick.clear();
        STORE.clear();
    }
}
