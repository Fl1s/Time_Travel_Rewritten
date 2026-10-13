package com.fl1s.rewritten.timetravel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class EntityJournal {
    private EntityJournal() {}

    public static final String TAG = "tt_replay";
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<Entry>>() {}.getType();

    public static final class Entry {
        String dim;
        String nbt;
        boolean item;
        long tick;
        long seq;
        int visit;
        int fuse;
        String owner;
    }

    private record Due(long tick, PrimedTnt tnt) {}

    private record CloudRef(String dim, long tick) {}

    private static final List<Entry> entries = new ArrayList<>();
    private static final Map<UUID, CloudRef> clouds = new HashMap<>();
    private static final PriorityQueue<Entry> upcoming = new PriorityQueue<>(
            Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private static final Map<UUID, String> live = new HashMap<>();
    private static long nextSeq = 0;
    private static boolean dirty = false;
    private static boolean replaying = false;
    public static boolean suppress = false;

    private static boolean isTracked(Entity e) {
        if (e instanceof PrimedTnt) return true;
        if (e instanceof ItemEntity item) return !(item.getOwner() instanceof Player);
        if (e instanceof Projectile) {
            return !(e instanceof ThrownEnderpearl || e instanceof FishingHook || e instanceof FireworkRocketEntity);
        }
        return false;
    }

    public static boolean shouldBlock(Entity e) {
        if (replaying) return false;
        if (MobJournal.isKilling()) return e instanceof ItemEntity || e instanceof ExperienceOrb;
        if (suppress) return isTracked(e);
        if (BlockJournal.isApplying()) return e instanceof PrimedTnt || e instanceof FallingBlockEntity || e instanceof ItemEntity;
        if (BlockJournal.isReplayingLive()) return e instanceof ItemEntity;
        return false;
    }

    public static void onSpawn(ServerLevel level, Entity entity) {
        if (!TimeTravel.isLooping || replaying || TimeTravel.loopSceneManager == null) return;
        if (entity instanceof AreaEffectCloud) {
            clouds.put(entity.getUUID(), new CloudRef(level.dimension().location().toString(), TimeTravel.timelineTick));
            return;
        }
        if (!isTracked(entity)) return;
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
        e.seq = nextSeq++;
        e.visit = pd.getCurrentVisit().id;
        if (entity instanceof PrimedTnt tnt) e.fuse = tnt.getFuse();
        if (entity instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            if (owner != null && !(owner instanceof Player)) e.owner = owner.getUUID().toString();
        }
        entries.add(e);
        live.put(entity.getUUID(), e.dim);
        dirty = true;
    }

    public static void tick() {
        while (!upcoming.isEmpty() && upcoming.peek().tick <= TimeTravel.timelineTick) {
            Entry next = upcoming.poll();
            spawn(next, next.fuse);
        }
    }

    private static ServerLevel levelOf(String dim) {
        return TimeTravel.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim)));
    }

    private static boolean ownerPresent(ServerLevel level, Entry e) {
        try {
            Entity owner = level.getEntity(UUID.fromString(e.owner));
            if (owner == null) {
                net.minecraft.nbt.ListTag p = TagParser.parseTag(e.nbt).getList("Pos", 6);
                return !level.isLoaded(net.minecraft.core.BlockPos.containing(p.getDouble(0), p.getDouble(1), p.getDouble(2)));
            }
            return !owner.isRemoved() && !(owner instanceof LivingEntity living && living.isDeadOrDying());
        } catch (Exception ex) {
            return true;
        }
    }

    private static void spawn(Entry e, int fuse) {
        if (e.item && TimeTravel.trackItems) return;
        ServerLevel level = levelOf(e.dim);
        if (level == null) return;
        if (e.owner != null && !ownerPresent(level, e)) return;
        replaying = true;
        try {
            if (e.fuse > 0) {
                CompoundTag t = TagParser.parseTag(e.nbt);
                net.minecraft.nbt.ListTag p = t.getList("Pos", 6);
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(p.getDouble(0) - 0.6, p.getDouble(1) - 0.6, p.getDouble(2) - 0.6,
                        p.getDouble(0) + 0.6, p.getDouble(1) + 0.6, p.getDouble(2) + 0.6);
                if (!level.getEntitiesOfClass(PrimedTnt.class, box).isEmpty()) return;
            }
            CompoundTag tag = TagParser.parseTag(e.nbt);
            if (fuse > 0) tag.putShort("fuse", (short) fuse);
            tag.putBoolean("DropItem", false);
            Entity entity = EntityType.loadEntityRecursive(tag, level, en -> en);
            if (entity == null) return;
            entity.addTag(TAG);
            if (level.addFreshEntity(entity)) {
                live.put(entity.getUUID(), e.dim);
                if (entity instanceof PrimedTnt && fuse == e.fuse) {
                    level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                            SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0f, 1.0f);
                }
            }
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to replay entity", ex);
        } finally {
            replaying = false;
        }
    }

    public static void onJump() {
        discardLive();
        discardClouds();
        rebuildUpcoming();
        long now = TimeTravel.timelineTick;
        for (Entry e : new ArrayList<>(entries)) {
            if (e.fuse > 0 && e.tick <= now && e.tick + e.fuse > now) {
                spawn(e, (int) (e.fuse - (now - e.tick)));
            }
        }
    }

    private static void discardLive() {
        for (Map.Entry<UUID, String> en : new ArrayList<>(live.entrySet())) {
            ServerLevel level = levelOf(en.getValue());
            if (level == null) continue;
            Entity entity = level.getEntity(en.getKey());
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
            Entity cloud = level == null ? null : level.getEntity(en.getKey());
            if (cloud == null || cloud.isRemoved()) {
                it.remove();
            } else if (en.getValue().tick() > now) {
                cloud.discard();
                it.remove();
            }
        }
    }

    public static void rebuildUpcoming() {
        upcoming.clear();
        for (Entry e : entries) {
            if (e.tick > TimeTravel.timelineTick) upcoming.add(e);
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        entries.removeIf(e -> visitIds.contains(e.visit));
        upcoming.removeIf(e -> visitIds.contains(e.visit));
        dirty = true;
    }

    public static void fastForward(long from, long to) {
        if (to <= from) return;
        PriorityQueue<Due> q = new PriorityQueue<>(Comparator.comparingLong(Due::tick));
        Set<UUID> seen = new HashSet<>();
        collect(from, to, q, seen);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 512) {
            Due d = q.poll();
            if (d.tnt().isRemoved()) continue;
            TimeTravel.timelineTick = d.tick();
            d.tnt().setFuse(1);
            d.tnt().tick();
            collect(d.tick(), to, q, seen);
        }
        TimeTravel.timelineTick = from;
    }

    private static void collect(long base, long to, PriorityQueue<Due> q, Set<UUID> seen) {
        for (Map.Entry<UUID, String> en : new ArrayList<>(live.entrySet())) {
            if (seen.contains(en.getKey())) continue;
            ServerLevel level = levelOf(en.getValue());
            if (level == null) continue;
            if (level.getEntity(en.getKey()) instanceof PrimedTnt t) {
                seen.add(t.getUUID());
                long due = base + t.getFuse();
                if (due <= to) q.add(new Due(due, t));
            }
        }
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_entities.json");
    }

    public static void load() {
        entries.clear();
        upcoming.clear();
        live.clear();
        clouds.clear();
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
                TimeTravel.LOOP_LOGGER.error("Failed to load entity journal", ex);
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
            TimeTravel.LOOP_LOGGER.error("Failed to save entity journal", ex);
        }
    }

    public static void clear() {
        discardLive();
        clouds.clear();
        entries.clear();
        upcoming.clear();
        nextSeq = 0;
        dirty = true;
        save();
    }
}