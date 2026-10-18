package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-dimension weather journal.
 */
public final class WeatherJournal {
    private WeatherJournal() {}

    private static final int FAR = 1_000_000;

    public static final class Entry {
        public String dim;
        public long tick;
        public long seq;
        public int visit = -1;
        public int clearWeatherTime;
        public int rainTime;
        public boolean raining;
        public int thunderTime;
        public boolean thundering;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_weather.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<String, Entry> current = new HashMap<>();
    private static final Map<String, List<Entry>> byDim = new HashMap<>();
    private static final Map<String, Long> maxTickByDim = new HashMap<>();
    private static final Map<String, Entry> earliestByDim = new HashMap<>();
    private static boolean applying = false;

    private static String key(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static boolean hasSky(ServerLevel level) {
        return level.dimensionType().hasSkyLight();
    }

    private static boolean isOwner(ServerLevel level) {
        return hasSky(level)
                && level.getLevelData() instanceof ServerLevelData
                && !(level.getLevelData() instanceof DerivedLevelData);
    }

    private static boolean isFollower(ServerLevel level) {
        return hasSky(level) && level.getLevelData() instanceof DerivedLevelData;
    }

    private static ServerLevelData data(ServerLevel level) {
        return level.getLevelData() instanceof ServerLevelData d ? d : null;
    }

    private static List<ServerLevel> owners() {
        List<ServerLevel> out = new ArrayList<>();
        if (TimeTravel.server == null) return out;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            if (isOwner(level)) out.add(level);
        }
        return out;
    }

    public static boolean isApplying() {
        return applying;
    }

    private static boolean hasFuture(String dim) {
        Long max = maxTickByDim.get(dim);
        return max != null && max > TimeTravel.timelineTick;
    }

    public static boolean isProtected(ServerLevel level) {
        return TimeTravel.isLooping && isOwner(level) && hasFuture(key(level));
    }

    private static boolean hasEntries(String dim) {
        return byDim.containsKey(dim);
    }

    private static Entry earliest(String dim) {
        return earliestByDim.get(dim);
    }

    private static Entry findAt(String dim, long tick) {
        List<Entry> list = byDim.get(dim);
        if (list == null) return null;
        Entry best = null;
        for (Entry e : list) {
            if (e.tick > tick) continue;
            if (best == null || e.tick > best.tick || (e.tick == best.tick && e.seq > best.seq)) {
                best = e;
            }
        }
        return best;
    }

    private static void index(Entry e) {
        byDim.computeIfAbsent(e.dim, k -> new ArrayList<>()).add(e);
        Long max = maxTickByDim.get(e.dim);
        if (max == null || e.tick > max) maxTickByDim.put(e.dim, e.tick);
        Entry earliest = earliestByDim.get(e.dim);
        if (earliest == null || e.tick < earliest.tick
                || (e.tick == earliest.tick && e.seq < earliest.seq)) {
            earliestByDim.put(e.dim, e);
        }
    }

    private static void rebuildIndex() {
        byDim.clear();
        maxTickByDim.clear();
        earliestByDim.clear();
        for (Entry e : STORE.entries()) index(e);
    }

    private static void rebuildCurrent() {
        current.clear();
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            if (e.tick > now) continue;
            Entry prev = current.get(e.dim);
            if (prev == null || e.tick > prev.tick || (e.tick == prev.tick && e.seq > prev.seq)) {
                current.put(e.dim, e);
            }
        }
    }

    public static void snapshotInitial() {
        for (ServerLevel level : owners()) {
            if (!hasEntries(key(level))) write(level, TimeTravel.timelineTick, true);
        }
    }

    private static void write(ServerLevel level, long tick, boolean force) {
        ServerLevelData d = data(level);
        if (d == null) return;

        String dim = key(level);
        Entry existing = current.get(dim);
        if (!force && existing != null
                && existing.raining == d.isRaining()
                && existing.thundering == d.isThundering()) {
            return;
        }

        Entry e = new Entry();
        e.dim = dim;
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.clearWeatherTime = d.getClearWeatherTime();
        e.rainTime = d.getRainTime();
        e.raining = d.isRaining();
        e.thunderTime = d.getThunderTime();
        e.thundering = d.isThundering();
        STORE.entries().add(e);
        index(e);
        current.put(dim, e);
        STORE.markDirty();
    }

