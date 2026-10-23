package com.fl1s.rewritten.timetravel.entity;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.ItemFlowJournal;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.mob.MobJournal;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class EntityJournal {
    private EntityJournal() {
    }

    public static final String TAG = "tt_replay";
    private static final double OWNER_MAX_DIST_SQ = 5.0 * 5.0;
    private static final Map<UUID, Entry> flying = new HashMap<>();

    public static final class Entry {
        String dim;
        String nbt;
        boolean item;
        long tick;
        long seq;
        int visit;
        int fuse;
        String owner;
        UUID uuid;
        long landTick = -1;
        String landNbt;
    }

    private record Due(long tick, PrimedTnt tnt) {
    }

    private record CloudRef(String dim, long tick) {
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_entities.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, CloudRef> clouds = new HashMap<>();
    private static final Map<UUID, String> live = new HashMap<>();
    private static boolean replaying = false;
    public static boolean suppress = false;

    private static boolean isTracked(Entity e) {
        if (e instanceof ExperienceOrb) return true;
        if (e instanceof PrimedTnt) return true;
        if (e instanceof ItemEntity item) return !(item.getOwner() instanceof Player);
        return false;
    }

    public static boolean shouldBlock(Entity e) {
        if (replaying) return false;
        if (ItemFlowJournal.isCloneKill()) return e instanceof ItemEntity || e instanceof ExperienceOrb;
        if (MobJournal.isKilling()) return e instanceof ItemEntity || e instanceof ExperienceOrb;
        if (suppress) return isTracked(e);
        if (BlockJournal.isApplying())
            return e instanceof PrimedTnt || e instanceof FallingBlockEntity || e instanceof ItemEntity;
        if (BlockJournal.isReplayingLive()) return e instanceof ItemEntity;
        return false;
    }

    public static void onSpawn(ServerLevel level, Entity entity) {
        if (!TimeTravel.isLooping || replaying || TimeTravel.timelineSceneManager == null) return;
        boolean orb = entity instanceof ExperienceOrb;
        if (ItemFlowJournal.isApplying()) return;
        if (ItemFlowJournal.isCapturingDeath() && !orb) return;
        if (orb && ItemFlowJournal.isCloneKill()) return;
        if (MobJournal.isApplying()) return;
        if (entity instanceof AreaEffectCloud) {
            clouds.put(entity.getUUID(), new CloudRef(level.dimension().location().toString(), TimeTravel.timelineTick));
            return;
        }
        if (!isTracked(entity)) return;
        if (entity instanceof Projectile shot && shot.getOwner() instanceof Mob && ReplayState.isReplaying()) return;
        PlayerData pd = BlockJournal.nearestRecorder(level, entity.blockPosition());
        if (pd == null) return;

        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) return;
        tag.remove("UUID");

        Entry e = new Entry();
        e.dim = level.dimension().location().toString();
        e.nbt = tag.toString();
        e.item = entity instanceof ItemEntity;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.uuid = entity.getUUID();
        if (entity instanceof PrimedTnt tnt) e.fuse = tnt.getFuse();
        if (entity instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            if (owner != null && !(owner instanceof Player)) e.owner = owner.getUUID().toString();
        }
        STORE.entries().add(e);
        live.put(entity.getUUID(), e.dim);
        if (entity instanceof AbstractArrow) flying.put(entity.getUUID(), e);
        STORE.markDirty();
    }

    public static void tick() {
        STORE.tick(e -> spawn(e, e.fuse, e.nbt));
        trackLanding();
    }

    private static void trackLanding() {
        if (flying.isEmpty() || replaying) return;
        Iterator<Map.Entry<UUID, Entry>> it = flying.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entry> en = it.next();
            Entry e = en.getValue();
            ServerLevel level = levelOf(e.dim);
            Entity arrow = level == null ? null : findLoaded(level, en.getKey());
            if (arrow == null || arrow.isRemoved()) {
                it.remove();
                continue;
            }
            CompoundTag t = new CompoundTag();
            if (arrow.save(t) && t.getBoolean("inGround")) {
                t.remove("UUID");
                e.landNbt = t.toString();
                e.landTick = TimeTravel.timelineTick;
                STORE.markDirty();
                it.remove();
            }
        }
    }

    private static ServerLevel levelOf(String dim) {
        return TimeTravel.server.getLevel(Levels.key(dim));
    }

    private static Entity findLoaded(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid);
    }

    private static boolean ownerPresent(ServerLevel level, Entry e) {
        try {
            Entity owner = level.getEntity(UUID.fromString(e.owner));
            if (owner == null) {
                net.minecraft.nbt.ListTag p = TagParser.parseTag(e.nbt).getList("Pos", 6);
                double x = p.getDouble(0), y = p.getDouble(1), z = p.getDouble(2);
                return !level.isLoaded(net.minecraft.core.BlockPos.containing(x, y, z));
            }
            return !owner.isRemoved() && owner.level() == level;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void spawn(Entry e, int fuse, String nbt) {
        if (e.item && TimeTravel.trackItems) return;
        ServerLevel level = levelOf(e.dim);
        if (level == null) return;
        if (e.owner != null && !ownerPresent(level, e)) return;
        replaying = true;
        try {
            if (e.fuse > 0) {
                CompoundTag t = TagParser.parseTag(e.nbt);
                net.minecraft.nbt.ListTag p = t.getList("Pos", 6);
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(p.getDouble(0) - 0.6, p.getDouble(1) - 0.6, p.getDouble(2) - 0.6, p.getDouble(0) + 0.6, p.getDouble(1) + 0.6, p.getDouble(2) + 0.6);
                if (!level.getEntitiesOfClass(PrimedTnt.class, box).isEmpty()) return;
            }
            CompoundTag tag = TagParser.parseTag(nbt);
            if (fuse > 0) tag.putShort("fuse", (short) fuse);
            tag.putBoolean("DropItem", false);
            Entity entity = EntityType.loadEntityRecursive(tag, level, en -> en);
            if (entity instanceof Projectile) return;
            if (entity == null) return;
            entity.setUUID(e.uuid);
            entity.addTag(TAG);
            if (level.addFreshEntity(entity)) {
                e.uuid = entity.getUUID();
                live.put(entity.getUUID(), e.dim);
                if (entity instanceof PrimedTnt && fuse == e.fuse) {
                    level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0f, 1.0f);
                }
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay entity", ex);
        } finally {
            replaying = false;
        }
    }

    public static void onJump() {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;

        for (Entry e : STORE.entries()) {
            ServerLevel level = levelOf(e.dim);
            if (level == null) continue;

            boolean shouldExist = e.tick <= now
                    && (e.fuse <= 0 || now < e.tick + e.fuse);
            Entity existing = findLoaded(level, e.uuid);

            boolean projectile = !e.item && e.fuse <= 0;
            if (projectile) {
                boolean alive = existing != null && !existing.isRemoved();
                if (e.tick > now) {
                    if (alive) existing.discard();
                    continue;
                }
                if (e.landTick >= 0 && e.landTick <= now) {
                    if (!alive) spawn(e, 0, e.landNbt);
                } else if (e.landTick > now && alive) {
                    existing.discard();
                    spawn(e, 0, e.nbt);
                }
                continue;
            }

            if (!shouldExist) {
                if (existing != null && !existing.isRemoved()) existing.discard();
                continue;
            }

            if (existing != null && !existing.isRemoved()) continue;

            if (e.fuse > 0) {
                int remaining = (int) (e.fuse - (now - e.tick));
                if (remaining > 0) spawn(e, remaining, e.nbt);
            } else {
                spawn(e, 0, e.nbt);
            }
        }

        live.entrySet().removeIf(en -> {
            ServerLevel level = levelOf(en.getValue());
            if (level == null) return true;
            Entity e = findLoaded(level, en.getKey());
            return e == null || e.isRemoved();
        });

        discardClouds();
        STORE.rebuildUpcoming();
    }

    public static void onJump(long from, long to) {
        onJump();
    }

    private static void discardLive() {
        for (Map.Entry<UUID, String> en : new ArrayList<>(live.entrySet())) {
            ServerLevel level = levelOf(en.getValue());
            if (level == null) continue;
            Entity entity = findLoaded(level, en.getKey());
            if (entity != null) entity.discard();
        }
        live.clear();
    }

    private static void discardClouds() {
        long now = TimeTravel.timelineTick;
        Iterator<Map.Entry<UUID, CloudRef>> it = clouds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, CloudRef> en = it.next();
            ServerLevel level = levelOf(en.getValue().dim());
            Entity cloud = level == null ? null : findLoaded(level, en.getKey());
            if (cloud == null || cloud.isRemoved()) {
                it.remove();
            } else if (en.getValue().tick() > now) {
                cloud.discard();
                it.remove();
            }
        }
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
    }

    public static void fastForward(long from, long to) {
        if (to <= from) return;
        PriorityQueue<Due> q = new PriorityQueue<>(Comparator.comparingLong(Due::tick));
        Set<UUID> seen = new HashSet<>();
        collect(from, to, q, seen);
        int guard = 0;
        try {
            while (!q.isEmpty() && guard++ < 512) {
                Due d = q.poll();
                if (d.tnt().isRemoved()) continue;
                TimeTravel.timelineTick = d.tick();
                d.tnt().setFuse(1);
                d.tnt().tick();
                collect(d.tick(), to, q, seen);
            }
        } finally {
            TimeTravel.timelineTick = from;
        }
    }

    private static void collect(long base, long to, PriorityQueue<Due> q, Set<UUID> seen) {
        for (Map.Entry<UUID, String> en : new ArrayList<>(live.entrySet())) {
            if (seen.contains(en.getKey())) continue;
            ServerLevel level = levelOf(en.getValue());
            if (level == null) continue;
            if (findLoaded(level, en.getKey()) instanceof PrimedTnt t) {
                seen.add(t.getUUID());
                long due = base + t.getFuse();
                if (due <= to) q.add(new Due(due, t));
            }
        }
    }

    public static void load() {
        live.clear();
        clouds.clear();
        STORE.load();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void save() {
        STORE.save();
    }

    public static void clear() {
        discardLive();
        clouds.clear();
        STORE.clear();
    }
}
