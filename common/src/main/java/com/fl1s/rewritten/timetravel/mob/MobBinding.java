package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.util.JsonStore;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MobBinding {
    private MobBinding() {}

    private static final double RANGE_SQ = 32.0 * 32.0;
    private static final Type MAP_TYPE = new TypeToken<HashMap<String, Integer>>() {}.getType();
    private static final Map<UUID, Integer> bound = new HashMap<>();
    private static boolean dirty = false;

    public static boolean allows(Mob mob, LivingEntity target) {
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.timelineSceneManager == null) return true;
        if (MobSpawnJournal.isOutOfTime(mob.getUUID()) && !MobTrailJournal.isPuppeted(mob)) return !Clones.is(target);
        int visit = TimeTravel.visitIdOf(target);
        if (visit < 0) return true;

        int owner = MobJournal.ownerVisit(mob.getUUID());
        if (owner >= 0) return visit == owner;

        Integer current = bound.get(mob.getUUID());
        if (current == null || current == visit || !ownerHolds(mob, current)) {
            bind(mob, visit);
            return true;
        }
        return false;
    }

    public static void onHurt(Mob mob, Entity attacker) {
        if (MobSpawnJournal.isOutOfTime(mob.getUUID()) && !MobTrailJournal.isPuppeted(mob)) return;
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.timelineSceneManager == null) return;
        if (!(mob.level() instanceof ServerLevel)) return;
        int visit = TimeTravel.visitIdOf(attacker);
        if (visit >= 0) bind(mob, visit);
    }

    private static void bind(Mob mob, int visit) {
        Integer old = bound.put(mob.getUUID(), visit);
        if (old == null || old != visit) dirty = true;
    }

    private static boolean ownerHolds(Mob mob, int visit) {
        Entity owner = TimeTravel.entityOfVisit(visit);
        if (owner == null) return TimeTravel.visitPending(visit);
        return owner.isAlive() && owner.level() == mob.level() && owner.distanceToSqr(mob) <= RANGE_SQ;
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        if (bound.values().removeIf(visitIds::contains)) dirty = true;
    }

    public static void prune() {
        if (bound.values().removeIf(id -> !TimeTravel.visitKnown(id))) dirty = true;
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_bindings.json");
    }

    public static void load() {
        bound.clear();
        Map<String, Integer> loaded = JsonStore.load(file(), MAP_TYPE);
        if (loaded != null) loaded.forEach((k, v) -> bound.put(UUID.fromString(k), v));
        dirty = false;
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        Map<String, Integer> out = new HashMap<>();
        bound.forEach((k, v) -> out.put(k.toString(), v));
        if (JsonStore.save(file(), out, MAP_TYPE)) dirty = false;
    }

    public static void clear() {
        bound.clear();
        dirty = true;
        save();
    }
}