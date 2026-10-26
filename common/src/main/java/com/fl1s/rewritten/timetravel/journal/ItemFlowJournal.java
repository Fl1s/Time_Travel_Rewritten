package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.mixin.ItemEntityAccessor;
import com.fl1s.rewritten.timetravel.mob.MobJournal;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Records mob death loot and player pickups, then replays both at their
 * recorded ticks.
 */
public final class ItemFlowJournal {
    private ItemFlowJournal() {}

    public static final int KIND_PICKUP = 0;
    public static final int KIND_DROP = 1;
    public static final int KIND_DESTROY = 2;
    private static final int ITEM_LIFESPAN = 6000;
    public static final class Entry {
        public int kind;
        public long tick;
        public long seq;
        public int visit;
        public String player;
        public String dim;
        public double x, y, z;
        public String stack;
        public UUID itemUuid;
        public long originTick = Long.MIN_VALUE;
        public double vx, vy, vz;
        public int pickupDelay;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_item_flow.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static final ArrayList<Entry> retry = new ArrayList<>();
    private static final Set<UUID> tracked = new HashSet<>();
    private static final List<ItemEntity> toDiscard = new ArrayList<>();

    private static final class Ghost {
        final String dim;
        final UUID id;
        final long dropTick;
        final long deadline;
        final int cx, cz;
        final String key;
        boolean held;

        Ghost(String dim, UUID id, long dropTick, long deadline, int cx, int cz, String key, boolean held) {
            this.dim = dim;
            this.id = id;
            this.dropTick = dropTick;
            this.deadline = deadline;
            this.cz = cz;
            this.key = key;
            this.held = held;
        }
    }

    private static final int GHOST_TIMEOUT_TICKS = 200;
    private static final int GHOST_LIMIT = 64;
    private static final List<Ghost> ghosts = new ArrayList<>();
    private static final Map<String, Integer> forcedByUs = new HashMap<>();

    private static boolean applying = false;
    private static boolean suppressDrop = false;
    private static int replayDepth = 0;
    private static LivingEntity capturingEntity = null;

    public static boolean isApplying() { return applying; }
    public static boolean isSuppressDrop() { return suppressDrop; }
    public static void setSuppressDrop(boolean v) { suppressDrop = v; }
    public static boolean isCapturingDeath() { return capturingEntity != null; }
    public static void beginDeathCapture(LivingEntity e) { capturingEntity = e; }
    public static void endDeathCapture() { capturingEntity = null; }
    public static boolean isTracked(UUID id) { return tracked.contains(id); }

    public static void pushReplay() { replayDepth++; }
    public static void popReplay() { replayDepth = Math.max(0, replayDepth - 1); }

    public static boolean isCloneKill() {
        return capturingEntity != null && killedByClone(capturingEntity);
    }

    private static boolean killedByClone(LivingEntity victim) {
        DamageSource src = victim.getLastDamageSource();
        if (src == null) return false;
        Entity attacker = src.getEntity();
        Entity direct = src.getDirectEntity();
        return (attacker != null && Clones.is(attacker)) || (direct != null && Clones.is(direct));
    }

    public static void onEntitySpawn(ServerLevel level, Entity entity) {
        if (!(entity instanceof ItemEntity item)) return;
        if (!TimeTravel.isLooping || applying || ReplayState.SUPPRESS) return;
        if (tracked.contains(item.getUUID())) return;
        if (isReplayCaused(item)) {
            toDiscard.add(item);
            return;
        }
        recordDrop(item, level);
    }

    private static boolean isReplayCaused(ItemEntity item) {
        if (replayDepth > 0 || suppressDrop) return true;
        if (MobJournal.isApplying() || MobJournal.isKilling()) return true;
        Entity owner = item.getOwner();
        if (owner != null && Clones.is(owner)) return true;
        return capturingEntity != null && killedByClone(capturingEntity);
    }

    private static void recordDrop(ItemEntity item, ServerLevel level) {
        PlayerData pd = BlockJournal.nearestRecorder(level, item.blockPosition());
        if (pd == null || pd.getCurrentVisit() == null) return;

        Entry e = new Entry();
        e.kind = KIND_DROP;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.player = "";
        e.dim = level.dimension().location().toString().intern();
        e.x = item.getX();
        e.y = item.getY();
        e.z = item.getZ();
        Vec3 v = item.getDeltaMovement();
        e.vx = v.x;
        e.vy = v.y;
        e.vz = v.z;
        e.pickupDelay = ((ItemEntityAccessor) item).tt$getPickupDelay();
        e.stack = item.getItem().save(TimeTravel.server.registryAccess()).toString();
        e.itemUuid = item.getUUID();
        STORE.entries().add(e);
        tracked.add(e.itemUuid);
        STORE.markDirty();
    }

