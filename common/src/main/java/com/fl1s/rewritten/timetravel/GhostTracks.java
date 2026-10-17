package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.util.Ghosts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

    public static void onGhostSwing(ServerPlayer ghost) {
        if (!TimeTravel.isLooping || !(ghost.level() instanceof ServerLevel level)) return;
        Vec3 eye = ghost.getEyePosition();
        Vec3 end = eye.add(ghost.getViewVector(1.0f).scale(3.0));
        for (ServerPlayer p : level.players()) {
            if (p == ghost || p.isSpectator() || !p.isAlive()) continue;
            if (p.getBoundingBox().inflate(0.1).clip(eye, end).isEmpty()) continue;
            p.hurt(level.damageSources().playerAttack(ghost), (float) ghost.getAttributeValue(Attributes.ATTACK_DAMAGE));
            return;
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
            ghost.attack(target);
        }
    }
}