    public static void onAdvance(ServerLevel level) {
        if (applying || !TimeTravel.isLooping || !isOwner(level) || isProtected(level)) return;
        write(level, TimeTravel.timelineTick, false);
    }

    public static void onSetWeather(ServerLevel level) {
        if (applying || !TimeTravel.isLooping || !isOwner(level) || isProtected(level)) return;
        write(level, TimeTravel.timelineTick, true);
    }

    public static void enforce(ServerLevel level) {
        if (!isProtected(level)) return;
        ServerLevelData d = data(level);
        Entry e = current.get(key(level));
        if (d == null || e == null) return;
        applying = true;
        try {
            d.setClearWeatherTime(0);
            d.setRaining(e.raining);
            d.setThundering(e.thundering);
            d.setRainTime(FAR);
            d.setThunderTime(FAR);
        } finally {
            applying = false;
        }
    }

    private static void sendState(ServerLevel level, boolean raining, boolean thundering, boolean instant, boolean changed) {
        float rain = raining ? 1.0F : 0.0F;
        float thunder = thundering ? 1.0F : 0.0F;
        var players = level.getServer().getPlayerList();
        var dim = level.dimension();

        if (instant || changed) {
            players.broadcastAll(new ClientboundGameEventPacket(raining ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0.0F), dim);
        }
        if (instant) {
            level.setRainLevel(rain);
            level.setThunderLevel(thunder);
            players.broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rain), dim);
            players.broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunder), dim);
        }
    }

    private static void syncFollowers(boolean instant) {
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            if (!isFollower(level)) continue;
            sendState(level, level.getLevelData().isRaining(), level.getLevelData().isThundering(), instant, true);
        }
    }

    private static void apply(ServerLevel level, Entry e, long targetTick, boolean instant) {
        ServerLevelData d = data(level);
        if (d == null) return;

        boolean wasRaining = d.isRaining();
        long delta = Math.max(0, targetTick - e.tick);
        var random = level.getRandom();

        int clearT;
        int rainT;
        int thunderT;
        if (hasFuture(e.dim)) {
            clearT = 0;
            rainT = FAR;
            thunderT = FAR;
        } else {
            clearT = e.raining || e.thundering ? 0 : (int) Math.max(0, e.clearWeatherTime - delta);
            long rainLeft = e.rainTime - delta;
            long thunderLeft = e.thunderTime - delta;
            rainT = rainLeft > 0 ? (int) rainLeft
                    : (e.raining ? 12000 + random.nextInt(12000) : 12000 + random.nextInt(168000));
            thunderT = thunderLeft > 0 ? (int) thunderLeft
                    : (e.thundering ? 3600 + random.nextInt(12000) : 12000 + random.nextInt(168000));
        }

        applying = true;
        try {
            d.setClearWeatherTime(clearT);
            d.setRainTime(rainT);
            d.setRaining(e.raining);
            d.setThunderTime(thunderT);
            d.setThundering(e.thundering);
            sendState(level, e.raining, e.thundering, instant, wasRaining != e.raining);
        } finally {
            applying = false;
        }
        current.put(e.dim, e);
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        boolean any = false;
        List<Entry> due = new ArrayList<>();
        STORE.tick(due::add);
        for (Entry e : due) {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null || !isOwner(level)) continue;
            apply(level, e, e.tick, false);
            any = true;
        }
        if (any) syncFollowers(false);
    }

    public static void onJump(long from, long to) {
        STORE.rebuildUpcoming();
        rebuildCurrent();
        for (ServerLevel level : owners()) {
            String dim = key(level);
            Entry best = findAt(dim, TimeTravel.timelineTick);
            if (best == null) best = earliest(dim);
            if (best != null) apply(level, best, TimeTravel.timelineTick, true);
        }
        syncFollowers(true);
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
        rebuildCurrent();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        rebuildIndex();
        rebuildCurrent();
    }

    public static void load() {
        STORE.load();
        rebuildIndex();
        rebuildCurrent();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        current.clear();
        byDim.clear();
        maxTickByDim.clear();
        earliestByDim.clear();
        STORE.clear();
    }
}