    public static void recordDestroy(ItemEntity item) {
        if (!TimeTravel.isLooping || applying || ReplayState.SUPPRESS) return;
        if (!(item.level() instanceof ServerLevel level)) return;
        UUID id = item.getUUID();
        if (!tracked.contains(id)) return;

        Entry drop = null;
        for (Entry e : STORE.entries()) {
            if (e.kind == KIND_DROP && id.equals(e.itemUuid)) {
                if (drop == null || e.tick < drop.tick) drop = e;
            }
        }
        if (drop == null) return;

        for (Entry e : STORE.entries()) {
            if (e.kind == KIND_PICKUP && id.equals(e.itemUuid)) return;
            if (e.kind == KIND_DESTROY && id.equals(e.itemUuid)) return;
        }

        Entry e = new Entry();
        e.kind = KIND_DESTROY;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = drop.visit;
        e.player = "";
        e.dim = drop.dim;
        e.x = item.getX();
        e.y = item.getY();
        e.z = item.getZ();
        e.stack = drop.stack;
        e.itemUuid = id;
        STORE.entries().add(e);
        STORE.markDirty();
    }

    public static void recordPickup(ItemEntity item, Player player, ItemStack picked) {
        if (!TimeTravel.isLooping || applying) return;
        if (!(item.level() instanceof ServerLevel level)) return;
        if (!(player instanceof ServerPlayer sp)) return;
        if (Clones.is(sp)) return;
        if (TimeTravel.timelineSceneManager == null) return;
        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(sp.getName().getString());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

        Entry e = new Entry();
        e.kind = KIND_PICKUP;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.player = sp.getName().getString().intern();
        e.dim = level.dimension().location().toString().intern();
        e.x = item.getX();
        e.y = item.getY();
        e.z = item.getZ();
        e.stack = picked.save(TimeTravel.server.registryAccess()).toString();
        e.itemUuid = item.getUUID();
        e.originTick = e.tick - item.getAge();
        STORE.entries().add(e);
        tracked.add(e.itemUuid);
        STORE.markDirty();
    }

    private static void scheduleGhost(ServerLevel level, Entry e) {
        if (e.itemUuid == null || ghosts.size() >= GHOST_LIMIT) return;
        for (Ghost g : ghosts) if (g.id.equals(e.itemUuid)) return;

        int cx = Mth.floor(e.x) >> 4;
        int cz = Mth.floor(e.z) >> 4;
        long chunkKey = ChunkPos.asLong(cx, cz);
        String key = e.dim + "|" + chunkKey;
        boolean held = false;
        try {
            Integer n = forcedByUs.get(key);
            if (n != null) {
                forcedByUs.put(key, n + 1);
                held = true;
            } else if (!level.getForcedChunks().contains(chunkKey)) {
                level.setChunkForced(cx, cz, true);
                forcedByUs.put(key, 1);
                held = true;
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to force-load chunk for ghost item {}", e.itemUuid, ex);
            return;
        }
        long now = TimeTravel.timelineTick;
        ghosts.add(new Ghost(e.dim, e.itemUuid, e.tick, now + GHOST_TIMEOUT_TICKS, cx, cz, key, held));
    }

    private static void processGhosts() {
        if (ghosts.isEmpty() || TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        for (Iterator<Ghost> it = ghosts.iterator(); it.hasNext(); ) {
            Ghost g = it.next();
            boolean done = now >= g.dropTick || now >= g.deadline;
            if (!done) {
                ServerLevel level = TimeTravel.server.getLevel(Levels.key(g.dim));
                if (level == null) {
                    done = true;
                } else {
                    Entity item = level.getEntity(g.id);
                    if (item != null) {
                        if (!item.isRemoved()) item.discard();
                        done = true;
                    }
                }
            }
            if (done) {
                it.remove();
                releaseGhostChunk(g);
            }
        }
    }

    private static void releaseGhostChunk(Ghost g) {
        if (!g.held) return;
        g.held = false;
        Integer n = forcedByUs.get(g.key);
        if (n == null) return;
        if (n > 1) {
            forcedByUs.put(g.key, n - 1);
            return;
        }
        forcedByUs.remove(g.key);
        if (TimeTravel.server == null) return;
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(g.dim));
            if (level != null) level.setChunkForced(g.cx, g.cz, false);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to release forced chunk {}", g.key, ex);
        }
    }

