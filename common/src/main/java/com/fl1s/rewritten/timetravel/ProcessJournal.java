package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.util.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

public final class ProcessJournal {
    private ProcessJournal() {}

    private static final int SIM_BUDGET_PER_TICK = 50_000;
    private static final int MAX_SNAPS = 6000;

    private record Key(String dim, long pos) {}

    private static final class Snap {
        Key key;
        long tick;
        long seq;
        String nbt;
        boolean event;
    }

    private static final Map<Key, List<Snap>> snaps = new HashMap<>();
    private static final Set<Key> tracked = new HashSet<>();
    private static final PriorityQueue<Snap> upcomingEvents = new PriorityQueue<>(
            Comparator.comparingLong((Snap s) -> s.tick).thenComparingLong(s -> s.seq));
    private static long nextSeq = 0;

    private static final Map<Key, Snap> origins = new HashMap<>();
    private static final Map<Key, Long> pendingSim = new HashMap<>();
    private static volatile boolean fastSimulating = false;

    public static boolean isFastSimulating() {
        return fastSimulating;
    }

    public static boolean isProcess(BlockEntity be) {
        return be instanceof AbstractFurnaceBlockEntity || be instanceof BrewingStandBlockEntity;
    }

    private static Key key(ServerLevel level, BlockPos pos) {
        return new Key(level.dimension().location().toString(), pos.asLong());
    }

    private static ServerLevel levelOf(Key k) {
        return TimeTravel.server.getLevel(Levels.key(k.dim()));
    }

    private static BlockEntity beAt(ServerLevel level, Key k) {
        BlockPos pos = BlockPos.of(k.pos());
        if (!level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos);
    }

    private static String snapshot(BlockEntity be) {
        return be.saveWithFullMetadata(TimeTravel.server.registryAccess()).toString();
    }

