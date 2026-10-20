package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.mixin.EndDragonFightAccessor;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EndDragonFight;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BossFightJournal {
    private BossFightJournal() {}

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
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_boss_fights.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

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
        return e;
    }

    private static boolean same(Entry a, Entry b) {
        return a.scan == b.scan && a.killed == b.killed && a.previously == b.previously
                && Objects.equals(a.dragon, b.dragon) && a.hasPortal == b.hasPortal && a.portal == b.portal
                && a.gateways.equals(b.gateways);
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
        for (Entry e : STORE.entries()) {
            if (e.dim.equals(dim)) return;
        }
        STORE.entries().add(capture(level, fight, -1, -1));
        STORE.markDirty();
    }

    public static void onChanged(ServerLevel level, EndDragonFight fight) {
        if (!active()) return;
        long now = TimeTravel.timelineTick;
        Entry cur = capture(level, fight, now, visitAt(level));
        Entry at = stateAt(cur.dim, now);
        if (at != null && same(at, cur)) return;
        STORE.entries().add(cur);
        STORE.markDirty();
    }

    private static void apply(Entry e) {
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
            acc.tt$setRespawnStage(null);
            acc.tt$setRespawnCrystals(null);
            acc.tt$setRespawnTime(0);
            acc.tt$setTicksSinceDragonSeen(0);
            acc.tt$updateCrystalCount();
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to apply boss fight state", ex);
        } finally {
            applying = false;
        }
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        STORE.tick(BossFightJournal::apply);
    }

    public static void onJump(long from, long to) {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        Set<String> dims = new HashSet<>();
        for (Entry e : STORE.entries()) dims.add(e.dim);
        for (String dim : dims) {
            Entry at = stateAt(dim, now);
            if (at != null) apply(at);
        }
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        onJump(TimeTravel.timelineTick, TimeTravel.timelineTick);
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void load() {
        STORE.load();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        STORE.clear();
    }
}
