package com.fl1s.rewritten.timetravel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Set;

public final class VehicleJournal {
    private VehicleJournal() {}

    public static boolean isTracked(Entity e) {
        return e instanceof net.minecraft.world.entity.vehicle.AbstractMinecart
                || e instanceof net.minecraft.world.entity.vehicle.Boat;
    }

    public static void onEntitySpawn(ServerLevel level, Entity entity) {
        if (!isTracked(entity)) return;
        VehicleSpawnJournal.onSpawn(level, entity);
        VehicleTrailJournal.onSpawnSeen(entity.getUUID(),
                level.dimension().location().toString());
    }

    public static void tick() {
        VehicleSpawnJournal.tick();
        VehicleTrailJournal.tick();
        VehicleContainerJournal.tick();
    }

    public static void onJump(long from, long to) {
        VehicleSpawnJournal.onJump(from, to);
        VehicleContainerJournal.onJump(from, to);
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        VehicleSpawnJournal.eraseVisits(visitIds);
        VehicleTrailJournal.eraseVisits(visitIds);
        VehicleContainerJournal.eraseVisits(visitIds);
    }

    public static void rebuildUpcoming() {
        VehicleSpawnJournal.rebuildUpcoming();
        VehicleTrailJournal.rebuildUpcoming();
        VehicleContainerJournal.rebuildUpcoming();
    }

    public static void load() {
        VehicleSpawnJournal.load();
        VehicleTrailJournal.load();
        VehicleContainerJournal.load();
    }

    public static void save() {
        VehicleSpawnJournal.save();
        VehicleTrailJournal.save();
        VehicleContainerJournal.save();
    }

    public static void saveIfDirty() {
        VehicleSpawnJournal.saveIfDirty();
        VehicleTrailJournal.saveIfDirty();
        VehicleContainerJournal.saveIfDirty();
    }

    public static void clear() {
        VehicleSpawnJournal.clear();
        VehicleTrailJournal.clear();
        VehicleContainerJournal.clear();
    }

    public static void snapshotInitial() {
        VehicleSpawnJournal.snapshotInitial();
        VehicleTrailJournal.seedInitial();
    }
}