    private static void tickBlockEntity(ServerLevel level, BlockPos pos, BlockState state, BlockEntity be) {
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            AbstractFurnaceBlockEntity.serverTick(level, pos, state, furnace);
        } else if (be instanceof BrewingStandBlockEntity stand) {
            BrewingStandBlockEntity.serverTick(level, pos, state, stand);
        }
    }

    public static void trackIfProcess(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !isProcess(be)) return;
        Key k = key(level, pos);
        tracked.add(k);
        if (!origins.containsKey(k)) {
            Snap origin = new Snap();
            origin.key = k;
            origin.tick = 0;
            origin.seq = -1;
            origin.nbt = snapshot(be);
            origin.event = false;
            origins.put(k, origin);
        }
    }

    public static void recordEvent(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !isProcess(be)) return;

        Key k = key(level, pos);
        tracked.add(k);
        addSnap(k, be, true);
    }

    private static void addSnap(Key k, BlockEntity be, boolean event) {
        String nbt = snapshot(be);
        List<Snap> list = snaps.computeIfAbsent(k, x -> new ArrayList<>());

        if (!event && !list.isEmpty() && list.get(list.size() - 1).nbt.equals(nbt)) return;

        Snap s = new Snap();
        s.key = k;
        s.tick = TimeTravel.timelineTick;
        s.seq = nextSeq++;
        s.nbt = nbt;
        s.event = event;
        list.add(s);
        if (list.size() > MAX_SNAPS) list.subList(0, list.size() - MAX_SNAPS).clear();
    }

    private static void snapshotAll() {
        for (Key k : new ArrayList<>(tracked)) {
            ServerLevel level = levelOf(k);
            if (level == null) continue;
            BlockPos pos = BlockPos.of(k.pos());
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !isProcess(be)) continue;

            BlockState state = level.getBlockState(pos);
            if (be instanceof AbstractFurnaceBlockEntity
                    && state.hasProperty(AbstractFurnaceBlock.LIT)
                    && !state.getValue(AbstractFurnaceBlock.LIT)) {
                List<Snap> list = snaps.get(k);
                if (list != null && !list.isEmpty()) {
                    continue;
                }
            }

            addSnap(k, be, false);
        }
    }

    public static void pruneTracked() {
        tracked.removeIf(k -> {
            ServerLevel level = levelOf(k);
            if (level == null) return true;
            BlockPos pos = BlockPos.of(k.pos());
            if (!level.isLoaded(pos)) return false;
            BlockEntity be = level.getBlockEntity(pos);
            return be == null || !isProcess(be);
        });
    }

    public static void snapshotOrigins(Collection<ServerPlayer> players) {
        final int radius = 8;
        for (ServerPlayer player : players) {
            ServerLevel level = player.serverLevel();
            ChunkPos center = player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                    if (chunk == null) continue;
                    for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                        trackIfProcess(level, pos);
                    }
                }
            }
        }
    }

    public static void tick() {
        while (!upcomingEvents.isEmpty() && upcomingEvents.peek().tick <= TimeTravel.timelineTick) {
            Snap s = upcomingEvents.poll();
            ServerLevel level = levelOf(s.key);
            if (level == null) continue;
            BlockEntity be = beAt(level, s.key);
            if (be == null || !isProcess(be)) continue;
            restore(be, s.nbt);
            BlockPos pos = BlockPos.of(s.key.pos());
            tickBlockEntity(level, pos, level.getBlockState(pos), be);
        }

        if (!pendingSim.isEmpty()) {
            int budget = SIM_BUDGET_PER_TICK;
            Set<Key> touched = new HashSet<>();
            fastSimulating = true;
            try {
                Iterator<Map.Entry<Key, Long>> it = pendingSim.entrySet().iterator();
                while (it.hasNext() && budget > 0) {
                    Map.Entry<Key, Long> en = it.next();
                    ServerLevel level = levelOf(en.getKey());
                    if (level == null) { it.remove(); continue; }
                    BlockEntity be = beAt(level, en.getKey());
                    if (be == null || !isProcess(be)) { it.remove(); continue; }

                    long remaining = en.getValue();
                    int step = (int) Math.min(remaining, budget);
                    BlockPos pos = BlockPos.of(en.getKey().pos());
                    for (int i = 0; i < step; i++) {
                        BlockEntity current = level.getBlockEntity(pos);
                        if (current == null || !isProcess(current)) break;
                        tickBlockEntity(level, pos, level.getBlockState(pos), current);
                    }
                    touched.add(en.getKey());
                    remaining -= step;
                    budget -= step;
                    if (remaining <= 0) it.remove();
                    else en.setValue(remaining);
                }
            } finally {
                fastSimulating = false;
            }

            for (Key k : touched) {
                ServerLevel level = levelOf(k);
                if (level == null) continue;
                BlockPos pos = BlockPos.of(k.pos());
                if (!level.isLoaded(pos)) continue;
                BlockEntity touchedBe = level.getBlockEntity(pos);
                if (touchedBe != null) syncBlockState(touchedBe);
                BlockState state = level.getBlockState(pos);
                level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
            }
        }

        if (!tracked.isEmpty() && pendingSim.isEmpty() && TimeTravel.timelineTick % 20 == 0) {
            snapshotAll();
        }
        if (!tracked.isEmpty() && TimeTravel.timelineTick % 1200 == 0) {
            pruneTracked();
        }
    }

    public static void beforeJump() {
        snapshotAll();
    }

    public static void afterJump(long from, long to) {
        for (Key k : new ArrayList<>(tracked)) {
            ServerLevel level = levelOf(k);
            if (level == null) continue;
            BlockEntity be = beAt(level, k);
            if (be == null || !isProcess(be)) continue;

            if (to > from) {
                Snap best = bestEvent(k, from, to);
                if (best != null) {
                    restore(be, best.nbt);
                    long delta = to - best.tick;
                    if (delta > 0) pendingSim.put(k, delta);
                    else pendingSim.remove(k);
                } else {
                    pendingSim.merge(k, to - from, Long::sum);
                }
            } else {
                pendingSim.remove(k);
                Snap best = bestSnap(k, to);
                if (best == null) best = origins.get(k);
                if (best != null) {
                    restore(be, best.nbt);
                    long delta = to - best.tick;
                    if (delta > 0) {
                        pendingSim.put(k, delta);
                    }
                }
            }
        }
        rebuildUpcoming();
    }

    private static Snap bestSnap(Key k, long tick) {
        List<Snap> list = snaps.get(k);
        if (list == null) return null;
        Snap best = null;
        for (Snap s : list) {
            if (s.tick > tick) continue;
            if (best == null || s.tick > best.tick || (s.tick == best.tick && s.seq > best.seq)) best = s;
        }
        return best;
    }

    private static Snap bestEvent(Key k, long from, long to) {
        List<Snap> list = snaps.get(k);
        if (list == null) return null;
        Snap best = null;
        for (Snap s : list) {
            if (!s.event || s.tick <= from || s.tick > to) continue;
            if (best == null || s.tick > best.tick || (s.tick == best.tick && s.seq > best.seq)) best = s;
        }
        return best;
    }

    private static void restore(BlockEntity be, String nbt) {
        try {
            CompoundTag tag = TagParser.parseTag(nbt);
            be.loadWithComponents(tag, TimeTravel.server.registryAccess());
            be.setChanged();
            syncBlockState(be);
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("Failed to restore block entity state", e);
        }
    }

    private static void syncBlockState(BlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        if (!(be instanceof AbstractFurnaceBlockEntity)) return;

        BlockPos pos = be.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(AbstractFurnaceBlock.LIT)) return;

        CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
        boolean lit = tag.getShort("BurnTime") > 0;
        if (state.getValue(AbstractFurnaceBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(AbstractFurnaceBlock.LIT, lit), Block.UPDATE_ALL);
        }
    }

    public static void rebuildUpcoming() {
        upcomingEvents.clear();
        for (List<Snap> list : snaps.values()) {
            for (Snap s : list) {
                if (s.event && s.tick > TimeTravel.timelineTick) upcomingEvents.add(s);
            }
        }
    }

    public static void clear() {
        snaps.clear();
        tracked.clear();
        origins.clear();
        upcomingEvents.clear();
        pendingSim.clear();
        nextSeq = 0;
    }

    public static void load() {}

    public static void save() {}

    public static void saveIfDirty() {}

    public static void onJump(long from, long to) {
        afterJump(from, to);
    }

    public static void eraseVisits(Set<Integer> visitIds) {}
}
