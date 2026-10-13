package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.util.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class ProcessJournal {
    private ProcessJournal() {}

    private static final int MAX_SIM_TICKS = 24000;
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
        if (be != null && isProcess(be)) tracked.add(key(level, pos));
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
            BlockEntity be = beAt(level, k);
            if (be == null || !isProcess(be)) continue;
            addSnap(k, be, false);
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
            simulate(level, s.key, 0);
        }
        if (!tracked.isEmpty() && TimeTravel.timelineTick % 20 == 0) snapshotAll();
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

            Snap best = bestSnap(k, to);
            if (best != null) {
                restore(be, best.nbt);
                simulate(level, k, to - best.tick);
            } else if (to > from) {
                simulate(level, k, to - from);
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

    private static void simulate(ServerLevel level, Key k, long ticks) {
        int steps = (int) Math.max(1, Math.min(ticks, MAX_SIM_TICKS));
        BlockPos pos = BlockPos.of(k.pos());
        for (int i = 0; i < steps; i++) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !isProcess(be)) return;
            tickBlockEntity(level, pos, level.getBlockState(pos), be);
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
        upcomingEvents.clear();
        nextSeq = 0;
    }
}
