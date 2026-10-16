package com.fl1s.rewritten.timetravel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Set;

/**
 * Facade over vehicle tracking.
 */
public final class VehicleJournal {
    private VehicleJournal() {}

    public static boolean isTracked(Entity e) {
        return e instanceof net.minecraft.world.entity.vehicle.AbstractMinecart
                || e instanceof net.minecraft.world.entity.vehicle.Boat;
    }

    public static void onEntitySpawn(ServerLevel level, Entity entity) {}
    public static void tick() {}
    public static void onJump(long from, long to) {}
    public static void eraseVisits(Set<Integer> visitIds) {}
    public static void rebuildUpcoming() {}
    public static void load() {}
    public static void save() {}
    public static void saveIfDirty() {}
    public static void clear() {}
    public static void snapshotInitial() {}
}
