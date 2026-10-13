package com.fl1s.rewritten.timetravel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class BlockJournal {
    private BlockJournal() {}

    private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final double TRACK_RANGE_SQ = 64.0 * 64.0;
    private static final long DEDUP_TICKS = 40;
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<Entry>>() {}.getType();

    public static final class Entry {
        String dim;
        int x, y, z;
        String oldState;
        String newState;
        long tick;
        long seq;
        int visit = -1;
        boolean root;
        String oldItems;
        String newItems;
        String oldData;
        String newData;
        transient BlockState oldParsed;
        transient BlockState newParsed;
    }

    private record Watch(ServerLevel level, List<BlockPos> positions) {}

    private record ContainerWatch(ServerPlayer player, ServerLevel level, int visit, Map<BlockPos, List<ItemStack>> last, Map<BlockPos, String> data) {}

    private static final List<ContainerWatch> containerWatches = new ArrayList<>();
    private static final List<Entry> entries = new ArrayList<>();
    private static final PriorityQueue<Entry> upcoming = new PriorityQueue<>(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static final List<Watch> watches = new ArrayList<>();
    private static final Map<ServerLevel, Set<BlockPos>> touched = new HashMap<>();
    private static final Map<Long, List<Entry>> byPos = new HashMap<>();
    private static long nextSeq = 0;
    private static boolean dirty = false;
    private static boolean applying = false;
    private static boolean replayingLive = false;
    private static int actionDepth = 0;

    public static boolean isApplying() {
        return applying;
    }

    public static boolean isReplayingLive() {
        return replayingLive;
    }

    public static void enterAction() {
        actionDepth++;
    }

    public static void exitAction() {
        if (actionDepth > 0) actionDepth--;
    }

    public static void watch(ServerPlayer player, BlockPos center) {
        if (!TimeTravel.isLooping || TimeTravel.loopSceneManager == null) return;
        if (player.getClass().getName().contains("FakePlayer")) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive()) return;

        ServerLevel level = player.serverLevel();
        BlockPos c = center.immutable();
        List<BlockPos> positions = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) positions.add(c.offset(dx, dy, dz));
            }
        }
        watches.add(new Watch(level, positions));
        int visitId = pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
        watchContainers(player, level, c, visitId);
    }

    public static void watchLookTarget(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 1.0f, true);
        if (hit instanceof BlockHitResult blockHit) {
            watch(player, blockHit.getBlockPos());
        }
    }

    public static void flushWatches() {
        actionDepth = 0;
        pollContainers();
        if (watches.isEmpty()) return;
        List<Watch> copy = new ArrayList<>(watches);
        watches.clear();
        for (Watch w : copy) {
            for (BlockPos p : w.positions()) ProcessJournal.trackIfProcess(w.level(), p);
        }
    }

    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (applying || !TimeTravel.isLooping || TimeTravel.loopSceneManager == null) return;
        if (old.is(Blocks.MOVING_PISTON)) old = Blocks.AIR.defaultBlockState();
        if (now.is(Blocks.MOVING_PISTON)) now = Blocks.AIR.defaultBlockState();
        if (old.equals(now) || !shouldTrack(old, now)) return;
        PlayerData pd = nearestRecorder(level, pos);
        if (pd == null) return;

        String dim = level.dimension().location().toString();
        String oldS = BlockStateParser.serialize(old);
        String newS = BlockStateParser.serialize(now);
        int visit = pd.getCurrentVisit().id;

        List<Entry> same = byPos.get(pos.asLong());
        if (same != null) {
            for (Entry e : same) {
                if (e.visit != visit && e.dim.equals(dim) && Math.abs(e.tick - TimeTravel.timelineTick) <= DEDUP_TICKS
                        && oldS.equals(e.oldState) && newS.equals(e.newState)) return;
            }
        }

        Entry entry = new Entry();
        entry.dim = dim;
        entry.x = pos.getX();
        entry.y = pos.getY();
        entry.z = pos.getZ();
        entry.oldState = oldS;
        entry.newState = newS;
        entry.oldParsed = old;
        entry.newParsed = now;
        entry.tick = TimeTravel.timelineTick;
        entry.seq = nextSeq++;
        entry.visit = visit;
        entry.root = actionDepth > 0 && !(old.getBlock() instanceof RedStoneWireBlock && now.getBlock() instanceof RedStoneWireBlock);
        entries.add(entry);
        index(entry);
        dirty = true;
    }

    private static boolean shouldTrack(BlockState old, BlockState now) {
        Block a = old.getBlock();
        Block b = now.getBlock();
        if (a == b && (a instanceof AbstractFurnaceBlock || a instanceof BrewingStandBlock)) return false;
        if (a instanceof LiquidBlock && (b instanceof LiquidBlock || now.isAir())) return false;
        if (b instanceof LiquidBlock && old.isAir()) return false;
        if (a instanceof FireBlock && (b instanceof FireBlock || now.isAir())) return false;
        if (b instanceof FireBlock && old.isAir()) return false;
        if (a instanceof LeavesBlock && (a == b || now.isAir())) return false;
        return true;
    }

    static PlayerData nearestRecorder(ServerLevel level, BlockPos pos) {
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        for (ServerPlayer p : level.players()) {
            if (p.getClass().getName().contains("FakePlayer")) continue;
            if (p.distanceToSqr(cx, cy, cz) > TRACK_RANGE_SQ) continue;
            PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(p.getName().getString());
            if (pd != null && pd.getActive() && pd.getCurrentVisit() != null) return pd;
        }
        return null;
    }

    private static void index(Entry e) {
        byPos.computeIfAbsent(BlockPos.asLong(e.x, e.y, e.z), k -> new ArrayList<>()).add(e);
    }

    private static void rebuildIndex() {
        byPos.clear();
        for (Entry e : entries) index(e);
    }

    private static ServerLevel levelOf(String dim) {
        return TimeTravel.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim)));
    }

    public static void tick() {
        while (!upcoming.isEmpty() && upcoming.peek().tick <= TimeTravel.timelineTick) {
            Entry e = upcoming.poll();
            if (e.root) applyLive(e);
        }
    }

    private static void applyLive(Entry e) {
        if (e.oldItems != null) {
            applyContainer(e, true);
            return;
        }
        if (e.oldData != null) {
            applyData(e, true);
            return;
        }
        try {
            ServerLevel level = levelOf(e.dim);
            BlockState state = newState(e);
            if (level == null || state == null) return;
            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            if (level.getBlockState(pos).equals(state)) return;

            replayingLive = true;
            try {
                level.setBlock(pos, state, Block.UPDATE_ALL);
            } finally {
                replayingLive = false;
            }
            if (state.getBlock() instanceof ButtonBlock && state.getValue(ButtonBlock.POWERED)) {
                level.scheduleTick(pos, state.getBlock(), state.is(BlockTags.WOODEN_BUTTONS) ? 30 : 20);
            }
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to replay block journal entry at {} {} {}", e.x, e.y, e.z, ex);
        }
    }

    public static void onJump(long from, long to) {
        List<Entry> affected = new ArrayList<>();
        if (to < from) {
            for (Entry e : entries) if (e.tick > to && e.tick <= from) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
            for (Entry e : affected) apply(e, false);
        } else {
            for (Entry e : entries) if (e.tick > from && e.tick <= to) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
            for (Entry e : affected) apply(e, true);
        }
        finishBatch();
        rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        upcoming.clear();
        for (Entry e : entries) {
            if (e.tick > TimeTravel.timelineTick) upcoming.add(e);
        }
    }

    private static void apply(Entry e, boolean toNew) {
        if (e.oldItems != null) {
            applyContainer(e, toNew);
            return;
        }
        if (e.oldData != null) {
            applyData(e, toNew);
            return;
        }
        applying = true;
        try {
            ServerLevel level = levelOf(e.dim);
            if (level == null) return;

            BlockState state = toNew ? newState(e) : oldState(e);
            if (state == null) return;

            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            BlockState current = level.getBlockState(pos);
            if (current.equals(state)) return;

            if (!current.is(state.getBlock()) && level.getBlockEntity(pos) != null) {
                level.removeBlockEntity(pos);
            }
            level.setBlock(pos, state, SET_FLAGS);
            touched.computeIfAbsent(level, l -> new HashSet<>()).add(pos);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply block journal entry at {} {} {}", e.x, e.y, e.z, ex);
        } finally {
            applying = false;
        }
    }

    private static boolean isPistonPart(BlockState st) {
        Block b = st.getBlock();
        return b instanceof PistonBaseBlock || b instanceof PistonHeadBlock || st.is(Blocks.MOVING_PISTON);
    }

    private static void finishBatch() {
        if (touched.isEmpty()) return;
        applying = true;
        try {
            for (Map.Entry<ServerLevel, Set<BlockPos>> en : touched.entrySet()) {
                ServerLevel level = en.getKey();
                Set<BlockPos> positions = new HashSet<>();
                for (BlockPos p : en.getValue()) {
                    positions.add(p);
                    for (Direction d : Direction.values()) positions.add(p.relative(d));
                }
                for (BlockPos p : positions) {
                    BlockState st = level.getBlockState(p);
                    if (st.isAir() || isPistonPart(st)) continue;
                    BlockState fixed = Block.updateFromNeighbourShapes(st, level, p);
                    if (fixed.equals(st)) continue;
                    if (!fixed.is(st.getBlock()) && level.getBlockEntity(p) != null) level.removeBlockEntity(p);
                    level.setBlock(p, fixed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to refresh block connections", ex);
        } finally {
            applying = false;
            touched.clear();
        }
    }

    private static BlockState oldState(Entry e) {
        if (e.oldParsed == null) e.oldParsed = parse(e.oldState);
        return e.oldParsed;
    }

    private static BlockState newState(Entry e) {
        if (e.newParsed == null) e.newParsed = parse(e.newState);
        return e.newParsed;
    }

    private static BlockState parse(String s) {
        try {
            return BlockStateParser.parseForBlock(TimeTravel.server.registryAccess().lookupOrThrow(Registries.BLOCK), s, false).blockState();
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Cannot parse blockstate '{}'", s);
            return null;
        }
    }

    private static void watchContainers(ServerPlayer player, ServerLevel level, BlockPos center, int visitId) {
        Map<BlockPos, List<ItemStack>> snapshot = new HashMap<>();
        Map<BlockPos, String> data = new HashMap<>();
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(center.immutable());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(d));
        }
        for (BlockPos pos : candidates) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) {
                snapshot.put(pos.immutable(), copyOf(container));
            } else if (isDataEntity(be)) {
                data.put(pos.immutable(), dataOf(be));
            }
        }
        if (!snapshot.isEmpty() || !data.isEmpty()) {
            containerWatches.add(new ContainerWatch(player, level, visitId, snapshot, data));
        }
    }

    private static void pollContainers() {
        if (containerWatches.isEmpty()) return;

        Iterator<ContainerWatch> it = containerWatches.iterator();
        while (it.hasNext()) {
            ContainerWatch w = it.next();
            String dim = w.level().dimension().location().toString();

            for (Map.Entry<BlockPos, List<ItemStack>> e : w.last().entrySet()) {
                BlockEntity be = w.level().getBlockEntity(e.getKey());
                if (!(be instanceof Container container)) continue;

                List<ItemStack> now = copyOf(container);
                if (sameItems(e.getValue(), now)) continue;

                if (ProcessJournal.isProcess(be)) {
                    ProcessJournal.recordEvent(w.level(), e.getKey());
                    e.setValue(now);
                    continue;
                }

                Entry entry = new Entry();
                entry.dim = dim;
                entry.x = e.getKey().getX();
                entry.y = e.getKey().getY();
                entry.z = e.getKey().getZ();
                entry.oldItems = serializeItems(e.getValue());
                entry.newItems = serializeItems(now);
                entry.tick = TimeTravel.timelineTick;
                entry.seq = nextSeq++;
                entry.visit = w.visit();
                entry.root = true;
                entries.add(entry);
                dirty = true;

                e.setValue(now);
            }
            for (Map.Entry<BlockPos, String> e : w.data().entrySet()) {
                BlockEntity be = w.level().getBlockEntity(e.getKey());
                if (!isDataEntity(be)) continue;

                String now = dataOf(be);
                if (now.equals(e.getValue())) continue;

                Entry entry = new Entry();
                entry.dim = dim;
                entry.x = e.getKey().getX();
                entry.y = e.getKey().getY();
                entry.z = e.getKey().getZ();
                entry.oldData = e.getValue();
                entry.newData = now;
                entry.tick = TimeTravel.timelineTick;
                entry.seq = nextSeq++;
                entry.visit = w.visit();
                entry.root = true;
                entries.add(entry);
                dirty = true;

                e.setValue(now);
            }
            if (w.player().isRemoved() || w.player().containerMenu == w.player().inventoryMenu) {
                it.remove();
            }
        }
    }

    private static boolean isDataEntity(BlockEntity be) {
        return be instanceof SpawnerBlockEntity || be instanceof CampfireBlockEntity || be instanceof LecternBlockEntity;
    }

    private static String dataOf(BlockEntity be) {
        return be.saveWithFullMetadata(TimeTravel.server.registryAccess()).toString();
    }

    private static void applyData(Entry e, boolean toNew) {
        try {
            ServerLevel level = levelOf(e.dim);
            if (level == null) return;

            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            BlockEntity be = level.getBlockEntity(pos);
            if (!isDataEntity(be)) return;

            CompoundTag tag = TagParser.parseTag(toNew ? e.newData : e.oldData);
            be.loadWithComponents(tag, TimeTravel.server.registryAccess());
            be.setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply block entity journal entry at {} {} {}", e.x, e.y, e.z, ex);
        }
    }

    private static List<ItemStack> copyOf(Container container) {
        List<ItemStack> list = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            list.add(container.getItem(i).copy());
        }
        return list;
    }

    private static boolean sameItems(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private static String serializeItems(List<ItemStack> items) {
        NonNullList<ItemStack> list = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) list.set(i, items.get(i));
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, list, TimeTravel.server.registryAccess());
        return tag.toString();
    }

    private static void applyContainer(Entry e, boolean toNew) {
        try {
            ServerLevel level = levelOf(e.dim);
            if (level == null) return;

            BlockEntity be = level.getBlockEntity(new BlockPos(e.x, e.y, e.z));
            if (!(be instanceof Container container)) return;

            CompoundTag tag = TagParser.parseTag(toNew ? e.newItems : e.oldItems);
            NonNullList<ItemStack> list = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, list, TimeTravel.server.registryAccess());
            for (int i = 0; i < list.size(); i++) container.setItem(i, list.get(i));
            container.setChanged();
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply container journal entry at {} {} {}", e.x, e.y, e.z, ex);
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        List<Entry> mine = new ArrayList<>();
        for (Entry e : entries) if (visitIds.contains(e.visit)) mine.add(e);
        mine.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());

        for (Entry e : mine) {
            if (e.tick <= TimeTravel.timelineTick) apply(e, false);
        }
        finishBatch();
        entries.removeAll(mine);
        upcoming.removeAll(mine);
        rebuildIndex();
        dirty = true;
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_blocks.json");
    }

    public static void load() {
        entries.clear();
        upcoming.clear();
        watches.clear();
        containerWatches.clear();
        nextSeq = 0;
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                List<Entry> loaded = GSON.fromJson(r, LIST_TYPE);
                if (loaded != null) {
                    entries.addAll(loaded);
                    for (Entry e : loaded) nextSeq = Math.max(nextSeq, e.seq + 1);
                }
            } catch (Exception ex) {
                TimeTravel.LOOP_LOGGER.error("Failed to load block journal", ex);
            }
        }
        dirty = false;
        rebuildIndex();
        rebuildUpcoming();
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(entries, LIST_TYPE, w);
            dirty = false;
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to save block journal", ex);
        }
    }

    public static void clear() {
        entries.clear();
        upcoming.clear();
        watches.clear();
        containerWatches.clear();
        byPos.clear();
        nextSeq = 0;
        dirty = true;
        save();
    }
}
