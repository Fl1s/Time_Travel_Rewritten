package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.util.JsonStore;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CarryTrailJournal {

    private CarryTrailJournal() {
    }

    public static final class Sample {
        public long tick;
        public double x, y, z;
        public float yRot, xRot, yHeadRot;
    }

    public static final class Trail {
        public UUID source;
        public String type;
        public String dim;
        public long end = Long.MIN_VALUE;
        public List<Sample> samples = new ArrayList<>();
    }

    private static final Type TYPE = new TypeToken<HashMap<UUID, Trail>>() {
    }.getType();
    private static final double POS_EPS_SQ = 1.0e-4;
    private static final float ROT_EPS = 0.01f;

    private static final Map<UUID, Trail> trails = new HashMap<>();
    private static boolean dirty;

    public static boolean has(UUID trip) {
        return trails.containsKey(trip);
    }

    public static Map<UUID, Trail> trails() {
        return Collections.unmodifiableMap(trails);
    }

    static void open(UUID trip, Entity entity) {
        Trail trail = new Trail();
        trail.source = entity.getUUID();
        trail.type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        trail.dim = Levels.canonicalId(entity.level());
        trails.put(trip, trail);
        dirty = true;
    }

    static void record(UUID trip, Entity entity, long tick) {
        Trail trail = trails.get(trip);
        if (trail == null) return;
        Sample s = new Sample();
        s.tick = tick;
        s.x = entity.getX();
        s.y = entity.getY();
        s.z = entity.getZ();
        s.yRot = entity.getYRot();
        s.xRot = entity.getXRot();
        s.yHeadRot = entity.getYHeadRot();
        if (tick <= trail.end) return;
        trail.end = tick;
        dirty = true;
        List<Sample> samples = trail.samples;
        if (!samples.isEmpty()) {
            Sample last = samples.get(samples.size() - 1);
            double dx = s.x - last.x, dy = s.y - last.y, dz = s.z - last.z;
            if (dx * dx + dy * dy + dz * dz <= POS_EPS_SQ && Math.abs(s.yRot - last.yRot) <= ROT_EPS
                    && Math.abs(s.xRot - last.xRot) <= ROT_EPS
                    && Math.abs(s.yHeadRot - last.yHeadRot) <= ROT_EPS) return;
        }
        samples.add(s);
    }

    public static Sample sampleAt(Trail trail, long tick) {
        List<Sample> s = trail.samples;
        if (s.isEmpty() || tick < s.get(0).tick || tick > trail.end) return null;
        int lo = 0, hi = s.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (s.get(mid).tick <= tick) lo = mid;
            else hi = mid - 1;
        }
        return s.get(lo);
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_carry_trails.json");
    }

    public static void load() {
        trails.clear();
        dirty = false;
        if (TimeTravel.worldFolder == null) return;
        Map<UUID, Trail> loaded = JsonStore.load(file(), TYPE);
        if (loaded == null) return;
        loaded.forEach((trip, trail) -> {
            if (trail != null && trail.samples != null && trail.type != null) trails.put(trip, trail);
        });
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        if (JsonStore.save(file(), trails, TYPE)) dirty = false;
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    public static void clear() {
        trails.clear();
        dirty = true;
        save();
    }
}
