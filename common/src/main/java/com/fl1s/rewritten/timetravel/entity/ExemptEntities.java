package com.fl1s.rewritten.timetravel.entity;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.clone.HeadCarry;
import com.fl1s.rewritten.timetravel.clone.HeadTracking;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.journal.ItemFlowJournal;
import com.fl1s.rewritten.timetravel.journal.ProjectileJournal;
import com.fl1s.rewritten.timetravel.journal.TrackedEntityJournal;
import com.fl1s.rewritten.timetravel.mob.MobJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleSpawnJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ExemptEntities {
    private ExemptEntities() {
    }

    private static final int SWEEP_INTERVAL = 20;
    private static Set<UUID> settled = new HashSet<>();

    public static void born(Entity entity) {
        settled.add(entity.getUUID());
    }

    public static boolean set(Entity entity, boolean exempt) {
        if (Exempt.is(entity) == exempt) return false;
        Exempt.mark(entity, exempt);
        if (Exempt.is(entity) != exempt) return false;
        if (exempt) {
            forget(entity);
        } else {
            settled.remove(entity.getUUID());
            if (TimeTravel.isLooping && VehicleJournal.isTracked(entity)) VehicleSpawnJournal.snapshotInitial();
        }
        HeadTracking.markDirty();
        return true;
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        if (TimeTravel.server.getTickCount() % SWEEP_INTERVAL != 0) return;
        Set<UUID> seen = new HashSet<>();
        boolean changed = false;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!Exempt.is(entity)) continue;
                seen.add(entity.getUUID());
                if (settled.contains(entity.getUUID())) continue;
                forget(entity);
                changed = true;
            }
        }
        settled = seen;
        if (changed) HeadTracking.markDirty();
    }

    private static void forget(Entity entity) {
        UUID id = entity.getUUID();
        settled.add(id);
        EntityJournal.forget(id);
        ProjectileJournal.forget(id);
        TrackedEntityJournal.forget(id);
        EntityStateJournal.forget(id);
        EntityContainerJournal.forget(id);
        ItemFlowJournal.forget(id);
        MobJournal.forget(id);
        VehicleJournal.forget(id);
        HeadCarry.forget(entity);
    }
}
