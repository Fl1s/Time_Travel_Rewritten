package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorldEntityJournal {
    private WorldEntityJournal() {
    }

    private static final double RANGE = 96.0;

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit = -1;
        public String dim;
        public UUID uuid;
        public String nbt;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_world_entities.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, Entry> canon = new HashMap<>();
    private static boolean replaying = false;

    public static boolean isReplaying() {
        return replaying;
    }

    public static boolean isTracked(Entity e) {
        return e instanceof EndCrystal || e instanceof ItemFrame || e instanceof Painting || e instanceof ArmorStand;
    }

    public static void onEntityDeath(Entity entity) {
        if (replaying) return;
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.loopSceneManager == null) return;
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (!isTracked(entity)) return;
        if (!entity.getType().canSerialize()) return;

        ServerPlayer nearest = null;
        double best = RANGE * RANGE;
        for (ServerPlayer p : level.players()) {
            if (Ghosts.is(p)) continue;
            double d = p.distanceToSqr(entity);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest == null) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(nearest.getName().getString());
        if (pd == null || !pd.getActive()) return;

        UUID uuid = entity.getUUID();
        STORE.entries().removeIf(old -> old.uuid.equals(uuid));

        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return;
        tag.remove("UUID");

        Entry entry = new Entry();
        entry.tick = TimeTravel.timelineTick;
        entry.seq = STORE.nextSeq();
        entry.visit = pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
        entry.dim = level.dimension().location().toString();
        entry.uuid = uuid;
        entry.nbt = tag.toString();

        STORE.entries().add(entry);
        canon.put(uuid, entry);
        STORE.markDirty();
    }

    public static void onJump(long from, long to) {
        if (from == to) return;
        ArrayList<Entry> affected = new ArrayList<>();
        if (to < from) {
            for (Entry e : STORE.entries()) if (e.tick > to && e.tick <= from) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
            for (Entry e : affected) apply(e, true);
        } else {
            for (Entry e : STORE.entries()) if (e.tick > from && e.tick <= to) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
            for (Entry e : affected) apply(e, false);
        }
        rebuildUpcoming();
    }

    private static void apply(Entry e, boolean revive) {
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) return;

            Entity existing = level.getEntity(e.uuid);
            replaying = true;
            try {
                if (revive) {
                    if (existing != null && !existing.isRemoved()) return;
                    CompoundTag tag = TagParser.parseTag(e.nbt);
                    Entity revived = EntityType.loadEntityRecursive(tag, level, entity -> entity);
                    if (revived != null) {
                        revived.setUUID(e.uuid);
                        level.addFreshEntity(revived);
                    }
                } else {
                    if (existing != null && !existing.isRemoved()) existing.discard();
                }
            } finally {
                replaying = false;
            }
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply world entity journal entry {}", e.uuid, ex);
        }
    }

    public static void rebuildUpcoming() {
        rebuildCanon();
        STORE.upcoming().clear();
        for (Entry e : canon.values()) {
            if (e.tick > TimeTravel.timelineTick) STORE.upcoming().add(e);
        }
    }

    private static void rebuildCanon() {
        canon.clear();
        for (Entry e : STORE.entries()) {
            canon.merge(e.uuid, e, (a, b) -> (a.tick != b.tick ? a.tick < b.tick : a.seq < b.seq) ? a : b);
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        long now = TimeTravel.timelineTick;
        ArrayList<Entry> mine = new ArrayList<>();
        for (Entry e : STORE.entries()) if (visitIds.contains(e.visit)) mine.add(e);
        mine.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
        for (Entry e : mine) {
            if (e.tick <= now) apply(e, true);
        }
        STORE.entries().removeAll(mine);
        STORE.upcoming().removeAll(mine);
        rebuildUpcoming();
        STORE.markDirty();
    }

    public static void load() {
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
        canon.clear();
        STORE.clear();
    }
}
