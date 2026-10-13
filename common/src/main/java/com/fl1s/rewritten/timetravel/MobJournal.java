package com.fl1s.rewritten.timetravel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class MobJournal {
    private MobJournal() {}

    private static final double RANGE = 96.0;
    private static final long STEER_WINDOW = 1200;
    private static final long GLIDE_TICKS = 10;
    private static final double MAX_SPEED_MOD = 4.0;
    private static final double NEAR_PLAYER = 48.0;
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<Entry>>() {}.getType();

    public static final class Entry {
        long tick;
        long seq;
        int visit = -1;
        String dim;
        String uuid;
        String nbt;
        boolean explode;
        transient Vec3 deathPos;
    }

    private static final List<Entry> entries = new ArrayList<>();
    private static final Map<String, Entry> canon = new HashMap<>();
    private static final PriorityQueue<Entry> upcoming = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static long nextSeq = 0;
    private static boolean dirty = false;

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
        if (e.getClass().getName().contains("FakePlayer")) return true;
        return e instanceof PrimedTnt && e.getTags().contains(EntityJournal.TAG);
    }

    public static void onEntityDeath(LivingEntity entity, boolean explode) {
        if (!TimeTravel.isLooping || TimeTravel.server == null || TimeTravel.loopSceneManager == null) return;
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player) return;
        if (!entity.getType().canSerialize()) return;

        ServerPlayer nearest = null;
        double best = RANGE * RANGE;
        for (ServerPlayer p : level.players()) {
            if (p.getClass().getName().contains("FakePlayer")) continue;
            double d = p.distanceToSqr(entity);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest == null) return;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(nearest.getGameProfile().getName());
        if (pd == null || !pd.getActive()) return;

        String uuid = entity.getUUID().toString();
        entries.removeIf(old -> old.uuid.equals(uuid));
        upcoming.removeIf(old -> old.uuid.equals(uuid));

        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return;
        tag.putFloat("Health", entity.getMaxHealth());
        tag.putShort("DeathTime", (short) 0);
        tag.putShort("HurtTime", (short) 0);

        Entry entry = new Entry();
        entry.tick = TimeTravel.timelineTick;
        entry.seq = nextSeq++;
        entry.visit = pd.getCurrentVisit() != null ? pd.getCurrentVisit().id : -1;
        entry.dim = level.dimension().location().toString();
        entry.uuid = uuid;
        entry.nbt = tag.toString();
        entry.explode = explode;
        entries.add(entry);
        canon.put(uuid, entry);
        dirty = true;
    }

    public static void tick() {
        while (!upcoming.isEmpty() && upcoming.peek().tick <= TimeTravel.timelineTick) {
            apply(upcoming.poll(), true, true);
        }
        steer(TimeTravel.timelineTick % 5 == 0);
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
        for (Entry e : upcoming) {
            long remaining = e.tick - now;
            if (remaining <= 0 || remaining > STEER_WINDOW) continue;
            if (remaining > GLIDE_TICKS && !nav) continue;

            Vec3 target = deathPos(e);
            if (target == null) continue;

            ServerLevel level = TimeTravel.server.getLevel(
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(e.dim)));
            if (level == null) continue;
            if (!(level.getEntity(UUID.fromString(e.uuid)) instanceof Mob mob) || mob.isRemoved()) continue;

            Vec3 pos = mob.position();
            double dist = pos.distanceTo(target);
            if (dist < 1.5) continue;

            if (remaining <= GLIDE_TICKS) {
                mob.getNavigation().stop();
                if (level.getNearestPlayer(pos.x, pos.y, pos.z, NEAR_PLAYER, false) == null) {
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
            if (modifier > 0.6) {
                mob.getNavigation().moveTo(target.x, target.y, target.z,
                        Math.min(MAX_SPEED_MOD, Math.max(1.0, modifier)));
            }
        }
    }

    public static void onJump(long from, long to) {
        for (Entry e : canon.values()) {
            apply(e, e.tick <= to, false);
        }
        rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        rebuildCanon();
        upcoming.clear();
        for (Entry e : canon.values()) if (e.tick > TimeTravel.timelineTick) upcoming.add(e);
    }

    private static void rebuildCanon() {
        canon.clear();
        for (Entry e : entries) {
            canon.merge(e.uuid, e, (a, b) ->
                    (a.tick != b.tick ? a.tick < b.tick : a.seq < b.seq) ? a : b);
        }
    }

    public static int ownerVisit(UUID uuid) {
        Entry e = canon.get(uuid.toString());
        return (e != null && e.tick > TimeTravel.timelineTick) ? e.visit : -1;
    }

    private static void apply(Entry e, boolean die, boolean effects) {
        try {
            ServerLevel level = TimeTravel.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(e.dim)));
            if (level == null) return;

            Entity existing = level.getEntity(UUID.fromString(e.uuid));
            if (die) {
                if (existing != null && !existing.isRemoved()) {
                    if (effects && e.explode && existing instanceof Creeper) {
                        CompoundTag saved = TagParser.parseTag(e.nbt);
                        int radius = saved.contains("ExplosionRadius") ? saved.getByte("ExplosionRadius") : 3;
                        float power = radius * (saved.getBoolean("powered") ? 2.0f : 1.0f);
                        level.explode(existing, existing.getX(), existing.getY(), existing.getZ(), power, Level.ExplosionInteraction.MOB);
                    } else if (effects) {
                        level.broadcastEntityEvent(existing, (byte) 60);
                    }
                    existing.discard();
                }
                return;
            }

            if (existing != null) return;

            CompoundTag tag = TagParser.parseTag(e.nbt);
            ListTag pos = tag.getList("Pos", 6);
            if (pos.size() < 3 || !level.isLoaded(BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)))) return;

            TimeTravel.LOOP_LOGGER.info("Revive {} (visit {}) deathTick={} now={}", e.uuid, e.visit, e.tick, TimeTravel.timelineTick);
            Entity revived = EntityType.loadEntityRecursive(tag, level, entity -> entity);
            if (revived != null) level.addFreshEntity(revived);
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply mob journal entry {}", e.uuid, ex);
        }
    }

    public static boolean shouldShield(LivingEntity victim, DamageSource source) {
        if (!TimeTravel.isLooping) return false;
        if (!(victim instanceof ServerPlayer) || !victim.getClass().getName().contains("FakePlayer")) return false;
        return isDoomed(source.getEntity()) || isDoomed(source.getDirectEntity());
    }

    public static boolean isDoomed(Entity attacker) {
        if (!(attacker instanceof Mob)) return false;
        Entry e = canon.get(attacker.getUUID().toString());
        return e != null && e.tick > TimeTravel.timelineTick;
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        List<Entry> mine = new ArrayList<>();
        for (Entry e : entries) if (visitIds.contains(e.visit)) mine.add(e);
        mine.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
        for (Entry e : mine) if (e.tick <= TimeTravel.timelineTick) apply(e, false, false);
        entries.removeAll(mine);
        upcoming.removeAll(mine);
        rebuildCanon();
        dirty = true;
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_mobs.json");
    }

    public static void load() {
        entries.clear();
        upcoming.clear();
        canon.clear();
        nextSeq = 0;
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                List<Entry> loaded = GSON.fromJson(r, LIST_TYPE);
                if (loaded != null) {
                    entries.addAll(loaded);
                    for (Entry e : loaded) nextSeq = Math.max(nextSeq, e.seq + 1);
                }
            } catch (Exception ex) {
                TimeTravel.LOOP_LOGGER.error("Failed to load mob journal", ex);
            }
        }
        dirty = false;
        rebuildUpcoming();
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(entries, LIST_TYPE, w);
            dirty = false;
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to save mob journal", ex);
        }
    }

    public static void clear() {
        entries.clear();
        upcoming.clear();
        canon.clear();
        nextSeq = 0;
        dirty = true;
        save();
    }
}
