package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.mixin.EndDragonFightAccessor;
import com.fl1s.rewritten.timetravel.mob.MobEventJournal;
import com.fl1s.rewritten.timetravel.net.DragonDeathPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.DragonRespawnAnimation;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BossFightJournal {
    private BossFightJournal() {
    }

    private static final int DEATH_TICKS = 200;
    private static final double DEATH_RISE = 0.1;

    public static final class Entry {
        public String dim;
        public long tick;
        public long seq;
        public int visit;
        public boolean scan;
        public boolean killed;
        public boolean previously;
        public String dragon;
        public boolean hasPortal;
        public long portal;
        public List<Integer> gateways;
        public String respawn;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_boss_fights.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Set<UUID> known = new HashSet<>();
    private static final Set<UUID> driven = new HashSet<>();
    private static boolean applying = false;

    private static boolean active() {
        if (applying || !TimeTravel.isLooping || ReplayState.SUPPRESS || ReplayState.isReplaying()) return false;
        return TimeTravel.timelineSceneManager != null && TimeTravel.server != null;
    }

    private static String dimOf(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static int visitAt(ServerLevel level) {
        PlayerData pd = BlockJournal.nearestRecorder(level, BlockPos.ZERO);
        return pd != null && pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
    }

    private static void add(Entry e) {
        STORE.entries().add(e);
        indexDragon(e);
        STORE.markDirty();
    }

    private static void indexDragon(Entry e) {
        if (e.dragon == null) return;
        try {
            known.add(UUID.fromString(e.dragon));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void rebuildKnown() {
        known.clear();
        for (Entry e : STORE.entries()) indexDragon(e);
    }

    private static Entry capture(ServerLevel level, EndDragonFight fight, long tick, int visit) {
        EndDragonFight.Data data = fight.saveData();
        Entry e = new Entry();
        e.dim = dimOf(level);
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = visit;
        e.scan = data.needsStateScanning();
        e.killed = data.dragonKilled();
        e.previously = data.previouslyKilled();
        e.dragon = data.dragonUUID().map(UUID::toString).orElse(null);
        if (data.exitPortalLocation().isPresent()) {
            e.hasPortal = true;
            e.portal = data.exitPortalLocation().get().asLong();
        }
        e.gateways = new ArrayList<>(data.gateways().orElseGet(ArrayList::new));
        DragonRespawnAnimation st = ((EndDragonFightAccessor) fight).tt$getRespawnStage();
        e.respawn = st == null ? null : st.name();
        return e;
    }

    private static boolean same(Entry a, Entry b) {
        return a.scan == b.scan && a.killed == b.killed && a.previously == b.previously && Objects.equals(a.dragon, b.dragon) && a.hasPortal == b.hasPortal && a.portal == b.portal && a.gateways.equals(b.gateways) && Objects.equals(a.respawn, b.respawn);
    }

    private static Entry stateAt(String dim, long tick) {
        Entry best = null;
        for (Entry e : STORE.entries()) {
            if (!e.dim.equals(dim) || e.tick > tick) continue;
            if (best == null || e.tick > best.tick || (e.tick == best.tick && e.seq > best.seq)) best = e;
        }
        return best;
    }

    public static void beforeChange(ServerLevel level, EndDragonFight fight) {
        if (!active()) return;
        String dim = dimOf(level);
        for (Entry e : STORE.entries()) if (e.dim.equals(dim)) return;
        add(capture(level, fight, -1, -1));
    }

    public static void onChanged(ServerLevel level, EndDragonFight fight) {
        if (!active()) return;
        long now = TimeTravel.timelineTick;
        Entry cur = capture(level, fight, now, visitAt(level));
        Entry at = stateAt(cur.dim, now);
        if (at != null && same(at, cur)) return;
        add(cur);
    }

    public static boolean isProtected(ServerLevel level) {
        if (!TimeTravel.isLooping) return false;
        String dim = dimOf(level);
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) if (e.tick > now && e.dim.equals(dim)) return true;
        return false;
    }

    public static boolean isDrivenDeath(EnderDragon dragon) {
        return driven.contains(dragon.getUUID());
    }

    public static void finishProtectedRespawn(EndDragonFight fight) {
        EndDragonFightAccessor acc = (EndDragonFightAccessor) fight;
        acc.tt$setRespawnStage(null);
        acc.tt$setRespawnCrystals(null);
        acc.tt$setRespawnTime(0);
        acc.tt$setDragonKilled(false);
        acc.tt$setTicksSinceDragonSeen(0);
    }

    public static void cosmeticRespawnTick(DragonRespawnAnimation stage, ServerLevel level, List<EndCrystal> crystals, int time) {
        switch (stage) {
            case PREPARING_TO_SUMMON_PILLARS -> {
                if (time < 100 && (time == 0 || time == 50 || time == 51 || time == 52 || time >= 95)) {
                    level.levelEvent(3001, new BlockPos(0, 128, 0), 0);
                }
            }
            case SUMMONING_PILLARS -> {
                if (time % 40 != 39) return;
                List<SpikeFeature.EndSpike> spikes = SpikeFeature.getSpikesForLevel(level);
                int j = time / 40;
                if (j >= spikes.size()) return;
                SpikeFeature.EndSpike spike = spikes.get(j);
                for (EndCrystal c : level.getEntitiesOfClass(EndCrystal.class, spike.getTopBoundingBox())) {
                    c.setInvulnerable(false);
                }
                level.explode(null, spike.getCenterX() + 0.5, spike.getHeight(), spike.getCenterZ() + 0.5, 5.0F, Level.ExplosionInteraction.NONE);
            }
            case SUMMONING_DRAGON -> {
                if (time < 100 && (time >= 80 || (time > 0 && time < 5))) {
                    level.levelEvent(3001, new BlockPos(0, 128, 0), 0);
                }
            }
            default -> {
            }
        }
    }

    private static DragonRespawnAnimation parseStage(String s) {
        if (s == null) return null;
        try {
            return DragonRespawnAnimation.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<EndCrystal> respawnCrystals(ServerLevel level, Entry e) {
        if (!e.hasPortal) return null;
        BlockPos p = BlockPos.of(e.portal).above(1);
        List<EndCrystal> list = new ArrayList<>();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            List<EndCrystal> l = level.getEntitiesOfClass(EndCrystal.class, new AABB(p.relative(dir, 2)));
            if (l.isEmpty()) return null;
            list.addAll(l);
        }
        return list;
    }

    private static void clearRespawn(EndDragonFightAccessor acc) {
        acc.tt$setRespawnStage(null);
        acc.tt$setRespawnCrystals(null);
        acc.tt$setRespawnTime(0);
    }

    private static void applyRespawn(ServerLevel level, EndDragonFightAccessor acc, Entry e, boolean jump) {
        DragonRespawnAnimation want = parseStage(e.respawn);
        DragonRespawnAnimation have = acc.tt$getRespawnStage();
        if (want == null) {
            if (have != null || jump) clearRespawn(acc);
            return;
        }
        if (!jump && want == have) return;
        List<EndCrystal> crystals = respawnCrystals(level, e);
        if (crystals == null) {
            TimeTravel.TIMELINE_LOGGER.warn("Respawn stage {} in {} but crystals are missing, dropping respawn", want, e.dim);
            clearRespawn(acc);
            return;
        }
        acc.tt$setRespawnCrystals(crystals);
        acc.tt$setRespawnStage(want);
        acc.tt$setRespawnTime((int) Math.max(0, TimeTravel.timelineTick - e.tick));
    }

    private static void apply(Entry e, boolean jump) {
        if (TimeTravel.server == null) return;
        ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
        if (level == null) return;
        EndDragonFight fight = level.getDragonFight();
        if (fight == null) return;
        EndDragonFightAccessor acc = (EndDragonFightAccessor) fight;
        applying = true;
        try {
            acc.tt$setNeedsStateScanning(e.scan);
            acc.tt$setDragonKilled(e.killed);
            acc.tt$setPreviouslyKilled(e.previously);
            acc.tt$setDragonUUID(e.dragon == null ? null : UUID.fromString(e.dragon));
            acc.tt$setPortalLocation(e.hasPortal ? BlockPos.of(e.portal) : null);
            acc.tt$getGateways().clear();
            acc.tt$getGateways().addAll(e.gateways);
            acc.tt$setTicksSinceDragonSeen(0);
            applyRespawn(level, acc, e, jump);
            acc.tt$updateCrystalCount();
            if (e.killed) acc.tt$getDragonEvent().setProgress(0.0F);
            else resync(level, fight, acc);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to apply boss fight state", ex);
        } finally {
            applying = false;
        }
    }

    private static void resync(ServerLevel level, EndDragonFight fight, EndDragonFightAccessor acc) {
        EnderDragon d = null;
        UUID wanted = fight.saveData().dragonUUID().orElse(null);
        if (wanted != null && level.getEntity(wanted) instanceof EnderDragon x && !x.isRemoved()) d = x;
        if (d == null) {
            List<? extends EnderDragon> alive = level.getDragons();
            if (alive.size() == 1) d = alive.get(0);
            else if (alive.size() > 1)
                TimeTravel.TIMELINE_LOGGER.warn("{} dragons alive in {}", alive.size(), dimOf(level));
        }
        if (d == null) return;
        acc.tt$setDragonUUID(d.getUUID());
        acc.tt$setTicksSinceDragonSeen(0);
        fight.updateDragon(d);
    }

    private static EnderDragon findDragon(UUID id) {
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            if (level.getEntity(id) instanceof EnderDragon d && !d.isRemoved()) return d;
        }
        return null;
    }

    private static void driveDeaths() {
        long now = TimeTravel.timelineTick;
        driven.removeIf(id -> findDragon(id) == null);
        for (UUID id : known) {
            if (driven.contains(id)) continue;
            MobEventJournal.Entry death = MobEventJournal.deathOf(id);
            if (death == null || !death.hasPosition) continue;
            long start = death.tick - (DEATH_TICKS - 1);
            if (now < start || now >= death.tick) continue;
            EnderDragon dragon = findDragon(id);
            if (dragon != null) startDrivenDeath(dragon, death, now);
        }
    }

    private static void startDrivenDeath(EnderDragon d, MobEventJournal.Entry death, long now) {
        long left = death.tick - now;
        driven.add(d.getUUID());
        d.getPhaseManager().setPhase(EnderDragonPhase.DYING);
        d.setHealth(0.0F);
        d.dragonDeathTime = (int) (DEATH_TICKS - 1 - left);
        d.moveTo(death.x, death.y - DEATH_RISE * left, death.z, death.yRot, death.xRot);
        d.setDeltaMovement(Vec3.ZERO);

        if (d.level() instanceof ServerLevel level) {
            EndDragonFight fight = level.getDragonFight();
            if (fight != null) {
                EndDragonFightAccessor acc = (EndDragonFightAccessor) fight;
                acc.tt$setDragonUUID(d.getUUID());
                acc.tt$setTicksSinceDragonSeen(0);
                fight.updateDragon(d);
            }
            TimeTravel.queueTask(() -> {
                if (d.isRemoved()) return;
                DragonDeathPayload p = new DragonDeathPayload(d.getId(), d.dragonDeathTime);
                for (ServerPlayer sp : level.players()) {
                    if (!Clones.is(sp)) TimelineNet.sendToPlayer(sp, p);
                }
            });
        }
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        STORE.tick(e -> apply(e, false));
        driveDeaths();
    }

    public static void onJump(long from, long to) {
        if (TimeTravel.server == null) return;
        driven.clear();
        long now = TimeTravel.timelineTick;
        Set<String> dims = new HashSet<>();
        for (Entry e : STORE.entries()) dims.add(e.dim);
        for (String dim : dims) {
            Entry at = stateAt(dim, now);
            if (at != null) apply(at, true);
        }
        driveDeaths();
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        rebuildKnown();
        onJump(TimeTravel.timelineTick, TimeTravel.timelineTick);
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void load() {
        STORE.load();
        rebuildKnown();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        known.clear();
        driven.clear();
        STORE.clear();
    }
}
