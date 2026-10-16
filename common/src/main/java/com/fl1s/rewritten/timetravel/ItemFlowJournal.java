package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Records mob death loot and player pickups, then replays both at their
 * recorded ticks.
 */
public final class ItemFlowJournal {
    private ItemFlowJournal() {}

    public static final int KIND_PICKUP = 0;
    public static final int KIND_DROP = 1;

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
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_item_flow.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static final ArrayList<Entry> retry = new ArrayList<>();

    private static boolean applying = false;
    private static boolean suppressDrop = false;
    private static LivingEntity capturingEntity = null;

    public static boolean isApplying() { return applying; }
    public static boolean isSuppressDrop() { return suppressDrop; }
    public static void setSuppressDrop(boolean v) { suppressDrop = v; }
    public static boolean isCapturingDeath() { return capturingEntity != null; }
    public static void beginDeathCapture(LivingEntity e) { capturingEntity = e; }
    public static void endDeathCapture() { capturingEntity = null; }

    public static boolean isGhostKill() {
        return capturingEntity != null && killedByGhost(capturingEntity);
    }

    private static boolean killedByGhost(LivingEntity victim) {
        DamageSource src = victim.getLastDamageSource();
        if (src == null) return false;
        Entity attacker = src.getEntity();
        Entity direct = src.getDirectEntity();
        return (attacker != null && Ghosts.is(attacker)) || (direct != null && Ghosts.is(direct));
    }

    public static void onEntitySpawn(ServerLevel level, Entity entity) {
        if (!(entity instanceof ItemEntity item)) return;
        if (capturingEntity == null) return;
        if (capturingEntity.level() != level) return;
        if (MobJournal.isKilling() || MobJournal.isApplying()) return;
        if (killedByGhost(capturingEntity)) return;
        recordDrop(item, level, item.getX(), item.getY(), item.getZ());
    }

    public static void recordPickup(ItemEntity item, Player player, ItemStack picked) {
        if (!TimeTravel.isLooping || applying) return;
        if (!(item.level() instanceof ServerLevel level)) return;
        if (!(player instanceof ServerPlayer sp)) return;
        if (Ghosts.is(sp)) return;
        if (TimeTravel.loopSceneManager == null) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(sp.getName().getString());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

        Entry e = new Entry();
        e.kind = KIND_PICKUP;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.player = sp.getName().getString();
        e.dim = level.dimension().location().toString();
        e.x = item.getX();
        e.y = item.getY();
        e.z = item.getZ();
        e.stack = picked.save(TimeTravel.server.registryAccess()).toString();
        e.itemUuid = item.getUUID();
        e.originTick = e.tick - item.getAge();
        STORE.entries().add(e);
        STORE.markDirty();
    }

    private static void recordDrop(ItemEntity item, ServerLevel level, double x, double y, double z) {
        PlayerData pd = BlockJournal.nearestRecorder(level, BlockPos.containing(x, y, z));
        if (pd == null || pd.getCurrentVisit() == null) return;

        Entry e = new Entry();
        e.kind = KIND_DROP;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.player = "";
        e.dim = level.dimension().location().toString();
        e.x = x;
        e.y = y;
        e.z = z;
        e.stack = item.getItem().save(TimeTravel.server.registryAccess()).toString();
        e.itemUuid = item.getUUID();
        STORE.entries().add(e);
        STORE.markDirty();
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
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
            CompoundTag tag = TagParser.parseTag(e.stack);
            ItemStack stack = ItemStack.parse(TimeTravel.server.registryAccess(), tag).orElse(ItemStack.EMPTY);
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

    private static void replayDrop(ServerLevel level, Entry e) {
        if (findLoaded(level, e.itemUuid) != null) return;
        applying = true;
        try {
            CompoundTag tag = TagParser.parseTag(e.stack);
            ItemStack stack = ItemStack.parse(TimeTravel.server.registryAccess(), tag).orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) return;
            ItemEntity item = new ItemEntity(level, e.x, e.y, e.z, stack);
            item.setUUID(e.itemUuid);
            item.setPickUpDelay(10);
            item.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(item);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to replay drop", ex);
        } finally {
            applying = false;
        }
    }

    private static Entity findLoaded(ServerLevel level, UUID uuid) {
        for (Entity e : level.getAllEntities()) {
            if (e.getUUID().equals(uuid)) return e;
        }
        return null;
    }

    public static void onJump(long from, long to) {
        if (to < from && TimeTravel.server != null) {
            long target = to;
            for (Entry e : STORE.entries()) {
                if (e.tick <= target) continue;
                ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
                if (level == null) continue;

                if (e.kind == KIND_DROP) {
                    Entity item = findLoaded(level, e.itemUuid);
                    if (item != null && !item.isRemoved()) item.discard();
                } else if (e.kind == KIND_PICKUP && e.originTick <= target) {
                    Entity item = findLoaded(level, e.itemUuid);
                    if (item == null || item.isRemoved()) {
                        try {
                            CompoundTag tag = TagParser.parseTag(e.stack);
                            ItemStack stack = ItemStack.parse(TimeTravel.server.registryAccess(), tag)
                                    .orElse(ItemStack.EMPTY);
                            if (!stack.isEmpty()) {
                                ItemEntity newItem = new ItemEntity(level, e.x, e.y, e.z, stack);
                                newItem.setUUID(e.itemUuid);
                                newItem.setDeltaMovement(Vec3.ZERO);
                                level.addFreshEntity(newItem);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        pending.clear();
        retry.clear();
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            if (e.tick > now) pending.add(e);
        }
    }

    public static void load() {
        STORE.load();
        rebuildUpcoming();
    }

    public static void save() { STORE.save(); }
    public static void saveIfDirty() { STORE.saveIfDirty(); }

    public static void clear() {
        pending.clear();
        retry.clear();
        STORE.clear();
    }
}