    private static void releaseAllGhosts() {
        for (Ghost g : new ArrayList<>(ghosts)) releaseGhostChunk(g);
        ghosts.clear();
        forcedByUs.clear();
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        processGhosts();
        if (!toDiscard.isEmpty()) {
            for (ItemEntity i : toDiscard) if (!i.isRemoved()) i.discard();
            toDiscard.clear();
        }
        long now = TimeTravel.timelineTick;
        for (Iterator<Entry> it = retry.iterator(); it.hasNext(); ) {
            Entry r = it.next();
            ServerLevel rl = TimeTravel.server.getLevel(Levels.key(r.dim));
            if (rl == null || now - r.tick > 20 || replayPickup(rl, r)) it.remove();
        }
        while (!pending.isEmpty() && pending.peek().tick <= now) {
            Entry e = pending.poll();
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            if (e.kind == KIND_PICKUP) {
                if (!replayPickup(level, e)) retry.add(e);
            } else if (e.kind == KIND_DROP) {
                replayDrop(level, e);
            }
        }
    }

    private static boolean replayPickup(ServerLevel level, Entry e) {
        Entity item = findLoaded(level, e.itemUuid);
        if (item == null || item.isRemoved()) item = findNearby(level, e);
        if (item == null || item.isRemoved()) return false;
        applying = true;
        try {
            if (item instanceof ItemEntity ie && TimeTravel.entityOfVisit(e.visit) instanceof LivingEntity taker
                    && taker.level() == level) {
                taker.take(ie, ie.getItem().getCount());
            }
            item.discard();
        } finally {
            applying = false;
        }
        return true;
    }

    private static ItemEntity findNearby(ServerLevel level, Entry e) {
        try {
            ItemStack stack = parseStack(e.stack);
            if (stack.isEmpty()) return null;
            AABB box = new AABB(e.x, e.y, e.z, e.x, e.y, e.z).inflate(4.0);
            ItemEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (ItemEntity c : level.getEntitiesOfClass(ItemEntity.class, box)) {
                if (c.isRemoved() || !ItemStack.isSameItemSameComponents(c.getItem(), stack)) continue;
                double d = c.distanceToSqr(e.x, e.y, e.z);
                if (d < bestDist) {
                    best = c;
                    bestDist = d;
                }
            }
            return best;
        } catch (Exception ex) {
            return null;
        }
    }

    private static ItemStack parseStack(String nbt) throws Exception {
        CompoundTag tag = TagParser.parseTag(nbt);
        return ItemStack.parse(TimeTravel.server.registryAccess(), tag).orElse(ItemStack.EMPTY);
    }

    private static long originOf(Entry pickup) {
        if (pickup.originTick != Long.MIN_VALUE) return pickup.originTick;
        if (pickup.itemUuid == null) return Long.MIN_VALUE;
        for (Entry d : STORE.entries()) {
            if (d.kind == KIND_DROP && pickup.itemUuid.equals(d.itemUuid)) return d.tick;
        }
        return Long.MIN_VALUE;
    }

