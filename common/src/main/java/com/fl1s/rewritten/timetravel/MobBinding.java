package com.fl1s.rewritten.timetravel;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MobBinding {
    private MobBinding() {}

    private static final double RANGE_SQ = 32.0 * 32.0;
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<HashMap<String, Integer>>() {}.getType();
    private static final Map<UUID, Integer> bound = new HashMap<>();
    private static boolean dirty = false;

    public static boolean allows(Mob mob, LivingEntity target) {
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.loopSceneManager == null) return true;
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
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.loopSceneManager == null) return;
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
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                Map<String, Integer> loaded = GSON.fromJson(r, MAP_TYPE);
                if (loaded != null) loaded.forEach((k, v) -> bound.put(UUID.fromString(k), v));
            } catch (Exception ex) {
                TimeTravel.LOOP_LOGGER.error("Failed to load mob bindings", ex);
            }
        }
        dirty = false;
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        Map<String, Integer> out = new HashMap<>();
        bound.forEach((k, v) -> out.put(k.toString(), v));
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(out, MAP_TYPE, w);
            dirty = false;
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to save mob bindings", ex);
        }
    }

    public static void clear() {
        bound.clear();
        dirty = true;
        save();
    }
}