package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.Levels;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VillagerJournal {
    private VillagerJournal() {
    }

    private static final int SCAN_INTERVAL = 20;
    private static final double SCAN_RADIUS = 64.0;

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit = -1;
        public String dim;
        public UUID uuid;
        public String data;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_villagers.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, String> lastData = new HashMap<>();
    private static final Map<UUID, Entry> canon = new HashMap<>();
    private static boolean applying = false;

    private static DynamicOps<Tag> ops() {
        return RegistryOps.create(NbtOps.INSTANCE, TimeTravel.server.registryAccess());
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        if (applying) return;
        if (TimeTravel.timelineTick % SCAN_INTERVAL != 0) return;
        if (TimeTravel.loopSceneManager == null) return;

        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                if (Ghosts.is(p) || p.isSpectator()) continue;
                PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(p.getName().getString());
                if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) continue;
                AABB box = p.getBoundingBox().inflate(SCAN_RADIUS);
                for (Villager v : level.getEntitiesOfClass(Villager.class, box)) {
                    capture(v, pd);
                }
            }
        }
    }

    private static void capture(Villager v, PlayerData pd) {
        String data = encodeData(v);
        if (data.equals(lastData.get(v.getUUID()))) return;
        lastData.put(v.getUUID(), data);

        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.dim = v.level().dimension().location().toString();
        e.uuid = v.getUUID();
        e.data = data;
        STORE.entries().add(e);
        canon.put(e.uuid, e);
        STORE.markDirty();
    }

    public static void enforce(Villager v) {
        if (v.level().isClientSide()) return;
        if (applying || !TimeTravel.isLooping) return;
        if (TimeTravel.server == null) return;

        UUID uuid = v.getUUID();
        Entry latest = canon.get(uuid);
        if (latest == null) return;
        if (TimeTravel.timelineTick > latest.tick) return;

        Entry target = lastBefore(uuid, TimeTravel.timelineTick);
        if (target == null) return;

        String current = encodeData(v);
        if (current.equals(target.data)) return;

        applying = true;
        try {
            applyData(v, target.data);
            lastData.put(uuid, target.data);
        } finally {
            applying = false;
        }
    }

    public static void onJump(long from, long to) {
        if (TimeTravel.server == null) return;
        for (UUID id : new ArrayList<>(canon.keySet())) {
            Entry target = lastBefore(id, to);
            if (target == null) continue;
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(target.dim));
            if (level == null) continue;
            Entity e = level.getEntity(id);
            if (!(e instanceof Villager v) || v.isRemoved()) continue;
            applying = true;
            try {
                applyData(v, target.data);
                lastData.put(id, target.data);
            } finally {
                applying = false;
            }
        }
    }

    private static Entry lastBefore(UUID uuid, long tick) {
        Entry best = null;
        for (Entry e : STORE.entries()) {
            if (!e.uuid.equals(uuid) || e.tick > tick) continue;
            if (best == null || e.tick > best.tick || (e.tick == best.tick && e.seq > best.seq)) best = e;
        }
        return best;
    }

    private static String encodeData(Villager v) {
        return VillagerData.CODEC.encodeStart(ops(), v.getVillagerData()).getOrThrow().toString();
    }

    private static void applyData(Villager v, String encoded) {
        try {
            Tag tag = TagParser.parseTag(encoded);
            VillagerData data = VillagerData.CODEC.parse(ops(), tag).getOrThrow();
            v.setVillagerData(data);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply villager data", ex);
        }
    }

    public static void rebuildUpcoming() {
        canon.clear();
        for (Entry e : STORE.entries()) {
            canon.merge(e.uuid, e, (a, b) -> (a.tick != b.tick ? a.tick < b.tick : a.seq < b.seq) ? a : b);
        }
    }

    public static void load() {
        lastData.clear();
        canon.clear();
        STORE.load();
        rebuildUpcoming();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void save() {
        STORE.save();
    }

    public static void clear() {
        lastData.clear();
        canon.clear();
        STORE.clear();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
    }
}
