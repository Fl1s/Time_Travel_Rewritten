package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.util.Ghosts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

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

    public static boolean isKilling() { return killing; }
    public static boolean isApplying() { return applying; }
    public static void enterApplying() { applying = true; }
    public static void exitApplying() { applying = false; }
    public static void setKilling(boolean value) { killing = value; }

    public static void onEntitySpawn(ServerLevel level, Entity entity) {
        MobSpawnJournal.onSpawn(level, entity);
        if (entity instanceof Mob) {
            MobTrailJournal.onSpawnSeen(entity.getUUID(), level.dimension().location().toString());
        }
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
        if (killing || applying) return;
        if (!TimeTravel.isLooping) return;
        if (MobTrailJournal.isReplaying()
                && (!MobSpawnJournal.isOutOfTime(entity.getUUID()) || isDoomed(entity))) return;
        if (entity instanceof net.minecraft.world.entity.player.Player) return;
        if (!entity.getType().canSerialize()) return;
        MobEventJournal.recordDeath(entity, explode);
    }

    private static boolean isReplayEntity(Entity e) {
        if (e == null) return false;
        if (Ghosts.is(e)) return true;
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
    }

    public static void onJump(long from, long to) {
        MobSpawnJournal.onJump(from, to);
        MobTrailJournal.onJump(from, to);
        MobEventJournal.onJump(from, to);
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        MobSpawnJournal.eraseVisits(visitIds);
        MobTrailJournal.eraseVisits(visitIds);
        MobEventJournal.eraseVisits(visitIds);
        if (TimeTravel.server != null && TimeTravel.isLooping) {
            MobSpawnJournal.onJump(TimeTravel.timelineTick, TimeTravel.timelineTick);
        }
    }

    public static void rebuildUpcoming() {
        MobSpawnJournal.rebuildUpcoming();
        MobTrailJournal.rebuildUpcoming();
        MobEventJournal.rebuildUpcoming();
    }

    public static void load() {
        MobSpawnJournal.load();
        MobTrailJournal.load();
        MobEventJournal.load();
    }

    public static void save() {
        MobSpawnJournal.save();
        MobTrailJournal.save();
        MobEventJournal.save();
    }

    public static void saveIfDirty() {
        MobSpawnJournal.saveIfDirty();
        MobTrailJournal.saveIfDirty();
        MobEventJournal.saveIfDirty();
    }

    public static void clear() {
        MobSpawnJournal.clear();
        MobTrailJournal.clear();
        MobEventJournal.clear();
    }
}