    public static void onJump(long from, long target) {
        if (TimeTravel.server == null) { rebuildUpcoming(); return; }
        toDiscard.clear();
        releaseAllGhosts();

        if (target < from) {
            for (Entry e : STORE.entries()) {
                if (e.tick <= target) continue;
                ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
                if (level == null) continue;

                if (e.kind == KIND_DROP) {
                    Entity item = findLoaded(level, e.itemUuid);
                    if (item != null && !item.isRemoved()) item.discard();
                    else if (item == null && e.tick <= from && from - e.tick < ITEM_LIFESPAN) scheduleGhost(level, e);
                } else if (e.kind == KIND_PICKUP) {
                    long origin = originOf(e);
                    long age = origin == Long.MIN_VALUE ? 0 : target - origin;
                    if (age < 0 || age >= ITEM_LIFESPAN) continue;
                    Entity item = findLoaded(level, e.itemUuid);
                    if (item != null && !item.isRemoved()) continue;
                    applying = true;
                    try {
                        ItemStack stack = parseStack(e.stack);
                        if (!stack.isEmpty()) {
                            ItemEntity ni = new ItemEntity(level, e.x, e.y, e.z, stack);
                            ni.setUUID(e.itemUuid);
                            ni.setDeltaMovement(Vec3.ZERO);
                            ni.setPickUpDelay(10);
                            ((ItemEntityAccessor) ni).tt$setAge((int) age);
                            level.addFreshEntity(ni);
                        }
                    } catch (Exception ex) {
                        TimeTravel.TIMELINE_LOGGER.error("Failed to restore picked item", ex);
                    } finally {
                        applying = false;
                    }
                } else if (e.kind == KIND_DESTROY) {
                    Entry drop = null;
                    for (Entry d : STORE.entries()) {
                        if (d.kind == KIND_DROP && e.itemUuid.equals(d.itemUuid) && d.tick <= target) {
                            if (drop == null || d.tick < drop.tick) drop = d;
                        }
                    }
                    if (drop == null) continue;
                    long age = target - drop.tick;
                    if (age < 0 || age >= ITEM_LIFESPAN) continue;
                    Entity item = findLoaded(level, e.itemUuid);
                    if (item != null && !item.isRemoved()) continue;
                    applying = true;
                    try {
                        ItemStack stack = parseStack(drop.stack);
                        if (!stack.isEmpty()) {
                            ItemEntity ni = new ItemEntity(level, drop.x, drop.y, drop.z, stack);
                            ni.setUUID(drop.itemUuid);
                            ni.setDeltaMovement(Vec3.ZERO);
                            ni.setPickUpDelay(10);
                            ((ItemEntityAccessor) ni).tt$setAge((int) age);
                            level.addFreshEntity(ni);
                        }
                    } catch (Exception ex) {
                        TimeTravel.TIMELINE_LOGGER.error("Failed to restore destroyed item", ex);
                    } finally {
                        applying = false;
                    }
                }
            }
        } else if (target > from) {
            Map<UUID, Entry> earliestDrop = new HashMap<>();
            Map<UUID, Long> earliestPickup = new HashMap<>();
            Map<UUID, Long> earliestDestroy = new HashMap<>();
            for (Entry e : STORE.entries()) {
                if (e.tick > target) continue;
                if (e.kind == KIND_DROP) {
                    earliestDrop.merge(e.itemUuid, e, (a, b) -> a.tick <= b.tick ? a : b);
                } else if (e.kind == KIND_PICKUP) {
                    earliestPickup.merge(e.itemUuid, e.tick, Math::min);
                } else if (e.kind == KIND_DESTROY) {
                    earliestDestroy.merge(e.itemUuid, e.tick, Math::min);
                }
            }
            for (Entry drop : earliestDrop.values()) {
                if (earliestPickup.containsKey(drop.itemUuid)) continue;
                Long destroyTick = earliestDestroy.get(drop.itemUuid);
                if (destroyTick != null && destroyTick <= target) continue;
                long age = target - drop.tick;
                if (age >= ITEM_LIFESPAN) continue;
                ServerLevel level = TimeTravel.server.getLevel(Levels.key(drop.dim));
                if (level == null) continue;
                if (findLoaded(level, drop.itemUuid) != null) continue;
                replayDrop(level, drop, (int) age);
            }
            for (Entry e : STORE.entries()) {
                if (e.kind != KIND_PICKUP || e.tick > target) continue;
                ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
                if (level == null) continue;
                Entity item = findLoaded(level, e.itemUuid);
                if (item == null || item.isRemoved()) item = findNearby(level, e);
                if (item != null && !item.isRemoved()) item.discard();
            }
            for (Entry e : STORE.entries()) {
                if (e.kind != KIND_DESTROY || e.tick > target) continue;
                ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
                if (level == null) continue;
                Entity item = findLoaded(level, e.itemUuid);
                if (item != null && !item.isRemoved()) item.discard();
            }
        }
        rebuildUpcoming();
    }

    private static void replayDrop(ServerLevel level, Entry e) {
        replayDrop(level, e, 0);
    }

    private static void replayDrop(ServerLevel level, Entry e, int age) {
        if (findLoaded(level, e.itemUuid) != null) return;
        applying = true;
        try {
            ItemStack stack = parseStack(e.stack);
            if (stack.isEmpty()) return;
            ItemEntity item = new ItemEntity(level, e.x, e.y, e.z, stack);
            item.setUUID(e.itemUuid);
            tracked.add(e.itemUuid);
            item.setPickUpDelay(e.pickupDelay > 0 ? e.pickupDelay : 10);
            item.setDeltaMovement(age > 0 ? Vec3.ZERO : new Vec3(e.vx, e.vy, e.vz));
            if (age > 0) ((ItemEntityAccessor) item).tt$setAge(Math.min(age, ITEM_LIFESPAN - 1));
            level.addFreshEntity(item);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay drop", ex);
        } finally {
            applying = false;
        }
    }

    private static Entity findLoaded(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid);
    }

    private static void rebuildTracked() {
        tracked.clear();
        for (Entry e : STORE.entries()) {
            if (e.itemUuid != null) tracked.add(e.itemUuid);
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        rebuildTracked();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        pending.clear();
        retry.clear();
        toDiscard.clear();
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            if (e.tick > now) pending.add(e);
        }
    }

    public static void load() {
        STORE.load();
        rebuildTracked();
        for (Entry e : STORE.entries()) ReplayState.observe(e.tick);
        rebuildUpcoming();
    }

    public static void save() {
        releaseAllGhosts();
        STORE.save();
    }
    public static void saveIfDirty() { STORE.saveIfDirty(); }

    public static void clear() {
        pending.clear();
        retry.clear();
        toDiscard.clear();
        tracked.clear();
        replayDepth = 0;
        releaseAllGhosts();
        STORE.clear();
    }
}