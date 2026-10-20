package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.mixin.WardenSpawnTrackerAccessor;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.warden.WardenSpawnTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class WardenWarningJournal {
    private WardenWarningJournal() {
    }

    public static final class Entry {
        public UUID player;
        public int level;
        public String nbt;
        public long tick;
        public long seq;
        public int visit;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_warden_warnings.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, List<Entry>> byPlayer = new HashMap<>();

    private static void index(Entry e) {
        byPlayer.computeIfAbsent(e.player, k -> new ArrayList<>()).add(e);
    }

    private static void rebuildIndex() {
        byPlayer.clear();
        for (Entry e : STORE.entries()) index(e);
    }

    private static Entry stateAt(List<Entry> list, long tick) {
        Entry best = null;
        for (Entry e : list) {
            if (e.tick > tick) continue;
            if (best == null || e.tick > best.tick || (e.tick == best.tick && e.seq > best.seq)) best = e;
        }
        return best;
    }

    private static String encode(WardenSpawnTracker tracker) {
        return WardenSpawnTracker.CODEC.encodeStart(NbtOps.INSTANCE, tracker).result().map(Tag::toString).orElse(null);
    }

    private static WardenSpawnTracker decode(String nbt) {
        try {
            return WardenSpawnTracker.CODEC.parse(NbtOps.INSTANCE, TagParser.parseTag(nbt)).result().orElse(null);
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to parse warden spawn tracker state", ex);
            return null;
        }
    }

    private static void check(ServerPlayer player, PlayerData pd) {
        Optional<WardenSpawnTracker> tracker = player.getWardenSpawnTracker();
        if (tracker.isEmpty()) return;
        int level = tracker.get().getWarningLevel();
        long now = TimeTravel.sharedTick;
        List<Entry> list = byPlayer.get(player.getUUID());
        Entry at = list == null ? null : stateAt(list, now);
        if (at != null && at.level == level) return;
        String nbt = encode(tracker.get());
        if (nbt == null) return;

        Entry e = new Entry();
        e.player = player.getUUID();
        e.level = level;
        e.nbt = nbt;
        e.tick = at == null ? -1L : now;
        e.visit = at == null || pd.getCurrentVisit() == null ? -1 : pd.getCurrentVisit().id;
        e.seq = STORE.nextSeq();
        STORE.entries().add(e);
        index(e);
        STORE.markDirty();
    }

    private static void apply(ServerPlayer player, Entry e) {
        Optional<WardenSpawnTracker> tracker = player.getWardenSpawnTracker();
        if (tracker.isEmpty()) return;
        WardenSpawnTracker restored = decode(e.nbt);
        if (restored != null) ((WardenSpawnTrackerAccessor) tracker.get()).tt$copyData(restored);
    }

    private static void restoreAll() {
        long now = TimeTravel.sharedTick;
        TimeTravel.forEachOnlineRecorded((player, pd) -> {
            List<Entry> list = byPlayer.get(player.getUUID());
            if (list == null) return;
            Entry at = stateAt(list, now);
            if (at != null) apply(player, at);
        });
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.timelineSceneManager == null) return;
        TimeTravel.forEachOnlineRecorded(WardenWarningJournal::check);
    }

    public static void onJump(long from, long to) {
        if (TimeTravel.server == null || TimeTravel.timelineSceneManager == null) return;
        restoreAll();
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        rebuildIndex();
        if (TimeTravel.server != null && TimeTravel.isLooping && TimeTravel.timelineSceneManager != null) restoreAll();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void load() {
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
        byPlayer.clear();
        STORE.clear();
    }
}
