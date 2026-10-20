package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.time.TimeContext;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.mixin.BaseRailBlockAccessor;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockJournal {
    private BlockJournal() {
    }

    private static final int SET_FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
    private static final int QUIET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final double TRACK_RANGE_SQ = 64.0 * 64.0;
    private static final long DEDUP_TICKS = 40;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<BlockState, String> SERIALIZED = new ConcurrentHashMap<>();

    public static final class Entry {
        String dim;
        int x, y, z;
        String oldState;
        String newState;
        long tick;
        long seq;
        int visit = -1;
        boolean root;
        boolean build;
        String oldItems;
        String newItems;
        boolean piston;
        boolean mech;
        transient BlockState oldParsed;
        transient BlockState newParsed;
    }

    private record Watch(ServerLevel level, long[] positions) {
    }

    private record MovingOrigin(BlockState state, long tick) {
    }

    private static final class SetFrame {
        final ServerLevel level;
        final long pos;
        final BlockState before;
        final BlockState requested;
        boolean notified;

        SetFrame(ServerLevel level, long pos, BlockState before, BlockState requested) {
            this.level = level;
            this.pos = pos;
            this.before = before;
            this.requested = requested;
        }
    }

    private record ContainerWatch(ServerPlayer player, ServerLevel level, int visit,
                                  Map<BlockPos, List<ItemStack>> last) {
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_blocks.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final List<ContainerWatch> containerWatches = new ArrayList<>();
    private static final List<Watch> watches = new ArrayList<>();
    private static final Map<ServerLevel, Set<BlockPos>> touched = new HashMap<>();
    private static final Map<ServerLevel, Set<BlockPos>> settleTouched = new HashMap<>();
    private static final Map<ServerLevel, Set<BlockPos>> fluidTouched = new HashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, Fluid>> drainSeeds = new HashMap<>();
    private static final int DRAIN_LIMIT = 8192;
    private static final Map<Long, List<Entry>> byPos = new HashMap<>();
    private static boolean applying = false;
    private static boolean erasing = false;
    private static boolean replayingLive = false;
    private static final ArrayDeque<SetFrame> setFrames = new ArrayDeque<>();
    private static final Map<ServerLevel, Set<BlockPos>> jukeboxes = new HashMap<>();
    private static int actionDepth = 0;
    private static int buildDepth = 0;
    private static final Map<ServerLevel, Map<Long, MovingOrigin>> moving = new HashMap<>();

    public static boolean isApplying() {
        return applying;
    }

    public static boolean isErasing() {
        return erasing;
    }

    private static boolean isPistonStruct(BlockState st) {
        Block b = st.getBlock();
        return b instanceof PistonBaseBlock || b instanceof PistonHeadBlock;
    }

    public static boolean shouldSkipAutomation() {
        return TimeTravel.isLooping && ReplayState.isReplaying();
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

    public static void enterBuild() {
        buildDepth++;
    }

    public static void exitBuild() {
        if (buildDepth > 0) buildDepth--;
    }

    public static boolean isLocked(ServerLevel level, BlockPos pos, BlockState requested) {
        if (!TimeTravel.isLooping || applying || replayingLive || buildDepth > 0) return false;
        List<Entry> list = byPos.get(pos.asLong());
        if (list == null) return false;
        BlockState current = level.getBlockState(pos);
        if (current.is(requested.getBlock())) return false;
        String dim = Levels.canonicalId(level);
        long now = TimeContext.now();
        for (Entry e : list) {
            if (!e.build || e.tick <= now || !e.dim.equals(dim)) continue;
            BlockState old = oldState(e);
            if (old != null && old.is(current.getBlock())) return true;
        }
        return false;
    }

    private static String serialize(BlockState state) {
        return SERIALIZED.computeIfAbsent(state, BlockStateParser::serialize);
    }

    public static void watch(ServerPlayer player, BlockPos center) {
        if (!TimeTravel.isLooping || TimeTravel.timelineSceneManager == null) return;
        if (!TimeTravel.isRealPlayer(player)) return;
        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(player.getName().getString());
        if (pd == null || !pd.getActive()) return;

        ServerLevel level = player.serverLevel();
        BlockPos c = center.immutable();
        long[] positions = new long[125];
        int i = 0;
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -2; dz <= 2; dz++)
                    positions[i++] = BlockPos.asLong(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
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

    public static void beginSet(ServerLevel level, BlockPos pos, BlockState requested) {
        if (!TimeTravel.isLooping) return;
        setFrames.push(new SetFrame(level, pos.asLong(), level.getBlockState(pos), requested));
    }

    public static void endSet(ServerLevel level, BlockPos pos, boolean changed) {
        SetFrame top = null;
        for (SetFrame f : setFrames) {
            if (f.level == level && f.pos == pos.asLong()) {
                top = f;
                break;
            }
        }
        if (top == null) return;
        while (setFrames.peek() != top) setFrames.pop();
        setFrames.pop();

        if (!changed || top.notified || applying || !TimeTravel.isLooping || TimeTravel.timelineSceneManager == null)
            return;

        BlockState now = level.getBlockState(pos);
        if (!now.equals(top.before)) record(level, pos, top.before, now);
    }

    public static void flushWatches() {
        actionDepth = 0;
        buildDepth = 0;
        setFrames.clear();
        pollContainers();
        if (watches.isEmpty()) return;
        List<Watch> copy = new ArrayList<>(watches);
        watches.clear();
        for (Watch w : copy) {
            for (long pos : w.positions())
                ProcessJournal.trackIfProcess(w.level(), BlockPos.of(pos));
        }
    }

    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (applying || !TimeTravel.isLooping || TimeTravel.timelineSceneManager == null) return;
        SetFrame top = setFrames.peek();
        if (top != null && top.pos == pos.asLong() && top.level == level && top.requested.equals(now)) {
            top.notified = true;
        }
        for (SetFrame f : setFrames) {
            if (!f.notified && f.pos == pos.asLong() && f.level == level && f.requested.equals(old) && !f.before.equals(old)) {
                old = f.before;
                f.notified = true;
                break;
            }
        }
        record(level, pos, old, now);
    }

    private static void record(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        boolean mechanical = RedstoneJournal.isMechanical(old) || RedstoneJournal.isMechanical(now);
        boolean derived = actionDepth == 0 && mechanical;
        if (actionDepth == 0 && old.getBlock() == now.getBlock() && RedstoneJournal.isOutput(now)) return;
        if (derived && ReplayState.isReplaying()) return;
        long tick = TimeContext.now();
        Map<Long, MovingOrigin> inFlight = moving.computeIfAbsent(level, l -> new HashMap<>());
        if (now.is(Blocks.MOVING_PISTON)) {
            if (!old.is(Blocks.MOVING_PISTON)) inFlight.put(pos.asLong(), new MovingOrigin(old, tick));
            return;
        }
        if (old.is(Blocks.MOVING_PISTON)) {
            MovingOrigin origin = inFlight.remove(pos.asLong());
            if (origin == null) return;
            old = origin.state();
            tick = origin.tick();
        }
        if (old.equals(now) || !shouldTrack(old, now)) return;

        PlayerData pd = nearestRecorder(level, pos);
        int visit;
        if (pd != null && pd.getCurrentVisit() != null) {
            visit = pd.getCurrentVisit().id;
        } else {
            boolean hasRealPlayer = false;
            for (ServerPlayer p : level.players()) {
                if (!Clones.is(p)) {
                    hasRealPlayer = true;
                    break;
                }
            }
            if (!hasRealPlayer) return;
            visit = -1;
        }

        String dim = Levels.canonicalId(level);
        String oldS = serialize(old);
        String newS = serialize(now);

        List<Entry> same = byPos.get(pos.asLong());
        if (same != null) {
            for (Entry e : same) {
                if (isPistonStruct(old) || isPistonStruct(now)) break;
                if (!e.dim.equals(dim)) continue;
                if (e.visit == visit) {
                    if (e.tick != tick) continue;
                } else {
                    if (Math.abs(e.tick - tick) > DEDUP_TICKS) continue;
                }
                if (!oldS.equals(e.oldState) || !newS.equals(e.newState)) continue;
                return;
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
        entry.tick = tick;
        entry.seq = STORE.nextSeq();
        entry.visit = visit;
        entry.root = actionDepth > 0 && !(old.getBlock() instanceof RedStoneWireBlock && now.getBlock() instanceof RedStoneWireBlock);
        entry.build = buildDepth > 0;
        entry.mech = derived;
        STORE.entries().add(entry);
        index(entry);
        STORE.markDirty();
    }

    private static boolean isSourceLiquid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().isSource();
    }

    private static Fluid sourceOf(FluidState state) {
        Fluid type = state.getType();
        return type instanceof FlowingFluid flowing ? flowing.getSource() : type;
    }

    private static boolean shouldTrack(BlockState old, BlockState now) {
        Block a = old.getBlock();
        Block b = now.getBlock();
        if (a instanceof LiquidBlock || b instanceof LiquidBlock) {
            if (a instanceof LiquidBlock && b instanceof LiquidBlock)
                return actionDepth > 0 && (isSourceLiquid(old) || isSourceLiquid(now));
            if (b instanceof LiquidBlock && !old.isAir()) return true;
            return actionDepth > 0 && (isSourceLiquid(old) || isSourceLiquid(now));
        }
        if (a == b && (a instanceof AbstractFurnaceBlock || a instanceof BrewingStandBlock)) return false;
        if (a instanceof FireBlock && (b instanceof FireBlock || now.isAir())) return false;
        if (b instanceof FireBlock && old.isAir()) return false;
        return true;
    }

    private static void nudgeFluids(ServerLevel level, BlockPos pos) {
        nudgeOne(level, pos);
        for (Direction d : DIRECTIONS) nudgeOne(level, pos.relative(d));
    }

    private static void nudgeOne(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        FluidState fluid = level.getFluidState(pos);
        if (!fluid.isEmpty()) level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
    }

    private static boolean isFlowingLiquid(BlockState state, Fluid source) {
        if (!(state.getBlock() instanceof LiquidBlock)) return false;
        FluidState fs = state.getFluidState();
        return !fs.isSource() && !fs.isEmpty() && sourceOf(fs) == source;
    }

    private static void drainAll() {
        if (drainSeeds.isEmpty()) return;
        Map<ServerLevel, Map<BlockPos, Fluid>> copy = new HashMap<>(drainSeeds);
        drainSeeds.clear();
        for (Map.Entry<ServerLevel, Map<BlockPos, Fluid>> en : copy.entrySet()) {
            ServerLevel level = en.getKey();
            for (Map.Entry<BlockPos, Fluid> seed : en.getValue().entrySet()) {
                drain(level, seed.getKey(), seed.getValue());
            }
        }
    }

    private static void drain(ServerLevel level, BlockPos origin, Fluid source) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        List<BlockPos> region = new ArrayList<>();
        seen.add(origin.asLong());
        queue.add(origin);
        while (!queue.isEmpty() && region.size() < DRAIN_LIMIT) {
            BlockPos cur = queue.poll();
            for (Direction d : DIRECTIONS) {
                BlockPos next = cur.relative(d);
                if (!level.isLoaded(next) || !seen.add(next.asLong())) continue;
                if (!isFlowingLiquid(level.getBlockState(next), source)) continue;
                region.add(next);
                queue.add(next);
            }
        }
        if (region.isEmpty()) return;

        applying = true;
        try {
            BlockState air = Blocks.AIR.defaultBlockState();
            for (BlockPos p : region) level.setBlock(p, air, QUIET_FLAGS);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to drain fluid around {}", origin, ex);
        } finally {
            applying = false;
        }

        Set<BlockPos> boundary = new HashSet<>();
        for (BlockPos p : region) {
            for (Direction d : DIRECTIONS) boundary.add(p.relative(d));
        }
        boundary.add(origin);
        for (BlockPos p : boundary) nudgeOne(level, p);
    }

    private static void flushFluids() {
        drainAll();
        if (fluidTouched.isEmpty()) return;
        Map<ServerLevel, Set<BlockPos>> copy = new HashMap<>(fluidTouched);
        fluidTouched.clear();
        for (Map.Entry<ServerLevel, Set<BlockPos>> en : copy.entrySet()) {
            for (BlockPos p : en.getValue()) nudgeFluids(en.getKey(), p);
        }
    }

    public static PlayerData nearestRecorder(ServerLevel level, BlockPos pos) {
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        for (ServerPlayer p : level.players()) {
            if (Clones.is(p)) continue;
            if (p.distanceToSqr(cx, cy, cz) > TRACK_RANGE_SQ) continue;
            PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(p.getName().getString());
            if (pd != null && pd.getActive() && pd.getCurrentVisit() != null) return pd;
        }
        return null;
    }

    private static void index(Entry e) {
        byPos.computeIfAbsent(BlockPos.asLong(e.x, e.y, e.z), k -> new ArrayList<>()).add(e);
    }

    private static void rebuildIndex() {
        byPos.clear();
        for (Entry e : STORE.entries()) index(e);
    }

    private static ServerLevel levelOf(String dim) {
        return TimeTravel.server.getLevel(Levels.key(dim));
    }

    public static void tick() {
        boolean replay = ReplayState.isReplaying();
        STORE.tick(e -> {
            if (e.root || e.build || replay) applyLive(e);
        });
        flushJukeboxes();
    }

    private static void applyLive(Entry e) {
        if (e.mech || e.piston) return;
        if (e.oldItems != null) {
            applyContainer(e, true, true);
            return;
        }
        try {
            ServerLevel level = levelOf(e.dim);
            BlockState state = newState(e);
            if (level == null || state == null) return;
            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            BlockState before = level.getBlockState(pos);
            if (before.is(Blocks.MOVING_PISTON)) return;
            if (before.equals(state)) return;
            if (!e.root) {
                BlockState old = oldState(e);
                if (RedstoneJournal.isNaturalPhysics(state) || RedstoneJournal.isNaturalPhysics(before)
                        || (old != null && RedstoneJournal.isNaturalPhysics(old)) || nearPistonPart(level, pos))
                    return;
            }
            clearContainerBeforeRemoval(level, pos, before, state);

            replayingLive = true;
            try {
                level.setBlock(pos, state, SET_FLAGS);
                if (state.getBlock() instanceof BaseRailBlock) {
                    BlockPos ip = pos.immutable();
                    BlockState after = level.getBlockState(ip);
                    if (after.getBlock() instanceof BaseRailBlock railBlock) {
                        BlockState fixed = ((BaseRailBlockAccessor) railBlock).tt$updateState(after, level, ip, false);
                        if (!fixed.equals(after)) {
                            applying = true;
                            try {
                                level.setBlock(ip, fixed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                            } finally {
                                applying = false;
                            }
                        }
                    }
                }
            } finally {
                replayingLive = false;
            }
            if (!before.getFluidState().isEmpty() || !state.getFluidState().isEmpty()) nudgeFluids(level, pos);
            if (state.getBlock() instanceof ButtonBlock && state.getValue(ButtonBlock.POWERED)) {
                level.scheduleTick(pos, state.getBlock(), state.is(BlockTags.WOODEN_BUTTONS) ? 30 : 20);
            } else if (state.getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock
                    && state.getSignal(level, pos, Direction.UP) > 0) {
                level.scheduleTick(pos, state.getBlock(),
                        state.getBlock() instanceof net.minecraft.world.level.block.WeightedPressurePlateBlock ? 10 : 20);
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay block journal entry at {} {} {}", e.x, e.y, e.z, ex);
        }
    }

    public static void beforeJump() {
        for (Map.Entry<ServerLevel, Map<Long, MovingOrigin>> en : moving.entrySet()) {
            ServerLevel level = en.getKey();
            for (long key : new ArrayList<>(en.getValue().keySet())) {
                BlockPos pos = BlockPos.of(key);
                for (int i = 0; i < 4 && level.getBlockEntity(pos) instanceof PistonMovingBlockEntity be; i++) {
                    PistonMovingBlockEntity.tick(level, pos, level.getBlockState(pos), be);
                }
            }
        }
        moving.clear();
    }

    public static void onJump(long from, long to) {
        List<Entry> affected = new ArrayList<>();
        if (to < from) {
            for (Entry e : STORE.entries()) if (e.tick > to && e.tick <= from) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
            for (Entry e : affected) apply(e, false);
        } else {
            for (Entry e : STORE.entries()) if (e.tick > from && e.tick <= to) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
            for (Entry e : affected) apply(e, true);
        }
        finishBatch();
        settleAfterBatch();
        flushFluids();
        flushJukeboxes();
        rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    private static void apply(Entry e, boolean toNew) {
        if (e.oldItems != null) {
            applyContainer(e, toNew, false);
            return;
        }
        final boolean prevApplying = applying;
        applying = true;
        try {
            ServerLevel level = levelOf(e.dim);
            if (level == null) return;

            BlockState state = toNew ? newState(e) : oldState(e);
            if (state == null) return;

            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            BlockState current = level.getBlockState(pos);
            boolean rail = current.getBlock() instanceof BaseRailBlock || state.getBlock() instanceof BaseRailBlock;
            if (!current.equals(state)) {
                if (!current.is(state.getBlock()) && level.getBlockEntity(pos) != null) {
                    clearContainerBeforeRemoval(level, pos, current, state);
                    level.removeBlockEntity(pos);
                }
                int flags = e.mech || (rail && !toNew) ? QUIET_FLAGS : (rail ? Block.UPDATE_ALL : SET_FLAGS);
                level.setBlock(pos, state, flags);
                if (!current.getFluidState().isEmpty() || !state.getFluidState().isEmpty()) {
                    fluidTouched.computeIfAbsent(level, l -> new HashSet<>()).add(pos.immutable());
                }
                if (!toNew && isSourceLiquid(current) && !isSourceLiquid(state)) {
                    drainSeeds.computeIfAbsent(level, l -> new HashMap<>()).put(pos.immutable(), sourceOf(current.getFluidState()));
                }
                if (rail) {
                    BlockPos ip = pos.immutable();
                    BlockState after = level.getBlockState(ip);
                    if (after.getBlock() instanceof BaseRailBlock railBlock) {
                        BlockState fixed = ((BaseRailBlockAccessor) railBlock).tt$updateState(after, level, ip, false);
                        if (!fixed.equals(after)) {
                            level.setBlock(ip, fixed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
            if (rail) {
                touched.computeIfAbsent(level, l -> new HashSet<>()).add(pos);
            }
            if (RedstoneJournal.needsSettle(current) || RedstoneJournal.needsSettle(state)) {
                settleTouched.computeIfAbsent(level, l -> new HashSet<>()).add(pos.immutable());
            }
            if (current.getBlock() instanceof JukeboxBlock || state.getBlock() instanceof JukeboxBlock) {
                markJukebox(level, pos);
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to apply block journal entry at {} {} {}", e.x, e.y, e.z, ex);
        } finally {
            applying = prevApplying;
        }
    }

    private static void clearContainerBeforeRemoval(ServerLevel level, BlockPos pos, BlockState current, BlockState next) {
        if (current.is(next.getBlock())) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            container.clearContent();
            container.setChanged();
        }
    }

    private static void discardNearbyItems(ServerLevel level, Entry e) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(e.x + 0.5 - 1.5, e.y + 0.5 - 1.5, e.z + 0.5 - 1.5, e.x + 0.5 + 1.5, e.y + 0.5 + 1.5, e.z + 0.5 + 1.5);
        for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (!item.isRemoved()) item.discard();
        }
    }

    private static boolean nearPistonPart(ServerLevel level, BlockPos pos) {
        for (Direction d : DIRECTIONS) {
            if (isPistonPart(level.getBlockState(pos.relative(d)))) return true;
        }
        return false;
    }

    private static boolean isPistonPart(BlockState st) {
        Block b = st.getBlock();
        return b instanceof PistonBaseBlock || b instanceof PistonHeadBlock || st.is(Blocks.MOVING_PISTON);
    }

    private static void finishBatch() {
        if (touched.isEmpty()) return;
        final boolean prevApplying = applying;
        applying = true;
        try {
            for (Map.Entry<ServerLevel, Set<BlockPos>> en : touched.entrySet()) {
                ServerLevel level = en.getKey();
                TreeSet<BlockPos> positions = new TreeSet<>(Comparator.comparingLong(BlockPos::asLong));
                for (BlockPos p : en.getValue()) {
                    positions.add(p);
                    for (Direction d : DIRECTIONS) positions.add(p.relative(d));
                }
                boolean changed = true;
                int passes = 0;
                while (changed && passes++ < 8) {
                    changed = false;
                    for (BlockPos p : positions) {
                        BlockState st = level.getBlockState(p);
                        if (st.isAir()) continue;

                        BlockState fixed;
                        if (st.getBlock() instanceof BaseRailBlock railBlock) {
                            fixed = ((BaseRailBlockAccessor) railBlock).tt$updateState(st, level, p, false);
                        } else {
                            if (isPistonPart(st)) continue;
                            fixed = Block.updateFromNeighbourShapes(st, level, p);
                        }
                        if (fixed.equals(st)) continue;
                        if (!fixed.is(st.getBlock()) && level.getBlockEntity(p) != null) level.removeBlockEntity(p);
                        level.setBlock(p, fixed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        changed = true;
                    }
                }
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to refresh block connections", ex);
        } finally {
            applying = prevApplying;
            touched.clear();
        }
    }

    private static void settleAfterBatch() {
        if (settleTouched.isEmpty()) return;
        try {
            RedstoneJournal.settle(settleTouched);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to settle redstone after jump", ex);
        } finally {
            settleTouched.clear();
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
            TimeTravel.TIMELINE_LOGGER.error("Cannot parse blockstate '{}'", s);
            return null;
        }
    }

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
            String dim = Levels.canonicalId(w.level());

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
                entry.tick = TimeContext.now();
                entry.seq = STORE.nextSeq();
                entry.visit = w.visit();
                entry.root = true;
                STORE.entries().add(entry);
                STORE.markDirty();

                e.setValue(now);
            }
            if (w.player().isRemoved() || w.player().containerMenu == w.player().inventoryMenu) {
                it.remove();
            }
        }
    }

    public static void recordContainerChange(ServerLevel level, BlockPos pos, List<ItemStack> before) {
        if (applying || !TimeTravel.isLooping || TimeTravel.timelineSceneManager == null) return;
        if (!(level.getBlockEntity(pos) instanceof Container container)) return;
        List<ItemStack> now = copyOf(container);
        if (sameItems(before, now)) return;
        PlayerData pd = nearestRecorder(level, pos);
        if (pd == null) return;

        Entry entry = new Entry();
        entry.dim = Levels.canonicalId(level);
        entry.x = pos.getX();
        entry.y = pos.getY();
        entry.z = pos.getZ();
        entry.oldItems = serializeItems(before);
        entry.newItems = serializeItems(now);
        entry.tick = TimeContext.now();
        entry.seq = STORE.nextSeq();
        entry.visit = pd.getCurrentVisit().id;
        entry.root = false;
        STORE.entries().add(entry);
        STORE.markDirty();
    }

    public static List<ItemStack> copyOf(Container container) {
        List<ItemStack> list = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            list.add(container.getItem(i).copy());
        }
        return list;
    }

    public static boolean sameItems(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    public static String serializeItems(List<ItemStack> items) {
        NonNullList<ItemStack> list = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) list.set(i, items.get(i));
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, list, TimeTravel.server.registryAccess());
        return tag.toString();
    }

    public static List<ItemStack> parseItems(String tagStr, int size) {
        NonNullList<ItemStack> list = NonNullList.withSize(size, ItemStack.EMPTY);
        try {
            CompoundTag tag = TagParser.parseTag(tagStr);
            ContainerHelper.loadAllItems(tag, list, TimeTravel.server.registryAccess());
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to parse container items", ex);
        }
        return list;
    }

    private static void applyContainer(Entry e, boolean toNew, boolean live) {
        try {
            ServerLevel level = levelOf(e.dim);
            if (level == null) return;

            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container container)) return;

            boolean jukebox = be instanceof JukeboxBlockEntity;
            boolean wasApplying = applying;
            if (jukebox) applying = true;
            try {
                int size = container.getContainerSize();
                List<ItemStack> oldList = parseItems(e.oldItems, size);
                List<ItemStack> newList = parseItems(e.newItems, size);

                for (int i = 0; i < size; i++) {
                    ItemStack from = oldList.get(i);
                    ItemStack to = newList.get(i);
                    if (ItemStack.matches(from, to)) continue;
                    ItemStack target = toNew ? to : from;
                    container.setItem(i, target.copy());
                }
                container.setChanged();
            } finally {
                if (jukebox) applying = wasApplying;
            }

            if (jukebox) markJukebox(level, pos);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to apply container journal entry at {} {} {}", e.x, e.y, e.z, ex);
        }
    }

    public static void markJukebox(ServerLevel level, BlockPos pos) {
        jukeboxes.computeIfAbsent(level, l -> new HashSet<>()).add(pos.immutable());
    }

    //am I overthinking it
    private static void flushJukeboxes() {
        if (jukeboxes.isEmpty()) return;
        Map<ServerLevel, Set<BlockPos>> copy = new HashMap<>(jukeboxes);
        jukeboxes.clear();
        for (Map.Entry<ServerLevel, Set<BlockPos>> en : copy.entrySet()) {
            ServerLevel level = en.getKey();
            for (BlockPos pos : en.getValue()) {
                try {
                    if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity jb) {
                        syncJukebox(level, pos, jb);
                    } else {
                        level.levelEvent(LevelEvent.SOUND_STOP_JUKEBOX_SONG, pos, 0);
                    }
                } catch (Exception ex) {
                    TimeTravel.TIMELINE_LOGGER.error("Failed to sync jukebox at {}", pos, ex);
                }
            }
        }
    }

    private static void syncJukebox(ServerLevel level, BlockPos pos, JukeboxBlockEntity jukebox) {
        level.levelEvent(LevelEvent.SOUND_STOP_JUKEBOX_SONG, pos, 0);
        JukeboxSongPlayer player = jukebox.getSongPlayer();
        player.stop(level, level.getBlockState(pos));

        ItemStack disc = jukebox.getTheItem();
        if (disc.isEmpty()) return;
        JukeboxSong.fromStack(level.registryAccess(), disc).ifPresent(song -> player.play(level, song));
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        List<Entry> mine = new ArrayList<>();
        for (Entry e : STORE.entries()) if (visitIds.contains(e.visit)) mine.add(e);
        mine.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());

        erasing = true;
        try {
            for (Entry e : mine) {
                apply(e, false);
                ServerLevel level = levelOf(e.dim);
                if (level != null && e.oldItems == null) discardNearbyItems(level, e);
            }
            finishBatch();
            settleAfterBatch();
            flushFluids();
        } finally {
            erasing = false;
        }
        STORE.entries().removeAll(mine);
        STORE.upcoming().removeAll(mine);
        rebuildIndex();
        STORE.markDirty();
    }

    public static void load() {
        STORE.load();
        STORE.entries().removeIf(e -> e.oldItems == null && e.piston);
        rebuildIndex();
        STORE.rebuildUpcoming();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void save() {
        STORE.save();
    }

    public static void clear() {
        watches.clear();
        containerWatches.clear();
        byPos.clear();
        setFrames.clear();
        jukeboxes.clear();
        fluidTouched.clear();
        drainSeeds.clear();
        moving.clear();
        STORE.clear();
    }
}
