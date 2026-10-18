package com.fl1s.rewritten.timetravel.vehicle;

import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.JsonStore;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dense per-tick trail of every vehicle in a loaded chunk, plus a coarse seed
 * sample captured at loop start.
 */
public final class VehicleTrailJournal {
    private VehicleTrailJournal() {
    }

    public static final class Sample {
        public long tick;
        public double x, y, z;
        public float yRot, xRot;
        public double deltaX, deltaY, deltaZ;
    }

    private static final class Track {
        final String dim;
        final List<Sample> samples = new ArrayList<>();

        Track(String dim) {
            this.dim = dim;
        }

        long lastTick() {
            return samples.isEmpty() ? -1 : samples.get(samples.size() - 1).tick;
        }
    }

    private static final class TrackData {
        String dim;
        List<Sample> samples;
    }

    private static final Type TRACKS_TYPE = new TypeToken<HashMap<String, TrackData>>() {
    }.getType();
    private static final Map<UUID, Track> tracks = new HashMap<>();
    private static final Set<UUID> sampledThisTick = new HashSet<>();
    private static boolean dirty = false;

    private static final double POS_EPS_SQ = 1.0e-4;
    private static final float ROT_EPS = 0.01f;
    private static final double VEL_EPS_SQ = 1.0e-4;

    static void seedFor(Entity entity, String dim, long now) {
        Track t = tracks.computeIfAbsent(entity.getUUID(), k -> new Track(dim));
        if (t.samples.isEmpty()) {
            t.samples.add(makeSample(entity, now));
            dirty = true;
        }
    }

    public static void seedInitial() {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity entity : level.getAllEntities()) {
                if (!VehicleJournal.isTracked(entity)) continue;
                if (Clones.is(entity)) continue;
                Track t = tracks.computeIfAbsent(entity.getUUID(), k -> new Track(dim));
                if (t.samples.isEmpty()) {
                    t.samples.add(makeSample(entity, now));
                    dirty = true;
                }
            }
        }
    }

    public static void onSpawnSeen(UUID id, String dim) {
        tracks.computeIfAbsent(id, k -> new Track(dim));
    }

    public static void sample() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        sampledThisTick.clear();
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity e : level.getAllEntities()) {
                if (!VehicleJournal.isTracked(e)) continue;
                if (Clones.is(e)) continue;
                if (!sampledThisTick.add(e.getUUID())) continue;
                record(e, dim, now);
            }
        }
        ReplayState.observe(now);
    }

    public static boolean hasFutureSamples(UUID id, long now) {
        Track t = tracks.get(id);
        if (t == null || t.samples.isEmpty()) return false;
        return t.samples.get(t.samples.size() - 1).tick > now;
    }

    private static void record(Entity e, String dim, long now) {
        Track t = tracks.computeIfAbsent(e.getUUID(), k -> new Track(dim));
        Sample s = makeSample(e, now);
        if (isSameAsLast(t, s)) return;
        t.samples.add(s);
        dirty = true;
    }

    private static boolean isSameAsLast(Track t, Sample s) {
        if (t.samples.isEmpty()) return false;
        Sample last = t.samples.get(t.samples.size() - 1);
        double dx = s.x - last.x, dy = s.y - last.y, dz = s.z - last.z;
        if (dx * dx + dy * dy + dz * dz > POS_EPS_SQ) return false;
        if (Math.abs(s.yRot - last.yRot) > ROT_EPS) return false;
        if (Math.abs(s.xRot - last.xRot) > ROT_EPS) return false;
        double dvx = s.deltaX - last.deltaX, dvy = s.deltaY - last.deltaY, dvz = s.deltaZ - last.deltaZ;
        return dvx * dvx + dvy * dvy + dvz * dvz <= VEL_EPS_SQ;
    }

    private static Sample makeSample(Entity e, long now) {
        Sample s = new Sample();
        s.tick = now;
        s.x = e.getX();
        s.y = e.getY();
        s.z = e.getZ();
        s.yRot = e.getYRot();
        s.xRot = e.getXRot();
        Vec3 d = e.getDeltaMovement();
        s.deltaX = d.x;
        s.deltaY = d.y;
        s.deltaZ = d.z;
        return s;
    }

    public static void apply() {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        for (Map.Entry<UUID, Track> en : tracks.entrySet()) {
            Track t = en.getValue();
            if (t.samples.isEmpty()) continue;
            if (t.samples.getFirst().tick > now) continue;
            Sample s = sampleAt(t.samples, now);
            if (s == null) continue;
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(t.dim));
            if (level == null) continue;
            Entity e = level.getEntity(en.getKey());
            if (e == null || e.isRemoved()) continue;
            if (!VehicleJournal.isTracked(e)) continue;
            e.setDeltaMovement(Vec3.ZERO);
            e.moveTo(s.x, s.y, s.z, s.yRot, s.xRot);
            e.setDeltaMovement(s.deltaX, s.deltaY, s.deltaZ);
        }
    }

    private static Sample sampleAt(List<Sample> s, long tick) {
        if (s.isEmpty()) return null;
        if (tick <= s.get(0).tick) return s.get(0);
        int last = s.size() - 1;
        if (tick >= s.get(last).tick) return s.get(last);
        int lo = 0, hi = last;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long mt = s.get(mid).tick;
            if (mt == tick) return s.get(mid);
            if (mt < tick) lo = mid + 1;
            else hi = mid - 1;
        }
        return s.get(Math.max(0, lo - 1));
    }

    public static Vec3 positionAt(UUID id, long tick) {
        Track t = tracks.get(id);
        if (t == null || t.samples.isEmpty()) return null;
        Sample s = sampleAt(t.samples, tick);
        return s == null ? null : new Vec3(s.x, s.y, s.z);
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        if (ReplayState.isReplaying()) apply();
        else sample();
    }

    public static void onJump(long from, long to) {
    }

    public static void eraseVisits(Set<Integer> visitIds) {
    }

    public static void rebuildUpcoming() {
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_vehicle_trails.json");
    }

    public static void load() {
        tracks.clear();
        dirty = false;
        if (TimeTravel.worldFolder == null) return;
        Map<String, TrackData> loaded = JsonStore.load(file(), TRACKS_TYPE);
        if (loaded == null) return;
        for (Map.Entry<String, TrackData> en : loaded.entrySet()) {
            TrackData d = en.getValue();
            if (d == null || d.dim == null || d.samples == null) continue;
            Track t = new Track(d.dim);
            t.samples.addAll(d.samples);
            try {
                tracks.put(UUID.fromString(en.getKey()), t);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ReplayState.observe(t.lastTick());
        }
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        Map<String, TrackData> out = new HashMap<>();
        for (Map.Entry<UUID, Track> en : tracks.entrySet()) {
            TrackData d = new TrackData();
            d.dim = en.getValue().dim;
            d.samples = en.getValue().samples;
            out.put(en.getKey().toString(), d);
        }
        if (JsonStore.save(file(), out, TRACKS_TYPE)) dirty = false;
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    public static void clear() {
        tracks.clear();
        sampledThisTick.clear();
        dirty = false;
        save();
    }
}
