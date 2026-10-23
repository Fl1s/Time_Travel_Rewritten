package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.compat.ironsspells.IrSpellsCompat;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.entity.EntityJournal;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ProjectileJournal {
    private ProjectileJournal() {
    }

    public static final int KIND_SPAWN = 0;
    public static final int KIND_SAMPLE = 1;
    public static final int KIND_END = 2;

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit;
        public int kind;
        public UUID uuid;
        public String dim;
        public String nbt;
        public double x, y, z;
        public double vx, vy, vz;
        public float yRot, xRot;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_projectiles.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Set<UUID> tracked = new HashSet<>();
    private static final Map<UUID, Entry> lastSamples = new HashMap<>();
    private static final Map<UUID, Puppet> puppets = new HashMap<>();
    private static Map<UUID, List<Entry>> trails;

    private static final class Puppet {
        Entity entity;
        UUID uuid;
        String dim;
    }

    private static boolean skip(Entity e) {
        if (e instanceof ThrownEnderpearl) return true;
        if (e instanceof FishingHook) return true;
        if (e instanceof FireworkRocketEntity) return true;
        if (Clones.isVisualOnly(e)) return true;
        if (IrSpellsCompat.isUntracked(e)) return true;
        return false;
    }

    public static void onSpawn(ServerLevel level, Entity entity) {
        if (!TimeTravel.isLooping || ReplayState.SUPPRESS) return;
        if (!(entity instanceof Projectile)) return;
        if (skip(entity)) return;
        if (Clones.is(entity)) return;
        if (tracked.contains(entity.getUUID())) return;

        PlayerData pd = BlockJournal.nearestRecorder(level, entity.blockPosition());
        if (pd == null || pd.getCurrentVisit() == null) return;

        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return;

        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.kind = KIND_SPAWN;
        e.uuid = entity.getUUID();
        e.dim = level.dimension().location().toString();
        e.nbt = tag.toString();
        e.x = entity.getX();
        e.y = entity.getY();
        e.z = entity.getZ();
        Vec3 v = entity.getDeltaMovement();
        e.vx = v.x;
        e.vy = v.y;
        e.vz = v.z;
        e.yRot = entity.getYRot();
        e.xRot = entity.getXRot();
        STORE.entries().add(e);
        tracked.add(e.uuid);
        STORE.markDirty();
    }

    public static void onRemoved(Entity entity) {
        if (!TimeTravel.isLooping || !(entity instanceof Projectile)) return;
        if (puppets.containsKey(entity.getUUID())) return;
        if (skip(entity)) return;
        if (!tracked.contains(entity.getUUID())) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = -1;
        e.kind = KIND_END;
        e.uuid = entity.getUUID();
        e.dim = level.dimension().location().toString();
        STORE.entries().add(e);
        tracked.remove(entity.getUUID());
        lastSamples.remove(entity.getUUID());
        STORE.markDirty();
    }

    private static void sample() {
        if (TimeTravel.server == null || tracked.isEmpty()) return;
        long now = TimeTravel.timelineTick;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof Projectile)) continue;
                if (skip(e)) continue;
                if (Clones.is(e)) continue;
                if (!tracked.contains(e.getUUID())) continue;

                Entry last = lastSamples.get(e.getUUID());
                if (last != null && Math.abs(last.x - e.getX()) < 0.02 && Math.abs(last.y - e.getY()) < 0.02 && Math.abs(last.z - e.getZ()) < 0.02 && Math.abs(last.vx - e.getDeltaMovement().x) < 0.02) {
                    continue;
                }

                Entry s = new Entry();
                s.tick = now;
                s.seq = STORE.nextSeq();
                s.visit = -1;
                s.kind = KIND_SAMPLE;
                s.uuid = e.getUUID();
                s.dim = dim;
                s.x = e.getX();
                s.y = e.getY();
                s.z = e.getZ();
                Vec3 v = e.getDeltaMovement();
                s.vx = v.x;
                s.vy = v.y;
                s.vz = v.z;
                s.yRot = e.getYRot();
                s.xRot = e.getXRot();
                STORE.entries().add(s);
                lastSamples.put(s.uuid, s);
                STORE.markDirty();
            }
        }
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        sample();
        STORE.tick(ProjectileJournal::handleDue);
        applyPuppets();
    }

    public static boolean isPuppeted(Entity e) {
        return e != null && puppets.containsKey(e.getUUID());
    }

    private static void handleDue(Entry e) {
        if (e.kind == KIND_SPAWN) startPuppet(e);
        else if (e.kind == KIND_END) endPuppet(e.uuid);
    }

    private static void startPuppet(Entry spawn) {
        if (puppets.containsKey(spawn.uuid)) return;
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(spawn.dim));
            if (level == null) return;
            CompoundTag tag = TagParser.parseTag(spawn.nbt);
            Entity created = EntityType.loadEntityRecursive(tag, level, entity -> entity);
            if (created == null) return;
            created.setUUID(spawn.uuid);
            created.moveTo(spawn.x, spawn.y, spawn.z, spawn.yRot, spawn.xRot);
            created.setDeltaMovement(spawn.vx, spawn.vy, spawn.vz);
            created.addTag(EntityJournal.TAG);
            if (!level.addFreshEntity(created)) return;
            Puppet p = new Puppet();
            p.entity = created;
            p.uuid = spawn.uuid;
            p.dim = spawn.dim;
            puppets.put(spawn.uuid, p);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("[TT-PROJ] start failed {}", spawn.uuid, ex);
        }
    }

    private static void endPuppet(UUID uuid) {
        Puppet p = puppets.remove(uuid);
        if (p != null && p.entity != null && !p.entity.isRemoved()) p.entity.discard();
    }

    private static void applyPuppets() {
        if (puppets.isEmpty()) return;
        long now = TimeTravel.timelineTick;
        Map<UUID, List<Entry>> trails = getTrails();
        for (Puppet p : new ArrayList<>(puppets.values())) {
            if (p.entity == null || p.entity.isRemoved()) {
                puppets.remove(p.uuid);
                continue;
            }
            List<Entry> trail = trails.get(p.uuid);
            if (trail == null) continue;
            Entry best = null;
            for (Entry s : trail) {
                if (s.tick > now) break;
                best = s;
            }
            if (best == null) continue;
            p.entity.moveTo(best.x, best.y, best.z, best.yRot, best.xRot);
            p.entity.setDeltaMovement(best.vx, best.vy, best.vz);
        }
    }

    private static Map<UUID, List<Entry>> getTrails() {
        if (trails != null) return trails;
        trails = new HashMap<>();
        for (Entry e : STORE.entries()) {
            if (e.kind == KIND_SAMPLE) {
                trails.computeIfAbsent(e.uuid, k -> new ArrayList<>()).add(e);
            }
        }
        for (List<Entry> l : trails.values()) {
            l.sort(Comparator.comparingLong(en -> en.tick));
        }
        return trails;
    }

    public static void onJump(long from, long to) {
        for (Puppet p : puppets.values()) {
            if (p.entity != null && !p.entity.isRemoved()) p.entity.discard();
        }
        puppets.clear();
        trails = null;

        long now = TimeTravel.timelineTick;
        Map<UUID, Entry> lastSpawn = new HashMap<>();
        Map<UUID, Entry> lastEnd = new HashMap<>();
        for (Entry e : STORE.entries()) {
            if (e.kind == KIND_SPAWN) lastSpawn.merge(e.uuid, e, (a, b) -> a.tick >= b.tick ? a : b);
            else if (e.kind == KIND_END) lastEnd.merge(e.uuid, e, (a, b) -> a.tick >= b.tick ? a : b);
        }
        for (Map.Entry<UUID, Entry> en : lastSpawn.entrySet()) {
            Entry spawn = en.getValue();
            if (spawn.tick > now) continue;
            Entry end = lastEnd.get(en.getKey());
            if (end != null && end.tick <= now) continue;
            startPuppet(spawn);
        }
        applyPuppets();
        STORE.rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        trails = null;
        for (Puppet p : puppets.values()) {
            if (p.entity != null && !p.entity.isRemoved()) p.entity.discard();
        }
        puppets.clear();
        rebuildTracked();
    }

    private static void rebuildTracked() {
        tracked.clear();
        lastSamples.clear();
        for (Entry e : STORE.entries()) {
            if (e.kind == KIND_SPAWN) tracked.add(e.uuid);
        }
        for (Entry e : STORE.entries()) {
            if (e.kind == KIND_END) tracked.remove(e.uuid);
        }
    }

    public static void load() {
        STORE.load();
        rebuildTracked();
        trails = null;
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        for (Puppet p : puppets.values()) {
            if (p.entity != null && !p.entity.isRemoved()) p.entity.discard();
        }
        puppets.clear();
        tracked.clear();
        lastSamples.clear();
        trails = null;
        STORE.clear();
    }
}
