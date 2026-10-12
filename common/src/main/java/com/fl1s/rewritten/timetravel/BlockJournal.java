package com.fl1s.rewritten.timetravel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.BrewingStandBlock;
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

    private static final int REPLAY_DELAY_TICKS = 0;

    private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
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
        String oldItems;
        String newItems;
        transient BlockState oldParsed;
        transient BlockState newParsed;
    }

    private record Watch(ServerLevel level, Map<BlockPos, BlockState> before, int visit) {}

    private record ContainerWatch(ServerPlayer player, ServerLevel level, int visit, Map<BlockPos, List<ItemStack>> last) {}

    private static final List<ContainerWatch> containerWatches = new ArrayList<>();

    private static final List<Entry> entries = new ArrayList<>();
    private static final PriorityQueue<Entry> upcoming = new PriorityQueue<>(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static final List<Watch> watches = new ArrayList<>();
    private static final Map<ServerLevel, Set<BlockPos>> touched = new HashMap<>();
    private static long nextSeq = 0;
    private static boolean dirty = false;

    //hooks (called from loader events)
    public static void watch(ServerPlayer player, BlockPos center) {
        if (!TimeTravel.isLooping || TimeTravel.loopSceneManager == null) return;
        if (player.getClass().getName().contains("FakePlayer")) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive()) return;

        ServerLevel level = player.serverLevel();
        Map<BlockPos, BlockState> before = new HashMap<>();
        BlockPos c = center.immutable();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos n = c.offset(dx, dy, dz);
                    before.put(n, level.getBlockState(n));
                }
            }
        }
        int visitId = pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
        watches.add(new Watch(level, before, visitId));
        watchContainers(player, level, c, visitId);
    }

    public static void watchLookTarget(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 1.0f, true);
        if (hit instanceof BlockHitResult blockHit) {
            watch(player, blockHit.getBlockPos());
        }
    }

    public static void flushWatches() {
        pollContainers();
        if (watches.isEmpty()) return;
        List<Watch> copy = new ArrayList<>(watches);
        watches.clear();

        for (Watch w : copy) {
            String dim = w.level().dimension().location().toString();
            for (Map.Entry<BlockPos, BlockState> e : w.before().entrySet()) {
                ProcessJournal.trackIfProcess(w.level(), e.getKey());

                BlockState now = w.level().getBlockState(e.getKey());
                if (now.equals(e.getValue())) continue;

                Block nowBlock = now.getBlock();
                if (nowBlock == e.getValue().getBlock()
                        && (nowBlock instanceof AbstractFurnaceBlock || nowBlock instanceof BrewingStandBlock)) {
                    continue;
                }

                Entry entry = new Entry();
                entry.dim = dim;
                entry.x = e.getKey().getX();
                entry.y = e.getKey().getY();
                entry.z = e.getKey().getZ();
                entry.oldState = BlockStateParser.serialize(e.getValue());
                entry.newState = BlockStateParser.serialize(now);
                entry.oldParsed = e.getValue();
                entry.newParsed = now;
                entry.tick = TimeTravel.timelineTick;
                entry.seq = nextSeq++;
                entry.visit = w.visit();
                entries.add(entry);
                dirty = true;
            }
        }
    }

    //time flow
    public static void tick() {
        boolean any = false;
        while (!upcoming.isEmpty() && upcoming.peek().tick + REPLAY_DELAY_TICKS <= TimeTravel.timelineTick) {
            apply(upcoming.poll(), true, true);
            any = true;
        }
        if (any) finishBatch();
    }

    public static void onJump(long from, long to) {
        List<Entry> affected = new ArrayList<>();
        if (to < from) {
            for (Entry e : entries) if (e.tick > to && e.tick <= from) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
            for (Entry e : affected) apply(e, false, false);
        } else {
            for (Entry e : entries) if (e.tick > from - REPLAY_DELAY_TICKS && e.tick <= to) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
            for (Entry e : affected) apply(e, true, false);
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

    private static void apply(Entry e, boolean toNew, boolean effects) {
        if (e.oldItems != null) {
            applyContainer(e, toNew);
            return;
        }
        try {
            ServerLevel level = TimeTravel.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(e.dim)));
            if (level == null) return;

            BlockState state = toNew ? newState(e) : oldState(e);
            if (state == null) return;

            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            BlockState current = level.getBlockState(pos);
            if (current.equals(state)) return;

            if (!current.is(state.getBlock()) && level.getBlockEntity(pos) != null) {
                level.removeBlockEntity(pos);
            }

            if (effects && !current.isAir() && state.isAir()) {
                level.destroyBlock(pos, false);
            } else {
                level.setBlock(pos, state, SET_FLAGS);
                if (effects && current.isAir() && !state.isAir()) {
                    SoundType type = state.getSoundType();
                    level.playSound(null, pos, type.getPlaceSound(), SoundSource.BLOCKS,
                            (type.getVolume() + 1.0f) / 2.0f, type.getPitch() * 0.8f);
                }
            }
            touched.computeIfAbsent(level, l -> new HashSet<>()).add(pos);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply block journal entry at {} {} {}", e.x, e.y, e.z, ex);
        }
    }

    private static void finishBatch() {
        if (touched.isEmpty()) return;
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
                    if (st.isAir()) continue;
                    BlockState fixed = Block.updateFromNeighbourShapes(st, level, p);
                    if (fixed.equals(st)) continue;
                    if (!fixed.is(st.getBlock()) && level.getBlockEntity(p) != null) level.removeBlockEntity(p);
                    level.setBlock(p, fixed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to refresh block connections", ex);
        } finally {
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


    //containers
    private static void watchContainers(ServerPlayer player, ServerLevel level, BlockPos center, int visitId) {
        Map<BlockPos, List<ItemStack>> snapshot = new HashMap<>();
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(center.immutable());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(d));
        }
        for (BlockPos pos : candidates) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) {
                snapshot.put(pos.immutable(), copyOf(container));
            }
        }
        if (!snapshot.isEmpty()) {
            containerWatches.add(new ContainerWatch(player, level, visitId, snapshot));
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
                entries.add(entry);
                dirty = true;

                e.setValue(now);
            }

            if (w.player().isRemoved() || w.player().containerMenu == w.player().inventoryMenu) {
                it.remove();
            }
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
            ServerLevel level = TimeTravel.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(e.dim)));
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
            if (e.tick <= TimeTravel.timelineTick) apply(e, false, false);
        }
        finishBatch();
        entries.removeAll(mine);
        upcoming.removeAll(mine);
        dirty = true;
    }

    //misc
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
        nextSeq = 0;
        dirty = true;
        save();
    }
}
