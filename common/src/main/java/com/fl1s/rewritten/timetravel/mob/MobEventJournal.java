package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.ItemFlowJournal;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.mixin.CreeperAccessor;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import com.fl1s.rewritten.timetravel.journal.EffectJournal;
import com.fl1s.rewritten.timetravel.journal.ParticleJournal;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Creeper;

import java.util.*;

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
        public boolean dig;
        public float radius;
        public boolean hasPosition;
        public double x, y, z;
        public float yRot, xRot;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_mob_events.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static int creeperExplosionDepth = 0;

    public static void enterCreeperExplosion() { creeperExplosionDepth++; }
    public static void exitCreeperExplosion() { creeperExplosionDepth = Math.max(0, creeperExplosionDepth - 1); }
    public static boolean isCreeperExploding() { return creeperExplosionDepth > 0; }

    private static final Map<UUID, Entry> latestDeath = new HashMap<>();
    private static final PriorityQueue<Entry> pendingDeaths = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));

    public static void recordDeath(LivingEntity victim, boolean explode) {
        recordDeath(victim, explode, false);
    }

    public static void recordDeath(LivingEntity victim, boolean explode, boolean dig) {
        if (!TimeTravel.isLooping) return;
        if (Clones.is(victim)) return;
        if (!(victim.level() instanceof ServerLevel level)) return;
        PlayerData pd = BlockJournal.nearestRecorder(level, victim.blockPosition());

        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = (pd != null && pd.getCurrentVisit() != null) ? pd.getCurrentVisit().id : -1;
        e.uuid = victim.getUUID();
        e.dim = level.dimension().location().toString().intern();
        e.explode = explode;
        e.dig = dig;
        if (explode && victim instanceof Creeper c) {
            e.radius = ((CreeperAccessor) c).tt$getExplosionRadius() * (c.isPowered() ? 2.0f : 1.0f);
        }
        e.hasPosition = true;
        e.x = victim.getX();
        e.y = victim.getY();
        e.z = victim.getZ();
        e.yRot = victim.getYRot();
        e.xRot = victim.getXRot();
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
            if (e.explode && (!(entity instanceof LivingEntity ll) || ll.isDeadOrDying() || ll.isRemoved())) {
                playExplosionFx(level, e.x, e.y, e.z, e.radius > 0 ? e.radius : 3.0f);
                continue;
            }
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isDeadOrDying() || living.isRemoved()) continue;

            ItemFlowJournal.setSuppressDrop(true);
            try {
                if (e.explode && living instanceof net.minecraft.world.entity.monster.Creeper creeper) {
                    double fx = e.hasPosition ? e.x : creeper.getX();
                    double fy = e.hasPosition ? e.y : creeper.getY();
                    double fz = e.hasPosition ? e.z : creeper.getZ();
                    MobJournal.enterApplying();
                    try {
                        ((CreeperAccessor) creeper).tt$explode();
                    } finally {
                        MobJournal.exitApplying();
                    }
                    playExplosionFx(level, fx, fy, fz, e.radius > 0 ? e.radius : 3.0f);
                } else if (living instanceof EnderDragon || e.dig) {
                    MobJournal.enterApplying();
                    try {
                        living.discard();
                    } finally {
                        MobJournal.exitApplying();
                    }
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
            } finally {
                ItemFlowJournal.setSuppressDrop(false);
            }
        }
    }

    private static void playExplosionFx(ServerLevel level, double x, double y, double z, float radius) {
        Runnable fx = () -> {
            level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0f,
                    (1.0f + (level.random.nextFloat() - level.random.nextFloat()) * 0.2f) * 0.7f);
            level.sendParticles(radius >= 2.0f ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION,
                    x, y, z, 1, 0, 0, 0, 0);
        };
        EffectJournal.silently(() -> ParticleJournal.silently(fx));
    }

    public static void onJump(long from, long to) { rebuildUpcoming(); }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.entries().removeIf(e -> visitIds.contains(e.visit));
        STORE.upcoming().removeIf(e -> visitIds.contains(e.visit));
        latestDeath.clear();
        for (Entry e : STORE.entries()) {
            Entry prev = latestDeath.get(e.uuid);
            if (prev == null || e.seq > prev.seq) latestDeath.put(e.uuid, e);
        }
        STORE.markDirty();
        rebuildUpcoming();
    }

    public static void fastForward(long from, long to) {
        if (to <= from || TimeTravel.server == null || !TimeTravel.isLooping) return;
        if (ReplayState.isReplaying()) return;
        List<Creeper> fusing = new ArrayList<>();
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Creeper c && c.isAlive() && !Clones.is(c) && c.getSwellDir() > 0) fusing.add(c);
            }
        }
        try {
            for (Creeper c : fusing) {
                CreeperAccessor acc = (CreeperAccessor) c;
                long due = from + Math.max(1, acc.tt$getMaxSwell() - acc.tt$getSwell());
                if (due > to) {
                    acc.tt$setSwell(Math.min(acc.tt$getMaxSwell() - 1, acc.tt$getSwell() + (int) (to - from)));
                    continue;
                }
                TimeTravel.timelineTick = due;
                MobJournal.onEntityDeath(c, true);
                acc.tt$explode();
            }
        } finally {
            TimeTravel.timelineTick = from;
        }
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        pendingDeaths.clear();
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            if (e.tick > now && latestDeath.get(e.uuid) == e) pendingDeaths.add(e);
        }
    }

    public static void load() {
        latestDeath.clear();
        STORE.load();
        for (Entry e : STORE.entries()) {
            Entry prev = latestDeath.get(e.uuid);
            if (prev == null || e.seq > prev.seq) latestDeath.put(e.uuid, e); //what if...
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
