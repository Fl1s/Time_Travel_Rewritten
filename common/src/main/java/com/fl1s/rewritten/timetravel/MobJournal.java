package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class MobJournal {
    private MobJournal() {}

    private static final double RANGE = 96.0;
    private static final long GLIDE_TICKS = 10;
    private static final long NAV_TICKS = 200;;
    private static final double MAX_SPEED_MOD = 4.0;
    private static final double NEAR_PLAYER = 48.0;
    private static final int TRAIL_STEP = 2;
    private static final int TRAIL_MAX = 600;
    private static final int TRAIL_TRACKED_MAX = 256;

    static final class Sample {
        long tick;
        double x, y, z;
        float yaw, pitch;
        boolean rot;
        boolean swing;
    }

    private static final class Trail {
        String dim;
        final List<Sample> samples = new ArrayList<>();
    }

    public static final class Entry {
        long tick;
        long seq;
        int visit = -1;
        String dim;
        UUID uuid;
        String nbt;
        boolean explode;
        List<Sample> trail;
        Long spawnTick;
        Double spawnX, spawnY, spawnZ;
        transient Vec3 deathPos;
    }

    private record Spawn(long tick, double x, double y, double z) {}

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_mobs.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick,
            e -> e.seq,
            e -> e.visit);

    private static final Map<UUID, Entry> canon = new HashMap<>();
    private static final Map<UUID, Trail> trails = new HashMap<>();
    private static final Map<UUID, Spawn> spawns = new HashMap<>();

    private static final PriorityQueue<Entry> pendingSpawns = new PriorityQueue<>(
            Comparator.comparingLong(e -> e.spawnTick == null ? Long.MAX_VALUE : e.spawnTick));

    private static boolean killing = false;

    public static boolean isKilling() {
        return killing;
    }

    public static void onEntityDeath(LivingEntity entity) {
        onEntityDeath(entity, false);
    }

    public static void onEntityDeath(LivingEntity entity, DamageSource src) {
        onEntityDeath(entity, src, false);
    }

    public static void onEntityDeath(LivingEntity entity, DamageSource src, boolean explode) {
        if (src != null && (isReplayEntity(src.getEntity()) || isReplayEntity(src.getDirectEntity()))) return;
        onEntityDeath(entity, explode);
    }

    private static boolean isReplayEntity(Entity e) {
        if (e == null) return false;
        if (Ghosts.is(e)) return true;
        return e instanceof PrimedTnt && e.getTags().contains(EntityJournal.TAG);
    }

    public static float capGhostDamage(LivingEntity victim, DamageSource src, float amount) {
        if (killing || !TimeTravel.isLooping) return amount;
        if (!(victim instanceof Mob) || !isDoomed(victim)) return amount;
        if (!isReplayEntity(src.getEntity()) && !isReplayEntity(src.getDirectEntity())) return amount;
        return Math.max(0.0f, Math.min(amount, victim.getHealth() - 1.0f));
    }

    public static void onEntitySpawn(ServerLevel level, Entity entity) {
        if (!TimeTravel.isLooping || !(entity instanceof Mob)) return;
        if (Ghosts.is(entity)) return;
        UUID uuid = entity.getUUID();
        if (spawns.containsKey(uuid) || canon.containsKey(uuid)) return;
        spawns.put(uuid, new Spawn(
                TimeTravel.timelineTick,
                entity.getX(), entity.getY(), entity.getZ()));
    }

    public static void onEntityDeath(LivingEntity entity, boolean explode) {
        if (killing) return;
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.loopSceneManager == null) return;
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player) return;
        if (!entity.getType().canSerialize()) return;

        ServerPlayer nearest = null;
        double best = RANGE * RANGE;
        for (ServerPlayer p : level.players()) {
            if (Ghosts.is(p)) continue;
            double d = p.distanceToSqr(entity);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest == null) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(nearest.getGameProfile().getName());
        if (pd == null || !pd.getActive()) return;

        UUID uuid = entity.getUUID();
        STORE.entries().removeIf(old -> old.uuid.equals(uuid));
        STORE.upcoming().removeIf(old -> old.uuid.equals(uuid));

        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return;
        tag.putFloat("Health", entity.getMaxHealth());
        tag.putShort("DeathTime", (short) 0);
        tag.putShort("HurtTime", (short) 0);

        Entry entry = new Entry();
        entry.tick = TimeTravel.timelineTick;
        entry.seq = STORE.nextSeq();
        entry.visit = pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
        entry.dim = level.dimension().location().toString();
        entry.uuid = uuid;
        entry.nbt = tag.toString();
        entry.explode = explode;

        Spawn newSpawn = spawns.remove(uuid);
        if (newSpawn != null) {
            entry.spawnTick = newSpawn.tick();
            entry.spawnX = newSpawn.x();
            entry.spawnY = newSpawn.y();
            entry.spawnZ = newSpawn.z();
        } else {
            Entry prev = canon.get(uuid);
            if (prev != null) {
                entry.spawnTick = prev.spawnTick;
                entry.spawnX = prev.spawnX;
                entry.spawnY = prev.spawnY;
                entry.spawnZ = prev.spawnZ;
            }
        }

        Trail trail = trails.remove(uuid);
        if (trail != null) {
            addSample(trail, entity);
            entry.trail = new ArrayList<>(trail.samples);
        }
        STORE.entries().add(entry);
        canon.put(uuid, entry);
        STORE.markDirty();
    }

    public static void track(Mob mob, Entity who) {
        if (!TimeTravel.isLooping || TimeTravel.loopSceneManager == null) return;
        if (!(who instanceof ServerPlayer sp) || Ghosts.is(sp)) return;
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (trails.containsKey(mob.getUUID()) || trails.size() >= TRAIL_TRACKED_MAX) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(sp.getGameProfile().getName());
        if (pd == null || !pd.getActive()) return;
        Trail t = new Trail();
        t.dim = level.dimension().location().toString();
        trails.put(mob.getUUID(), t);
        addSample(t, mob);
    }

    private static void addSample(Trail t, Entity m) {
        if (t.samples.size() >= TRAIL_MAX) t.samples.remove(0);
        Sample s = new Sample();
        s.tick = TimeTravel.timelineTick;
        s.x = m.getX();
        s.y = m.getY();
        s.z = m.getZ();
        s.yaw = m.getYRot();
        s.pitch = m.getXRot();
        s.rot = true;
        s.swing = m instanceof LivingEntity l && l.swinging && l.swingTime <= 1;
        t.samples.add(s);
    }

    private static void sampleTrails() {
        Iterator<Map.Entry<UUID, Trail>> it = trails.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Trail> en = it.next();
            Trail t = en.getValue();
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(t.dim));
            Entity m = level == null ? null : level.getEntity(en.getKey());
            if (m == null || m.isRemoved()) {
                it.remove();
                continue;
            }
            addSample(t, m);
        }
    }

    private static Sample sampleAt(List<Sample> s, long tick) {
        if (s == null || s.isEmpty()) return null;
        int hi = s.size() - 1;
        if (tick <= s.get(0).tick) return s.get(0);
        if (tick >= s.get(hi).tick) return s.get(hi);
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).tick <= tick) lo = mid;
            else hi = mid;
        }
        Sample a = s.get(lo);
        Sample b = s.get(hi);
        double f = b.tick == a.tick ? 1.0 : (double) (tick - a.tick) / (b.tick - a.tick);
        Sample r = new Sample();
        r.tick = tick;
        r.x = a.x + (b.x - a.x) * f;
        r.y = a.y + (b.y - a.y) * f;
        r.z = a.z + (b.z - a.z) * f;
        r.rot = a.rot && b.rot;
        r.yaw = Mth.rotLerp((float) f, a.yaw, b.yaw);
        r.pitch = Mth.lerp((float) f, a.pitch, b.pitch);
        r.swing = a.swing && tick - a.tick <= 1;
        return r;
    }

    private static Vec3 positionAt(Entry e, long tick) {
        boolean haveTrail = e.trail != null && !e.trail.isEmpty();
        if (haveTrail && tick >= e.trail.get(0).tick) {
            Sample s = sampleAt(e.trail, tick);
            if (s != null) return new Vec3(s.x, s.y, s.z);
        }
        if (e.spawnX != null && e.spawnY != null && e.spawnZ != null) {
            return new Vec3(e.spawnX, e.spawnY, e.spawnZ);
        }
        return null;
    }

    public static void tick() {
        while (!pendingSpawns.isEmpty()) {
            Entry e = pendingSpawns.peek();
            if (e.spawnTick == null || e.spawnTick > TimeTravel.timelineTick) break;
            pendingSpawns.poll();
            if (e.tick > TimeTravel.timelineTick) {
                apply(e, false, false);
            }
        }

        STORE.tick(e -> apply(e, true, true));
        drive();
        steer(TimeTravel.timelineTick % 5 == 0);
        if (TimeTravel.timelineTick % TRAIL_STEP == 0) sampleTrails();
    }

    private static void drive() {
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.upcoming()) {
            if (e.trail == null || e.trail.isEmpty() || now < e.trail.get(0).tick || now >= e.tick) continue;
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            if (!(level.getEntity(e.uuid) instanceof Mob mob) || !mob.isAlive()) continue;

            Sample s = sampleAt(e.trail, now);
            mob.getNavigation().stop();
            mob.setPos(s.x, s.y, s.z);
            mob.setDeltaMovement(Vec3.ZERO);
            mob.fallDistance = 0;
            if (s.rot) {
                mob.setYRot(s.yaw);
                mob.setXRot(s.pitch);
                mob.setYHeadRot(s.yaw);
                mob.setYBodyRot(s.yaw);
            }
            if (s.swing && !mob.swinging) mob.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static Vec3 deathPos(Entry e) {
        if (e.deathPos == null) {
            try {
                ListTag p = TagParser.parseTag(e.nbt).getList("Pos", 6);
                e.deathPos = new Vec3(p.getDouble(0), p.getDouble(1), p.getDouble(2));
            } catch (Exception ex) {
                return null;
            }
        }
        return e.deathPos;
    }

    private static void steer(boolean nav) {
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.upcoming()) {
            boolean trailed = e.trail != null && !e.trail.isEmpty();
            long due = trailed ? e.trail.get(0).tick : e.tick;
            long remaining = due - now;
            if (remaining <= 0 || remaining > NAV_TICKS) continue;
            if (remaining > GLIDE_TICKS && !nav) continue;

            Vec3 target = trailed ? positionAt(e, due) : deathPos(e);
            if (target == null) continue;

            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) continue;
            if (!(level.getEntity(e.uuid) instanceof Mob mob) || mob.isRemoved()) continue;

            Vec3 pos = mob.position();
            double dist = pos.distanceTo(target);
            if (dist < 1.5) continue;

            if (remaining <= GLIDE_TICKS) {
                mob.getNavigation().stop();
                if (!seenByRealPlayer(level, pos) && !seenByRealPlayer(level, target)) {
                    mob.teleportTo(target.x, target.y, target.z);
                } else {
                    Vec3 next = pos.add(target.subtract(pos).scale(1.0 / remaining));
                    mob.moveTo(next.x, next.y, next.z, mob.getYRot(), mob.getXRot());
                    mob.setDeltaMovement(Vec3.ZERO);
                }
                continue;
            }

            double base = Math.max(0.05, mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
            double needed = dist / (remaining - GLIDE_TICKS);
            double modifier = needed / base;
            mob.getNavigation().moveTo(target.x, target.y, target.z, Math.min(MAX_SPEED_MOD, Math.max(0.4, modifier)));
        }
    }

    private static boolean seenByRealPlayer(ServerLevel level, Vec3 p) {
        for (ServerPlayer sp : level.players()) {
            if (Ghosts.is(sp) || sp.isSpectator()) continue;
            if (sp.distanceToSqr(p) <= NEAR_PLAYER * NEAR_PLAYER) return true;
        }
        return false;
    }

    public static void onJump(long from, long to) {
        if (to < from) {
            for (Trail t : trails.values()) t.samples.removeIf(s -> s.tick > to);
        }
        for (Entry e : canon.values()) {
            apply(e, e.tick <= to, false);
        }
        rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        rebuildCanon();
        STORE.upcoming().clear();
        pendingSpawns.clear();
        for (Entry e : canon.values()) {
            if (e.tick > TimeTravel.timelineTick) {
                STORE.upcoming().add(e);
                if (e.spawnTick != null
                        && e.spawnTick > TimeTravel.timelineTick
                        && e.spawnTick < e.tick) {
                    pendingSpawns.add(e);
                }
            }
        }
    }

    private static void rebuildCanon() {
        canon.clear();
        for (Entry e : STORE.entries()) {
            canon.merge(e.uuid, e, (a, b) ->
                    (a.tick != b.tick ? a.tick < b.tick : a.seq < b.seq) ? a : b);
        }
    }

    public static int ownerVisit(UUID uuid) {
        Entry e = canon.get(uuid);
        return (e != null && e.tick > TimeTravel.timelineTick) ? e.visit : -1;
    }

    private static void slay(LivingEntity mob, ServerLevel level) {
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0f, 1.0f);
        killing = true;
        try {
            mob.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
        } finally {
            killing = false;
        }
        if (mob.isAlive()) mob.discard();
    }

    private static void apply(Entry e, boolean die, boolean effects) {
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) return;

            Entity existing = level.getEntity(e.uuid);
            if (die) {
                if (existing != null && !existing.isRemoved()) {
                    if (effects && e.explode && existing instanceof Creeper) {
                        CompoundTag saved = TagParser.parseTag(e.nbt);
                        int radius = saved.contains("ExplosionRadius") ? saved.getByte("ExplosionRadius") : 3;
                        float power = radius * (saved.getBoolean("powered") ? 2.0f : 1.0f);
                        level.explode(existing, existing.getX(), existing.getY(), existing.getZ(), power, Level.ExplosionInteraction.MOB);
                    } else if (effects && existing instanceof LivingEntity living) {
                        slay(living, level);
                        return;
                    }
                    existing.discard();
                }
                return;
            }
            if (e.spawnTick != null && TimeTravel.timelineTick < e.spawnTick) {
                if (existing != null && !existing.isRemoved()) existing.discard();
                return;
            }

            if (existing != null) {
                if (existing instanceof LivingEntity living && living.isDeadOrDying()) existing.discard();
                else return;
            }

            CompoundTag tag = TagParser.parseTag(e.nbt);
            Vec3 at = positionAt(e, TimeTravel.timelineTick);
            if (at != null) {
                ListTag moved = new ListTag();
                moved.add(DoubleTag.valueOf(at.x));
                moved.add(DoubleTag.valueOf(at.y));
                moved.add(DoubleTag.valueOf(at.z));
                tag.put("Pos", moved);
                ListTag still = new ListTag();
                still.add(DoubleTag.valueOf(0));
                still.add(DoubleTag.valueOf(0));
                still.add(DoubleTag.valueOf(0));
                tag.put("Motion", still);
            }
            ListTag pos = tag.getList("Pos", 6);
            if (pos.size() < 3 || !level.isLoaded(BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)))) return;

            TimeTravel.LOOP_LOGGER.info("Revive {} (visit {}) spawnTick={} deathTick={} now={}",
                    e.uuid, e.visit, e.spawnTick, e.tick, TimeTravel.timelineTick);
            Entity revived = EntityType.loadEntityRecursive(tag, level, entity -> entity);
            if (revived != null) level.addFreshEntity(revived);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply mob journal entry {}", e.uuid, ex);
        }
    }

    public static boolean shouldShield(LivingEntity victim, DamageSource source) {
        if (!TimeTravel.isLooping) return false;
        if (!(victim instanceof ServerPlayer) || !Ghosts.is(victim)) return false;
        return isDoomed(source.getEntity()) || isDoomed(source.getDirectEntity());
    }

    public static boolean isDoomed(Entity attacker) {
        if (!(attacker instanceof Mob)) return false;
        Entry e = canon.get(attacker.getUUID());
        return e != null && e.tick > TimeTravel.timelineTick;
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        long now = TimeTravel.timelineTick;
        List<Entry> mine = new ArrayList<>();
        for (Entry e : STORE.entries()) if (visitIds.contains(e.visit)) mine.add(e);
        mine.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
        for (Entry e : mine) {
            if (e.tick <= now) apply(e, false, false);
            else release(e);
        }
        STORE.entries().removeAll(mine);
        STORE.upcoming().removeAll(mine);
        rebuildUpcoming();
        STORE.markDirty();
    }

    private static void release(Entry e) {
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) return;
            if (level.getEntity(e.uuid) instanceof Mob mob && !mob.isRemoved()) {
                mob.getNavigation().stop();
                mob.setDeltaMovement(Vec3.ZERO);
                mob.fallDistance = 0;
            }
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to release mob {}", e.uuid, ex);
        }
    }

    public static void load() {
        canon.clear();
        trails.clear();
        spawns.clear();
        STORE.load();
        rebuildUpcoming();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void save() {
        STORE.save();
    }

    public static void clear() {
        canon.clear();
        trails.clear();
        spawns.clear();
        pendingSpawns.clear();
        STORE.clear();
    }
}