package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.entity.EntityJournal;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.Set;
import java.util.UUID;

/**
 * Facade over {@link MobSpawnJournal}, {@link MobTrailJournal} and
 * {@link MobEventJournal}. Preserves the public surface expected by mixins and
 * neighbouring journals while delegating all storage and replay logic.
 */
public final class MobJournal {
    private MobJournal() {}

    private static boolean killing = false;
    private static boolean applying = false;
    private static boolean dragonFinishing = false;

    public static boolean isKilling() { return killing; }
    public static boolean isApplying() { return applying; }
    public static void enterApplying() { applying = true; }
    public static void exitApplying() { applying = false; }
    public static void setKilling(boolean value) { killing = value; }

    public static void onDragonKilled(EnderDragon dragon) {
        dragonFinishing = true;
        try {
            recordDeath(dragon, false);
        } finally {
            dragonFinishing = false;
        }
    }

    public static void onWardenDug(Warden warden) {
        recordDeath(warden, false, true);
    }

    public static void onEntitySpawn(ServerLevel level, Entity entity) {
        if (Clones.isVisualOnly(entity)) return;
        MobSpawnJournal.onSpawn(level, entity);
        MobStateJournal.onSpawn(level, entity);
        if (entity instanceof Mob) {
            MobTrailJournal.onSpawnSeen(entity.getUUID(), level.dimension().location().toString());
        }
        if (MobTrailJournal.isBoss(entity)) MobTrailJournal.registerBoss(entity.getUUID());
    }

    public static void onEntityDeath(LivingEntity entity) {
        recordDeath(entity, false);
    }

    public static void onEntityDeath(LivingEntity entity, DamageSource src) {
        onEntityDeath(entity, src, false);
    }

    public static void onEntityDeath(LivingEntity entity, DamageSource src, boolean explode) {
        if (src != null && (isReplayEntity(src.getEntity()) || isReplayEntity(src.getDirectEntity()))) return;
        recordDeath(entity, explode);
    }

    public static void onEntityDeath(LivingEntity entity, boolean explode) {
        recordDeath(entity, explode);
    }

    private static void recordDeath(LivingEntity entity, boolean explode) {
        recordDeath(entity, explode, false);
    }

    private static void recordDeath(LivingEntity entity, boolean explode, boolean dig) {
        if (killing || applying) return;
        if (!TimeTravel.isLooping) return;
        if (MobTrailJournal.isReplaying() && isDoomed(entity)) return;
        if (entity instanceof net.minecraft.world.entity.player.Player) return;
        if (entity instanceof EnderDragon && !dragonFinishing) return;
        if (!entity.getType().canSerialize()) return;
        if (entity instanceof Mob deadMob && entity.level() instanceof ServerLevel deadLevel) {
            MobSpawnJournal.ensureRegistered(deadLevel, deadMob);
        }
        MobEventJournal.recordDeath(entity, explode, dig);
    }

    private static boolean isReplayEntity(Entity e) {
        if (e == null) return false;
        if (Clones.is(e)) return true;
        return e instanceof net.minecraft.world.entity.item.PrimedTnt
                && e.getTags().contains(EntityJournal.TAG);
    }

    public static boolean isDoomed(Entity attacker) {
        if (!(attacker instanceof Mob)) return false;
        MobEventJournal.Entry e = MobEventJournal.deathOf(attacker.getUUID());
        if (e == null) return false;
        return e.tick > TimeTravel.timelineTick;
    }

    public static int ownerVisit(UUID uuid) {
        MobEventJournal.Entry e = MobEventJournal.deathOf(uuid);
        if (e == null) return -1;
        return e.tick > TimeTravel.timelineTick ? e.visit : -1;
    }

    public static void fastForward(long from, long to) {
        MobEventJournal.fastForward(from, to);
    }

    public static void track(Mob mob, Entity who) {}

    public static void tick() {
        MobSpawnJournal.tick();
        MobTrailJournal.tick();
        MobEventJournal.tick();
        MobStateJournal.tick();
        WardenEventJournal.tick();
        WardenWarningJournal.tick();
    }

    public static void onJump(long from, long to) {
        MobSpawnJournal.onJump(from, to);
        MobTrailJournal.onJump(from, to);
        MobEventJournal.onJump(from, to);
        MobStateJournal.onJump(from, to);
        WardenEventJournal.onJump(from, to);
        WardenWarningJournal.onJump(from, to);
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        MobSpawnJournal.eraseVisits(visitIds);
        MobTrailJournal.eraseVisits(visitIds);
        MobEventJournal.eraseVisits(visitIds);
        MobStateJournal.eraseVisits(visitIds);
        WardenEventJournal.eraseVisits(visitIds);
        WardenWarningJournal.eraseVisits(visitIds);
        if (TimeTravel.server != null && TimeTravel.isLooping) {
            MobSpawnJournal.onJump(TimeTravel.timelineTick, TimeTravel.timelineTick);
            MobStateJournal.onJump(TimeTravel.timelineTick, TimeTravel.timelineTick);
        }
    }

    public static void rebuildUpcoming() {
        MobSpawnJournal.rebuildUpcoming();
        MobTrailJournal.rebuildUpcoming();
        MobEventJournal.rebuildUpcoming();
        MobStateJournal.rebuildUpcoming();
        WardenEventJournal.rebuildUpcoming();
        WardenWarningJournal.rebuildUpcoming();
    }

    public static void load() {
        MobSpawnJournal.load();
        MobTrailJournal.load();
        MobEventJournal.load();
        MobStateJournal.load();
        WardenEventJournal.load();
        WardenWarningJournal.load();
    }

    public static void save() {
        MobSpawnJournal.save();
        MobTrailJournal.save();
        MobEventJournal.save();
        MobStateJournal.save();
        WardenEventJournal.save();
        WardenWarningJournal.save();
    }

    public static void saveIfDirty() {
        MobSpawnJournal.saveIfDirty();
        MobTrailJournal.saveIfDirty();
        MobEventJournal.saveIfDirty();
        MobStateJournal.saveIfDirty();
        WardenEventJournal.saveIfDirty();
        WardenWarningJournal.saveIfDirty();
    }

    public static void clear() {
        MobSpawnJournal.clear();
        MobTrailJournal.clear();
        MobEventJournal.clear();
        MobStateJournal.clear();
        WardenEventJournal.clear();
        WardenWarningJournal.clear();
    }
}
