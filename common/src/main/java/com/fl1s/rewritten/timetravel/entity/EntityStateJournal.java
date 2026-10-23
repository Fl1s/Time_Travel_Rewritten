package com.fl1s.rewritten.timetravel.entity;

import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EntityStateJournal {
    private EntityStateJournal() {}

    private static final String[] ARMOR_STAND_KEYS = {"ArmorItems", "HandItems", "Pose", "DisabledSlots", "Invisible",
            "ShowArms", "NoBasePlate", "Small", "Marker", "CustomName", "CustomNameVisible"};
    private static final String[] ITEM_FRAME_KEYS = {"Item", "ItemRotation", "Invisible", "Fixed"};
    private static final String[] CRYSTAL_KEYS = {"ShowBottom", "BeamTarget"};
    private static final String[] NO_KEYS = {};

    public static final class Entry {
        String dim;
        UUID uuid;
        String nbt;
        String sig;
        long tick;
        long seq;
        int visit;
        transient CompoundTag parsed;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_entity_states.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, List<Entry>> byUuid = new HashMap<>();
    private static boolean applying = false;

    public static boolean isTracked(Entity e) {
        return e instanceof ArmorStand || e instanceof ItemFrame || e instanceof Painting || e instanceof EndCrystal;
    }

    private static String[] keysFor(Entity e) {
        if (e instanceof ArmorStand) return ARMOR_STAND_KEYS;
        if (e instanceof ItemFrame) return ITEM_FRAME_KEYS;
        if (e instanceof EndCrystal) return CRYSTAL_KEYS;
        return NO_KEYS;
    }

    private static ServerLevel levelOf(String dim) {
        return TimeTravel.server.getLevel(Levels.key(dim));
    }

    private static void index(Entry e) {
        byUuid.computeIfAbsent(e.uuid, k -> new ArrayList<>()).add(e);
    }

    private static void rebuildIndex() {
        byUuid.clear();
        for (Entry e : STORE.entries()) index(e);
    }

    private static Entry stateAtOf(List<Entry> list, long tick) {
        Entry best = null;
        for (Entry e : list) {
            if (e.tick > tick) continue;
            if (best == null || e.tick > best.tick || (e.tick == best.tick && e.seq > best.seq)) best = e;
        }
        return best;
    }

    private static Entry stateAt(UUID id, long tick) {
        List<Entry> list = byUuid.get(id);
        return list == null ? null : stateAtOf(list, tick);
    }

    private static String signature(Entity entity) {
        CompoundTag full = new CompoundTag();
        entity.saveWithoutId(full);
        CompoundTag out = new CompoundTag();
        for (String k : keysFor(entity)) {
            if (full.contains(k)) out.put(k, full.get(k));
        }
        return out.toString();
    }

    private static int visitAt(ServerLevel level, BlockPos pos) {
        PlayerData pd = BlockJournal.nearestRecorder(level, pos);
        return pd != null && pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
    }

    private static ServerLevel activeLevel(Entity entity) {
        if (applying || !TimeTravel.isLooping || ReplayState.SUPPRESS) return null;
        if (TimeTravel.timelineSceneManager == null || TimeTravel.server == null) return null;
        return entity.level() instanceof ServerLevel level ? level : null;
    }

    private static boolean inWorld(ServerLevel level, Entity entity) {
        return level.getEntity(entity.getUUID()) == entity;
    }

    private static void record(ServerLevel level, Entity entity, long tick, int visit) {
        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return;
        Entry e = new Entry();
        e.dim = level.dimension().location().toString();
        e.uuid = entity.getUUID();
        e.nbt = tag.toString();
        e.sig = signature(entity);
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = visit;
        STORE.entries().add(e);
        index(e);
        STORE.markDirty();
    }

    private static void ensureBaseline(ServerLevel level, Entity entity) {
        if (!byUuid.containsKey(entity.getUUID())) record(level, entity, -1, -1);
    }

    public static void onSpawn(ServerLevel level, Entity entity) {
        if (!isTracked(entity)) return;
        if (applying || !TimeTravel.isLooping || ReplayState.SUPPRESS) return;
        if (TimeTravel.timelineSceneManager == null) return;
        if (byUuid.containsKey(entity.getUUID())) return;
        record(level, entity, TimeTravel.timelineTick, visitAt(level, entity.blockPosition()));
    }

    public static void beforeChange(Entity entity) {
        if (!isTracked(entity)) return;
        ServerLevel level = activeLevel(entity);
        if (level == null || !inWorld(level, entity)) return;
        ensureBaseline(level, entity);
    }

    public static void onChanged(Entity entity) {
        if (!isTracked(entity)) return;
        ServerLevel level = activeLevel(entity);
        if (level == null || !inWorld(level, entity)) return;
        UUID id = entity.getUUID();
        long now = TimeTravel.timelineTick;
        String sig = signature(entity);
        Entry at = stateAt(id, now);
        if (at != null && at.sig != null && at.sig.equals(sig)) return;
        List<Entry> list = byUuid.get(id);
        if (list != null) {
            for (Entry x : list) {
                if (x.sig != null && Math.abs(x.tick - now) <= 2 && x.sig.equals(sig)) return;
            }
        }
        record(level, entity, now, visitAt(level, entity.blockPosition()));
    }

    public static void onRemoved(Entity entity, Entity.RemovalReason reason) {
        if (!isTracked(entity)) return;
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) return;
        ServerLevel level = activeLevel(entity);
        if (level == null || entity.isRemoved() || !inWorld(level, entity)) return;
        ensureBaseline(level, entity);
        UUID id = entity.getUUID();
        Entry at = stateAt(id, TimeTravel.timelineTick);
        if (at != null && at.nbt == null) return;
        Entry e = new Entry();
        e.dim = level.dimension().location().toString();
        e.uuid = id;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = visitAt(level, entity.blockPosition());
        STORE.entries().add(e);
        index(e);
        STORE.markDirty();
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        STORE.tick(e -> applyEntry(e, true));
    }

    private static void applyEntry(Entry e, boolean fx) {
        ServerLevel level = levelOf(e.dim);
        if (level == null) return;
        applying = true;
        try {
            Entity existing = level.getEntity(e.uuid);
            if (e.nbt == null) {
                if (existing != null && !existing.isRemoved()) {
                    if (fx && existing instanceof EndCrystal) {
                        level.explode(existing, existing.getX(), existing.getY(), existing.getZ(), 6.0F, Level.ExplosionInteraction.NONE);
                    }
                    existing.discard();
                }
                return;
            }
            CompoundTag cached = e.parsed;
            if (cached == null) {
                cached = TagParser.parseTag(e.nbt);
                e.parsed = cached;
            }
            CompoundTag tag = cached.copy();
            if (existing != null && !existing.isRemoved() && isTracked(existing)) {
                if (!signature(existing).equals(e.sig)) existing.load(tag);
            } else if (existing == null) {
                ListTag p = tag.getList("Pos", 6);
                if (!level.isLoaded(BlockPos.containing(p.getDouble(0), p.getDouble(1), p.getDouble(2)))) return;
                Entity created = EntityType.loadEntityRecursive(tag, level, en -> en);
                if (created != null) {
                    created.setUUID(e.uuid);
                    level.addFreshEntity(created);
                }
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to apply entity state entry", ex);
        } finally {
            applying = false;
        }
    }

    public static void onJump(long from, long to) {
        long now = TimeTravel.timelineTick;
        for (Map.Entry<UUID, List<Entry>> en : new ArrayList<>(byUuid.entrySet())) {
            List<Entry> list = en.getValue();
            if (list.isEmpty()) continue;
            Entry at = stateAtOf(list, now);
            if (at != null) {
                applyEntry(at, false);
                continue;
            }
            ServerLevel level = levelOf(list.get(0).dim);
            if (level == null) continue;
            Entity existing = level.getEntity(en.getKey());
            if (existing != null && !existing.isRemoved()) {
                applying = true;
                try {
                    existing.discard();
                } finally {
                    applying = false;
                }
            }
        }
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        rebuildIndex();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void load() {
        STORE.load();
        rebuildIndex();
    }

    public static void save() { STORE.save(); }
    public static void saveIfDirty() { STORE.saveIfDirty(); }

    public static void clear() {
        byUuid.clear();
        STORE.clear();
    }
}
