package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.compat.ironsspells.IrSpellsCompat;
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

import java.util.*;

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
        public long despawnTick = -1;
        public int despawnVisit = -1;
    }

    private static final PriorityQueue<Entry> pendingDespawns = new PriorityQueue<>(Comparator.comparingLong((Entry e) -> e.despawnTick).thenComparingLong(e -> e.seq));

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_mob_spawns.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, Entry> canon = new HashMap<>();
    private static final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));

    private static boolean removingForReplay = false;

    static void registerInitial(ServerLevel level, Mob mob, CompoundTag tag, long tick) {
        sanitize(tag);
        IrSpellsCompat.onRecord(mob, tag);
        if (canon.containsKey(mob.getUUID())) return;
        if (!TimeTravel.isLooping) return;

        PlayerData pd = BlockJournal.nearestRecorder(level, mob.blockPosition());

        Entry e = new Entry();
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = mob.getUUID();
        e.dim = level.dimension().location().toString();
        e.nbt = tag.toString();
        e.x = mob.getX();
        e.y = mob.getY();
        e.z = mob.getZ();
        STORE.entries().add(e);
        canon.put(e.uuid, e);
        STORE.markDirty();
        if (e.despawnTick > TimeTravel.timelineTick) {
            pendingDespawns.add(e);
        }
    }

    public static void snapshotInitial() {
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob)) continue;
                if (Clones.is(mob)) continue;
                if (Clones.isVisualOnly(mob)) continue;
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
        if (Clones.isVisualOnly(mob)) return;
        if (canon.containsKey(mob.getUUID())) return;

        CompoundTag tag = new CompoundTag();
        if (!mob.save(tag)) return;
        sanitize(tag);
        IrSpellsCompat.onRecord(mob, tag);

        PlayerData pd = BlockJournal.nearestRecorder(level, mob.blockPosition());

        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = mob.getUUID();
        e.dim = level.dimension().location().toString();
        e.nbt = tag.toString();
        e.x = mob.getX();
        e.y = mob.getY();
        e.z = mob.getZ();
        e.outOfTime = ReplayState.isReplaying();
        STORE.entries().add(e);
        canon.put(e.uuid, e);
        STORE.markDirty();
    }

    public static void onRemoved(Entity entity, Entity.RemovalReason reason) {
        if (!TimeTravel.isLooping || ReplayState.SUPPRESS) return;
        if (removingForReplay) return;
        if (!(entity instanceof Mob mob)) return;
        if (reason == null) return;
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) return;
        if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) return;
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION) return;
        if (mob.getTags().contains("tt_puppet")) return;
        if (mob.getTags().contains(com.fl1s.rewritten.timetravel.entity.EntityJournal.TAG)) return;

        Entry e = canon.get(mob.getUUID());
        if (e == null) return;
        if (e.despawnTick >= 0) return;
        if (reason == Entity.RemovalReason.KILLED && MobEventJournal.deathOf(mob.getUUID()) != null) return;

        e.despawnTick = TimeTravel.timelineTick;
        if (mob.level() instanceof ServerLevel level) {
            PlayerData pd = BlockJournal.nearestRecorder(level, mob.blockPosition());
            e.despawnVisit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        }
        STORE.markDirty();
        if (TimeTravel.DEBUG) {
            TimeTravel.TIMELINE_LOGGER.info("[TT-SPAWN] despawnTick={} for {} ({}) reason={}",
                    e.despawnTick, mob.getName().getString(), mob.getUUID(), reason);
        }
    }

    public static long spawnTick(UUID id) {
        Entry e = canon.get(id);
        return e == null ? -1 : e.tick;
    }

    public static boolean isOutOfTime(UUID id) {
        Entry e = canon.get(id);
        return e != null && e.outOfTime;
    }

    public static boolean shouldBeDespawned(UUID id, long now) {
        Entry e = canon.get(id);
        return e != null && e.despawnTick >= 0 && e.despawnTick <= now;
    }

    public static void discardForReplay(Entity entity) {
        if (entity == null || entity.isRemoved()) return;
        removingForReplay = true;
        try {
            entity.discard();
        } finally {
            removingForReplay = false;
        }
    }

    private static boolean isGoneAt(Entry e, long now) {
        if (MobEventJournal.hasDeathAtOrBefore(e.uuid, now)) return true;
        if (e.despawnTick >= 0 && e.despawnTick <= now) return true;
        return false;
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        long now = TimeTravel.timelineTick;

        List<Entry> deferred = new ArrayList<>();
        while (!pendingDespawns.isEmpty() && pendingDespawns.peek().despawnTick <= now) {
            Entry e = pendingDespawns.poll();
            if (e.tick > now) continue;
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            Entity current = findLoaded(level, e.uuid);
            if (current == null || current.isRemoved()) continue;
            if (!(current instanceof Mob m)) continue;
            if (MobTrailJournal.isPuppeted(m)) {
                deferred.add(e);
                continue;
            }
            discardForReplay(current);
        }
        pendingDespawns.addAll(deferred);

        while (!pending.isEmpty() && pending.peek().tick <= now) {
            Entry e = pending.poll();
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            if (isGoneAt(e, now)) continue;
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
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;

            boolean shouldExist = e.tick <= now && !isGoneAt(e, now);
            Entity current = findLoaded(level, e.uuid);

            if (!shouldExist) {
                if (current != null && !current.isRemoved()) discardForReplay(current);
                continue;
            }
            if (current != null && !current.isRemoved() && current instanceof LivingEntity living
                    && (living.isDeadOrDying() || living.deathTime > 0)) {
                discardForReplay(current);
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
            if (stale != null && !stale.isRemoved()) discardForReplay(stale);

            CompoundTag tag = TagParser.parseTag(e.nbt);
            sanitize(tag);
            Entity revived = EntityType.loadEntityRecursive(tag, level, entity -> entity);
            if (revived == null) return;
            revived.setUUID(e.uuid);
            Vec3 at = MobTrailJournal.positionAt(e.uuid, TimeTravel.timelineTick);
            if (at == null) at = new Vec3(e.x, e.y, e.z);
            revived.moveTo(at.x, at.y, at.z, revived.getYRot(), revived.getXRot());
            IrSpellsCompat.onRiseAnimation(revived);
            MobTrailJournal.markSpawned(revived);
            level.addFreshEntity(revived);
            IrSpellsCompat.onPuppetSpawned(revived, tag, level);
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
                if (current != null && !current.isRemoved()) discardForReplay(current);
            }
        }
        for (Entry e : STORE.entries()) {
            if (visitIds.contains(e.despawnVisit)) {
                e.despawnTick = -1;
                e.despawnVisit = -1;
            }
        }
        STORE.entries().removeIf(e -> visitIds.contains(e.visit));
        STORE.upcoming().removeIf(e -> visitIds.contains(e.visit));
        canon.values().removeIf(e -> visitIds.contains(e.visit));
        STORE.markDirty();
        rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        pending.clear();
        pendingDespawns.clear();
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            if (e.tick > now) pending.add(e);
            if (e.despawnTick > now) pendingDespawns.add(e);
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
        pendingDespawns.clear();
        STORE.clear();
    }
}
