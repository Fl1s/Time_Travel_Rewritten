package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.Levels;
import com.fl1s.rewritten.timetravel.util.Removal;
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
    private VehicleSpawnJournal() {}

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit;
        public UUID uuid;
        public String dim;
        public String nbt;
        public double x, y, z;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_vehicle_spawns.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, Entry> canon = new HashMap<>();
    private static final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));

    public static void snapshotInitial() {
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!VehicleJournal.isTracked(entity)) continue;
                if (Ghosts.is(entity)) continue;
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
        e.dim = level.dimension().location().toString();
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
        if (Ghosts.is(entity)) return;
        if (canon.containsKey(entity.getUUID())) return;
        register(level, entity, TimeTravel.timelineTick);
    }

    private static void register(ServerLevel level, Entity entity, long tick) {
        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return;

        PlayerData pd = BlockJournal.nearestRecorder(level, entity.blockPosition());

        Entry e = new Entry();
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = entity.getUUID();
        e.dim = level.dimension().location().toString();
        e.nbt = tag.toString();
        e.x = entity.getX();
        e.y = entity.getY();
        e.z = entity.getZ();
        STORE.entries().add(e);
        canon.put(e.uuid, e);
        STORE.markDirty();
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        long now = TimeTravel.timelineTick;
        while (!pending.isEmpty() && pending.peek().tick <= now) {
            Entry e = pending.poll();
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            if (MobEventJournal.hasDeathAtOrBefore(e.uuid, now)) continue;
            if (findLoaded(level, e.uuid) != null) continue;
            revive(level, e, now);
        }
    }

    public static void onJump(long from, long to) {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;

        for (Entry e : STORE.entries()) {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;

            boolean shouldExist = e.tick <= now && !MobEventJournal.hasDeathAtOrBefore(e.uuid, now);
            Entity current = findLoaded(level, e.uuid);

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
        for (Entity e : level.getAllEntities()) {
            if (e.getUUID().equals(uuid)) return e;
        }
        return null;
    }

    private static void revive(ServerLevel level, Entry e, long now) {
        try {
            CompoundTag tag = TagParser.parseTag(e.nbt);
            Entity revived = EntityType.loadEntityRecursive(tag, level, entity -> entity);
            if (revived == null) return;
            Vec3 at = VehicleTrailJournal.positionAt(e.uuid, now);
            if (at == null) at = new Vec3(e.x, e.y, e.z);
            revived.moveTo(at.x, at.y, at.z, revived.getYRot(), revived.getXRot());
            level.addFreshEntity(revived);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to revive vehicle {}", e.uuid, ex);
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.entries().removeIf(e -> visitIds.contains(e.visit));
        STORE.upcoming().removeIf(e -> visitIds.contains(e.visit));
        canon.values().removeIf(e -> visitIds.contains(e.visit));
        STORE.markDirty();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        pending.clear();
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            if (e.tick > now) pending.add(e);
        }
    }

    public static void load() {
        canon.clear();
        STORE.load();
        for (Entry e : STORE.entries()) canon.put(e.uuid, e);
        rebuildUpcoming();
    }

    public static void save() { STORE.save(); }
    public static void saveIfDirty() { STORE.saveIfDirty(); }

    public static void clear() {
        canon.clear();
        pending.clear();
        STORE.clear();
    }
}
