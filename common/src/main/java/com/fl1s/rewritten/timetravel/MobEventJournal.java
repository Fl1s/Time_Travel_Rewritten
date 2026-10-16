package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Discrete mob events. Records deaths (with an explosion flag) and replays
 * them at the recorded tick, so past selves die exactly when the original timeline
 * says they should. Continuous state lives in {@link MobTrailJournal}.
 */
public final class MobEventJournal {
    private MobEventJournal() {}

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit;
        public UUID uuid;
        public String dim;
        public boolean explode;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_mob_events.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, Entry> latestDeath = new HashMap<>();
    private static final PriorityQueue<Entry> pendingDeaths = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));

    public static void recordDeath(LivingEntity victim, boolean explode) {
        if (!TimeTravel.isLooping) return;
        if (Ghosts.is(victim)) return;
        if (!(victim.level() instanceof ServerLevel level)) return;
        PlayerData pd = BlockJournal.nearestRecorder(level, victim.blockPosition());

        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = victim.getUUID();
        e.dim = level.dimension().location().toString();
        e.explode = explode;
        STORE.entries().add(e);
        latestDeath.put(e.uuid, e);
        STORE.markDirty();
    }

    public static boolean hasDeathAtOrBefore(UUID id, long tick) {
        Entry e = latestDeath.get(id);
        return e != null && e.tick <= tick;
    }

    public static Entry deathOf(UUID id) { return latestDeath.get(id); }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        long now = TimeTravel.timelineTick;
        while (!pendingDeaths.isEmpty() && pendingDeaths.peek().tick <= now) {
            Entry e = pendingDeaths.poll();
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            Entity entity = level.getEntity(e.uuid);
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isDeadOrDying() || living.isRemoved()) continue;

            if (e.explode && living instanceof net.minecraft.world.entity.monster.Creeper creeper) {
                creeper.setSwellDir(1);
                creeper.setHealth(0);
                living.die(living.damageSources().generic());
            } else {
                MobJournal.enterApplying();
                try {
                    living.invulnerableTime = 0;
                    living.hurt(living.damageSources().genericKill(),
                            living.getMaxHealth() + living.getAbsorptionAmount() + 1000.0f);
                    if (!living.isDeadOrDying()) {
                        living.setHealth(0);
                        living.die(living.damageSources().generic());
                    }
                } finally {
                    MobJournal.exitApplying();
                }
            }
        }
    }

    public static void onJump(long from, long to) { rebuildUpcoming(); }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.entries().removeIf(e -> visitIds.contains(e.visit));
        STORE.upcoming().removeIf(e -> visitIds.contains(e.visit));
        latestDeath.values().removeIf(e -> visitIds.contains(e.visit));
        STORE.markDirty();
        rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        pendingDeaths.clear();
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            if (e.tick > now) pendingDeaths.add(e);
        }
    }

    public static void load() {
        latestDeath.clear();
        STORE.load();
        for (Entry e : STORE.entries()) {
            Entry prev = latestDeath.get(e.uuid);
            if (prev == null || e.tick < prev.tick) latestDeath.put(e.uuid, e);
        }
        rebuildUpcoming();
    }

    public static void save() { STORE.save(); }
    public static void saveIfDirty() { STORE.saveIfDirty(); }

    public static void clear() {
        latestDeath.clear();
        pendingDeaths.clear();
        STORE.clear();
    }
}
