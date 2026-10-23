package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.time.TimeContext;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Records the initial state of every mob
 */
public final class MobSpawnJournal {
    private MobSpawnJournal() {}

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit;
        public UUID uuid;
        public String dim;
        public String nbt;
        public double x, y, z;
        public boolean outOfTime;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_mob_spawns.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, Entry> canon = new HashMap<>();
    private static final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));

    static void registerInitial(ServerLevel level, Mob mob, CompoundTag tag, long tick) {
        sanitize(tag);
        if (canon.containsKey(mob.getUUID())) return;
        if (!TimeTravel.isLooping) return;

        PlayerData pd = BlockJournal.nearestRecorder(level, mob.blockPosition());

        Entry e = new Entry();
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = mob.getUUID();
        e.dim = Levels.canonicalId(level);
        e.nbt = tag.toString();
        e.x = mob.getX();
        e.y = mob.getY();
        e.z = mob.getZ();
        STORE.entries().add(e);
        canon.put(e.uuid, e);
        STORE.markDirty();
    }

    public static void snapshotInitial() {
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob)) continue;
                if (Clones.is(mob)) continue;
                if (canon.containsKey(mob.getUUID())) continue;
                CompoundTag tag = new CompoundTag();
                if (!mob.save(tag)) continue;
                registerInitial(level, mob, tag, -1L);
            }
        }
        rebuildUpcoming();
    }

    public static void onSpawn(ServerLevel level, Entity entity) {
        if (!TimeTravel.isLooping) return;
        if (!(entity instanceof Mob mob)) return;
        if (Clones.is(mob)) return;
        if (canon.containsKey(mob.getUUID())) return;

        CompoundTag tag = new CompoundTag();
        if (!mob.save(tag)) return;
        sanitize(tag);

        PlayerData pd = BlockJournal.nearestRecorder(level, mob.blockPosition());

        Entry e = new Entry();
        e.tick = TimeContext.now();
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = mob.getUUID();
        e.dim = Levels.canonicalId(level);
        e.nbt = tag.toString();
        e.x = mob.getX();
        e.y = mob.getY();
        e.z = mob.getZ();
        e.outOfTime = ReplayState.isReplaying();
        STORE.entries().add(e);
        canon.put(e.uuid, e);
        STORE.markDirty();
    }

    public static long spawnTick(UUID id) {
        Entry e = canon.get(id);
        return e == null ? -1 : e.tick;
    }

    public static boolean isOutOfTime(UUID id) {
        Entry e = canon.get(id);
        return e != null && e.outOfTime;
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        long now = TimeContext.now();
        while (!pending.isEmpty() && pending.peek().tick <= now) {
            Entry e = pending.poll();
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            if (MobEventJournal.hasDeathAtOrBefore(e.uuid, now)) continue;
            if (findLoaded(level, e.uuid) != null) continue;
            revive(level, e);
        }
    }

    static void ensureRegistered(ServerLevel level, Mob mob) {
        if (canon.containsKey(mob.getUUID())) return;
        CompoundTag tag = new CompoundTag();
        if (!mob.save(tag)) return;
        registerInitial(level, mob, tag, -1L);
    }

    public static void onJump(long from, long to) {
        long now = TimeContext.now();
        for (Entry e : STORE.entries()) {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;

            boolean shouldExist = e.tick <= now && !MobEventJournal.hasDeathAtOrBefore(e.uuid, now);
            Entity current = findLoaded(level, e.uuid);

            if (!shouldExist) {
                if (current != null && !current.isRemoved()) current.discard();
                continue;
            }
            if (current != null && !current.isRemoved() && current instanceof LivingEntity living
                    && (living.isDeadOrDying() || living.deathTime > 0)) {
                current.discard();
                current = null;
            }

            if (current == null || current.isRemoved()) {
                revive(level, e);
            }
        }
        rebuildUpcoming();
    }

    private static Entity findLoaded(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid);
    }

    private static void revive(ServerLevel level, Entry e) {
        try {
            Entity stale = findLoaded(level, e.uuid);
            if (stale != null && !stale.isRemoved()) stale.discard();

            CompoundTag tag = TagParser.parseTag(e.nbt);
            sanitize(tag);
            Entity revived = EntityType.loadEntityRecursive(tag, level, entity -> entity);
            if (revived == null) return;
            Vec3 at = MobTrailJournal.positionAt(e.uuid, TimeContext.now());
            if (at == null) at = new Vec3(e.x, e.y, e.z);
            revived.moveTo(at.x, at.y, at.z, revived.getYRot(), revived.getXRot());
            level.addFreshEntity(revived);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to revive mob {}", e.uuid, ex);
        }
    }

    private static void sanitize(CompoundTag tag) {
        if (tag.contains("Health") && tag.getFloat("Health") <= 0) tag.remove("Health");
        tag.putShort("DeathTime", (short) 0);
        tag.putShort("HurtTime", (short) 0);
        tag.putInt("DragonDeathTime", 0);
        tag.remove("DeathLootTable");
        tag.remove("DeathLootTableSeed");
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        if (TimeTravel.server != null) {
            for (Entry e : STORE.entries()) {
                if (!e.outOfTime || !visitIds.contains(e.visit)) continue;
                ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
                if (level == null) continue;
                Entity current = findLoaded(level, e.uuid);
                if (current != null && !current.isRemoved()) current.discard();
            }
        }
        STORE.entries().removeIf(e -> visitIds.contains(e.visit));
        STORE.removeUpcoming(e -> visitIds.contains(e.visit));
        canon.values().removeIf(e -> visitIds.contains(e.visit));
        STORE.markDirty();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        pending.clear();
        long now = TimeContext.now();
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
