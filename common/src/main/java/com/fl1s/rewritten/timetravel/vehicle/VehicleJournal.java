package com.fl1s.rewritten.timetravel.vehicle;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;

import java.util.Set;

public final class VehicleJournal {
    private VehicleJournal() {
    }

    public static final TagKey<EntityType<?>> TRACKED_VEHICLES = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "tracked_vehicles"));

    public static boolean isTracked(Entity e) {
        if (e == null) return false;
        if (e instanceof AbstractMinecart || e instanceof Boat) return true;
        return e.getType().is(TRACKED_VEHICLES);
    }

    public static void onEntitySpawn(ServerLevel level, Entity entity) {
        if (!isTracked(entity)) return;
        VehicleSpawnJournal.onSpawn(level, entity);
        VehicleTrailJournal.onSpawnSeen(entity.getUUID(), level.dimension().location().toString());
    }

    public static void onRemoved(Entity e, Entity.RemovalReason reason) {
        if (!isTracked(e) || !reason.shouldDestroy()) return;
        VehicleSpawnJournal.onDestroyed(e);
    }

    public static void cosmeticHit(Entity e) {
        if (e instanceof AbstractMinecart m) {
            m.setHurtDir(-m.getHurtDir());
            m.setHurtTime(10);
            m.setDamage(Math.min(m.getDamage() + 10.0f, 30.0f));
        } else if (e instanceof Boat b) {
            b.setHurtDir(-b.getHurtDir());
            b.setHurtTime(10);
            b.setDamage(Math.min(b.getDamage() + 10.0f, 30.0f));
        }
    }

    public static void tick() {
        VehicleSpawnJournal.tick();
        VehicleTrailJournal.tick();
        VehicleContainerJournal.tick();
    }

    public static void onJump(long from, long to) {
        VehicleSpawnJournal.onJump(from, to);
        VehicleTrailJournal.onJump(from, to);
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
