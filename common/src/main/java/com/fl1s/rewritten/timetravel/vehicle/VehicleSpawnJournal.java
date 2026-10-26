package com.fl1s.rewritten.timetravel.vehicle;

import com.fl1s.rewritten.timetravel.time.TimeContext;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.mob.MobEventJournal;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.data.Removal;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import net.minecraft.server.level.ServerPlayer;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Records the initial state of every vehicle ({@code AbstractMinecart} and
 * {@code AbstractBoat}) as a full NBT snapshot plus spawn tick.
 */
public final class VehicleSpawnJournal {
    private VehicleSpawnJournal() {
    }

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit;
        public UUID uuid;
        public String dim;
        public String nbt;
        public double x, y, z;
        public long endTick = -1;
        public int endVisit = -1;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_vehicle_spawns.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static boolean reviving = false;

    public static boolean shouldBlockReplaySpawn(Entity e) {
        if (reviving || !TimeTravel.isLooping || !ReplayState.isReplaying()) return false;
        if (!VehicleJournal.isTracked(e) || Clones.is(e)) return false;
        if (!(e.level() instanceof ServerLevel level)) return false;
        boolean clone = false, real = false;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(e) > 36.0) continue;
            if (Clones.is(p)) clone = true;
            else real = true;
        }
        return clone && !real;
    }

    private static final Map<UUID, Entry> canon = new HashMap<>();
    private static final PriorityQueue<Entry> pending = new PriorityQueue<>(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static final PriorityQueue<Entry> pendingRemovals = new PriorityQueue<>(Comparator.comparingLong((Entry e) -> e.endTick).thenComparingLong(e -> e.seq));

    public static void snapshotInitial() {
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!VehicleJournal.isTracked(entity)) continue;
                if (Clones.is(entity) || Exempt.is(entity)) continue;
                if (canon.containsKey(entity.getUUID())) continue;
                register(level, entity, -1L);
            }
        }
        rebuildUpcoming();
    }

    static void registerInitial(ServerLevel level, Entity entity, CompoundTag tag, long tick) {
        if (canon.containsKey(entity.getUUID())) return;
        if (!TimeTravel.isLooping) return;

        PlayerData pd = BlockJournal.nearestRecorder(level, entity.blockPosition());

        Entry e = new Entry();
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = entity.getUUID();
        e.dim = Levels.canonicalId(level);
        e.nbt = tag.toString();
        e.x = entity.getX();
        e.y = entity.getY();
        e.z = entity.getZ();
        STORE.entries().add(e);
        canon.put(e.uuid, e);
        STORE.markDirty();
    }

    public static void onSpawn(ServerLevel level, Entity entity) {
        if (!TimeTravel.isLooping) return;
        if (!VehicleJournal.isTracked(entity)) return;
        if (Clones.is(entity) || Exempt.is(entity)) return;
        if (canon.containsKey(entity.getUUID())) return;
        register(level, entity, TimeContext.now());
    }

    private static void register(ServerLevel level, Entity entity, long tick) {
        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return;
        Exempt.strip(tag);

        PlayerData pd = BlockJournal.nearestRecorder(level, entity.blockPosition());

        Entry e = new Entry();
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = entity.getUUID();
        e.dim = Levels.canonicalId(level);
        e.nbt = tag.toString();
        e.x = entity.getX();
        e.y = entity.getY();
        e.z = entity.getZ();
        STORE.entries().add(e);
        canon.put(e.uuid, e);
        STORE.markDirty();
    }

    private static boolean ended(Entry e, long now) {
        return e.endTick >= 0 && e.endTick <= now;
    }

    public static boolean isRecorded(UUID id) {
        return canon.containsKey(id);
    }

    public static java.util.Collection<Entry> recorded() {
        return canon.values();
    }

    public static boolean isProtected(UUID id, long now) {
        Entry e = canon.get(id);
        return e != null && e.endTick > now;
    }

    public static void onDestroyed(Entity entity) {
        if (!TimeTravel.isLooping || Removal.isSilent() || Clones.is(entity) || Exempt.is(entity)) return;
        if (!(entity.level() instanceof ServerLevel level)) return;
        Entry e = canon.get(entity.getUUID());
        if (e == null) return;
        long now = TimeContext.now();
        if (ended(e, now)) return;

        PlayerData pd = BlockJournal.nearestRecorder(level, entity.blockPosition());
        e.endTick = now;
        e.endVisit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        STORE.markDirty();
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        long now = TimeContext.now();
        while (!pendingRemovals.isEmpty() && pendingRemovals.peek().endTick <= now) {
            Entry e = pendingRemovals.poll();
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            Entity current = findLoaded(level, e.uuid);
            if (current != null && !current.isRemoved() && !Exempt.is(current)) Removal.discardWithoutDrops(current);
        }
        while (!pending.isEmpty() && pending.peek().tick <= now) {
            Entry e = pending.poll();
            if (ended(e, now)) continue;
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            if (MobEventJournal.hasDeathAtOrBefore(e.uuid, now)) continue;
            if (findLoaded(level, e.uuid) != null) continue;
            revive(level, e, now);
        }
    }

    public static void onJump(long from, long to) {
        if (TimeTravel.server == null) return;
        long now = TimeContext.now();

        for (Entry e : STORE.entries()) {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;

            boolean shouldExist = e.tick <= now && !ended(e, now) && !MobEventJournal.hasDeathAtOrBefore(e.uuid, now);
            Entity current = findLoaded(level, e.uuid);
            if (Exempt.is(current)) continue;

            if (!shouldExist) {
                if (current != null && !current.isRemoved()) Removal.discardWithoutDrops(current);
                continue;
            }

            if (current == null || current.isRemoved()) {
                revive(level, e, now);
            } else {
                Vec3 at = VehicleTrailJournal.positionAt(e.uuid, now);
                if (at != null) {
                    current.moveTo(at.x, at.y, at.z, current.getYRot(), current.getXRot());
                    current.setDeltaMovement(Vec3.ZERO);
                }
            }
        }
        rebuildUpcoming();
        VehicleTrailJournal.apply();
    }

    private static Entity findLoaded(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid);
    }

    private static void revive(ServerLevel level, Entry e, long now) {
        try {
            CompoundTag tag = TagParser.parseTag(e.nbt);
            Exempt.strip(tag);
            Entity revived = EntityType.loadEntityRecursive(tag, level, entity -> entity);
            if (revived == null || Exempt.is(revived)) return;
            revived.setUUID(e.uuid);
            Vec3 at = VehicleTrailJournal.positionAt(e.uuid, now);
            if (at == null) at = new Vec3(e.x, e.y, e.z);
            revived.moveTo(at.x, at.y, at.z, revived.getYRot(), revived.getXRot());
            reviving = true;
            try {
                level.addFreshEntity(revived);
            } finally {
                reviving = false;
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to revive vehicle {}", e.uuid, ex);
        }
    }

    public static void forget(UUID id) {
        if (canon.remove(id) == null) return;
        STORE.entries().removeIf(e -> id.equals(e.uuid));
        STORE.removeUpcoming(e -> id.equals(e.uuid));
        pending.removeIf(e -> id.equals(e.uuid));
        pendingRemovals.removeIf(e -> id.equals(e.uuid));
        STORE.markDirty();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        for (Entry e : STORE.entries()) {
            if (visitIds.contains(e.endVisit)) {
                e.endTick = -1;
                e.endVisit = -1;
            }
        }
        STORE.entries().removeIf(e -> visitIds.contains(e.visit));
        STORE.removeUpcoming(e -> visitIds.contains(e.visit));
        canon.values().removeIf(e -> visitIds.contains(e.visit));
        STORE.markDirty();
        rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        pending.clear();
        pendingRemovals.clear();
        long now = TimeContext.now();
        for (Entry e : STORE.entries()) {
            if (e.tick > now) pending.add(e);
            if (e.endTick > now) pendingRemovals.add(e);
        }
    }

    public static void load() {
        canon.clear();
        STORE.load();
        for (Entry e : STORE.entries()) canon.put(e.uuid, e);
        rebuildUpcoming();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        canon.clear();
        pending.clear();
        pendingRemovals.clear();
        STORE.clear();
    }
}
