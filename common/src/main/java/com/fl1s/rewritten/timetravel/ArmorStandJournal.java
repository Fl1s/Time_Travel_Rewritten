package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ArmorStandJournal {
    private ArmorStandJournal() {
    }

    private static final double TRACK_RANGE = 48.0;
    private static final String[] KEYS = {"ArmorItems", "HandItems", "Pose", "DisabledSlots", "Invisible", "ShowArms", "NoBasePlate", "Small", "Marker", "CustomName", "CustomNameVisible"};

    public static final class Entry {
        String dim;
        UUID uuid;
        String nbt;
        String sig;
        long tick;
        long seq;
        int visit;
    }

    private record Known(String dim, long pos) {
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_armor_stands.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, List<Entry>> byUuid = new HashMap<>();
    private static final Map<UUID, Known> known = new HashMap<>();
    private static boolean applying = false;

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

    private static String signature(ArmorStand stand) {
        CompoundTag full = new CompoundTag();
        stand.saveWithoutId(full);
        CompoundTag out = new CompoundTag();
        for (String k : KEYS) {
            if (full.contains(k)) out.put(k, full.get(k));
        }
        return out.toString();
    }

    private static int visitAt(ServerLevel level, BlockPos pos) {
        PlayerData pd = BlockJournal.nearestRecorder(level, pos);
        return pd != null && pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
    }

    private static void record(ServerLevel level, ArmorStand stand, long tick, int visit) {
        CompoundTag tag = new CompoundTag();
        if (!stand.save(tag)) return;
        Entry e = new Entry();
        e.dim = level.dimension().location().toString();
        e.uuid = stand.getUUID();
        e.nbt = tag.toString();
        e.sig = signature(stand);
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = visit;
        STORE.entries().add(e);
        index(e);
        STORE.markDirty();
        known.put(e.uuid, new Known(e.dim, stand.blockPosition().asLong()));
    }

    private static void recordGone(UUID id, String dim, BlockPos pos, ServerLevel level) {
        Entry e = new Entry();
        e.dim = dim;
        e.uuid = id;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = visitAt(level, pos);
        STORE.entries().add(e);
        index(e);
        STORE.markDirty();
    }

    public static void onSpawn(ServerLevel level, Entity entity) {
        if (!(entity instanceof ArmorStand stand)) return;
        if (!TimeTravel.isLooping || applying || ReplayState.SUPPRESS) return;
        if (TimeTravel.loopSceneManager == null) return;
        if (byUuid.containsKey(stand.getUUID())) return;
        record(level, stand, TimeTravel.timelineTick, visitAt(level, stand.blockPosition()));
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.loopSceneManager == null) return;
        STORE.tick(ArmorStandJournal::applyEntry);
        long now = TimeTravel.timelineTick;
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                if (Ghosts.is(p)) continue;
                for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, p.getBoundingBox().inflate(TRACK_RANGE))) {
                    if (stand.isRemoved() || !seen.add(stand.getUUID())) continue;
                    check(level, stand, now);
                }
            }
        }
        checkGone(now);
    }

    private static void check(ServerLevel level, ArmorStand stand, long now) {
        UUID id = stand.getUUID();
        known.put(id, new Known(level.dimension().location().toString(), stand.blockPosition().asLong()));
        Entry at = stateAt(id, now);
        if (at == null) {
            record(level, stand, -1, -1);
            return;
        }
        if (at.sig != null && at.sig.equals(signature(stand))) return;
        record(level, stand, now, visitAt(level, stand.blockPosition()));
    }

    private static void checkGone(long now) {
        for (Iterator<Map.Entry<UUID, Known>> it = known.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Known> en = it.next();
            ServerLevel level = levelOf(en.getValue().dim());
            if (level == null) continue;
            BlockPos pos = BlockPos.of(en.getValue().pos());
            if (!level.isLoaded(pos)) continue;
            Entity ent = level.getEntity(en.getKey());
            if (ent != null && !ent.isRemoved()) continue;
            Entry at = stateAt(en.getKey(), now);
            if (at != null && at.nbt != null) recordGone(en.getKey(), en.getValue().dim(), pos, level);
            it.remove();
        }
    }

    private static void applyEntry(Entry e) {
        ServerLevel level = levelOf(e.dim);
        if (level == null) return;
        applying = true;
        try {
            Entity existing = level.getEntity(e.uuid);
            if (e.nbt == null) {
                if (existing != null && !existing.isRemoved()) existing.discard();
                known.remove(e.uuid);
                return;
            }
            CompoundTag tag = TagParser.parseTag(e.nbt);
            if (existing instanceof ArmorStand stand && !stand.isRemoved()) {
                if (!signature(stand).equals(e.sig)) stand.load(tag);
            } else if (existing == null) {
                ListTag p = tag.getList("Pos", 6);
                if (!level.isLoaded(BlockPos.containing(p.getDouble(0), p.getDouble(1), p.getDouble(2)))) return;
                Entity created = EntityType.loadEntityRecursive(tag, level, en -> en);
                if (created != null) level.addFreshEntity(created);
            }
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply armor stand entry", ex);
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
                applyEntry(at);
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
        known.clear();
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
        known.clear();
        STORE.load();
        rebuildIndex();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        known.clear();
        byUuid.clear();
        STORE.clear();
    }
}
