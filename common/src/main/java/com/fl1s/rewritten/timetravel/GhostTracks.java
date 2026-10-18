package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.util.Ghosts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.util.*;

public final class GhostTracks {
    private GhostTracks() {
    }

    public static void poll(ServerPlayer player, PlayerData pd) {
        Visit visit = pd.getCurrentVisit();
        if (visit == null) return;
        long tick = TimeTravel.timelineTick;

        int mode = player.gameMode.getGameModeForPlayer().getId();
        boolean flying = player.getAbilities().flying;
        if (visit.lastMode != mode || visit.lastFlying != flying) {
            visit.modes.add(new Visit.ModeChange(tick, mode, flying));
            visit.lastMode = mode;
            visit.lastFlying = flying;
        }

        Entity vehicle = player.getVehicle();
        String vehicleId = vehicle == null ? null : vehicle.getUUID().toString();
        if (!Objects.equals(visit.lastVehicle, vehicleId)) {
            visit.rides.add(new Visit.RideChange(tick, vehicleId));
            visit.lastVehicle = vehicleId;
        }

        if (visit.healths == null) visit.healths = new ArrayList<>();
        float health = player.getHealth();
        if (visit.lastHealth != health) {
            visit.healths.add(new Visit.HealthChange(tick, health));
            visit.lastHealth = health;
        }
    }

    public static void pollAll() {
        TimeTravel.forEachOnlineRecorded(GhostTracks::poll);
    }

    public static void onAttack(ServerPlayer player, Entity target) {
        if (!TimeTravel.isLooping || TimeTravel.loopSceneManager == null) return;
        if (Ghosts.is(player) || target instanceof Player) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(player.getGameProfile().getName());
        if (pd == null || !pd.getActive()) return;
        Visit visit = pd.getCurrentVisit();
        if (visit == null) return;
        visit.attacks.add(new Visit.AttackEvent(TimeTravel.timelineTick, target.getUUID().toString()));
    }

    public static void replay(Visit visit, ServerPlayer ghost, long now) {
        if (visit == null || ghost.isRemoved()) return;
        applyMode(visit, ghost, now);
        applyRide(visit, ghost, now);
        applyAttacks(visit, ghost, now);
    }

    private static Visit.HealthChange healthAt(Visit visit, long now) {
        List<Visit.HealthChange> list = visit.healths;
        if (list == null || list.isEmpty()) return null;
        Visit.HealthChange current = list.getFirst();
        for (Visit.HealthChange change : list) {
            if (change.tick > now) break;
            current = change;
        }
        return current;
    }

    public static void snapHealth(Visit visit, ServerPlayer ghost, long now) {
        Visit.HealthChange current = healthAt(visit, now);
        if (current == null) return;
        float wanted = Math.min(current.health, ghost.getMaxHealth());
        if (wanted > 0.0f && ghost.getHealth() != wanted) ghost.setHealth(wanted);
    }

    private static void applyHealth(Visit visit, ServerPlayer ghost, long now) {
        Visit.HealthChange current = healthAt(visit, now);
        if (current == null) return;
        float wanted = Math.min(current.health, ghost.getMaxHealth());
        if (wanted <= 0.0f) return;
        float actual = ghost.getHealth();
        if (actual > wanted || (current.tick == now && actual != wanted)) {
            ghost.setHealth(wanted);
        }
    }

    public static void applyHealthAll() {
        if (!TimeTravel.isLooping || TimeTravel.loopSceneManager == null) return;
        long now = TimeTravel.timelineTick;
        TimeTravel.loopSceneManager.forEachRecordingPlayer(pd -> {
            for (Visit visit : pd.getVisits()) {
                Entity e = TimeTravel.entityOfVisit(visit.id);
                if (e instanceof ServerPlayer ghost && Ghosts.is(ghost) && !ghost.isRemoved()) {
                    applyHealth(visit, ghost, now);
                }
            }
        });
    }

    private static void applyMode(Visit visit, ServerPlayer ghost, long now) {
        List<Visit.ModeChange> list = visit.modes;
        if (list == null || list.isEmpty()) return;
        Visit.ModeChange current = list.get(0);
        for (Visit.ModeChange change : list) {
            if (change.tick > now) break;
            current = change;
        }
        GameType type = GameType.byId(current.mode);
        if (ghost.gameMode.getGameModeForPlayer() != type) ghost.gameMode.changeGameModeForPlayer(type);
        if (ghost.getAbilities().mayfly && ghost.getAbilities().flying != current.flying) {
            ghost.getAbilities().flying = current.flying;
        }
    }

    private static void applyRide(Visit visit, ServerPlayer ghost, long now) {
        List<Visit.RideChange> list = visit.rides;
        if (list == null || list.isEmpty()) return;
        String wanted = null;
        for (Visit.RideChange change : list) {
            if (change.tick > now) break;
            wanted = change.vehicle;
        }
        Entity current = ghost.getVehicle();
        if (wanted == null) {
            if (current != null) ghost.stopRiding();
            return;
        }
        if (current != null && current.getUUID().toString().equals(wanted)) return;
        UUID id;
        try {
            id = UUID.fromString(wanted);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (!(ghost.level() instanceof ServerLevel level)) return;
        Entity vehicle = level.getEntity(id);
        if (vehicle == null || vehicle.isRemoved()) return;
        ghost.startRiding(vehicle, true);
    }

    private static void applyAttacks(Visit visit, ServerPlayer ghost, long now) {
        List<Visit.AttackEvent> list = visit.attacks;
        if (list == null || list.isEmpty()) return;
        if (!(ghost.level() instanceof ServerLevel level)) return;
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).tick < now) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo; i < list.size() && list.get(i).tick == now; i++) {
            UUID id;
            try {
                id = UUID.fromString(list.get(i).target);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Entity target = level.getEntity(id);
            if (target == null || target.isRemoved()) continue;
            double d2 = ghost.distanceToSqr(target);
            if (d2 > 36.0) continue;
            ghost.attack(target);
        }
    }
}
