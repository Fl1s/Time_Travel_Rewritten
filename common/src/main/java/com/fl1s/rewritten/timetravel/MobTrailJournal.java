package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.mixin.CreeperAccessor;
import com.fl1s.rewritten.timetravel.mixin.LivingEntityAccessor;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.JsonStore;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dense per-tick trail of every mob within {@code entityTrackingDistance} of
 * any player (including ghosts), plus a coarse per-mob seed sample captured at
 * loop start.
 */
public final class MobTrailJournal {
    private MobTrailJournal() {}

    public static final class Sample {
        public long tick;
        public int visit;
        public double x, y, z;
        public float yRot, xRot, yHeadRot, yBodyRot;
        public float health;
        public boolean swinging;
        public float attackAnim;
        public boolean aggressive;
        public boolean usingItem;
        public int useItemRemaining;
        public String useItemNbt;
        public int activeHand;
        public int swellDir;
        public int swell;
    }

    private static final class Track {
        final String dim;
        final List<Sample> samples = new ArrayList<>();
        long end = -1;
        Track(String dim) { this.dim = dim; }
        long lastTick() { return Math.max(end, samples.isEmpty() ? -1 : samples.get(samples.size() - 1).tick); }
        long firstTick() { return samples.isEmpty() ? Long.MAX_VALUE : samples.get(0).tick; }
    }

    private static final class TrackData {
        String dim;
        long end;
        List<Sample> samples;
    }

    private static final Type TRACKS_TYPE = new TypeToken<HashMap<String, TrackData>>() {}.getType();
    private static final Map<UUID, Track> tracks = new HashMap<>();
    private static final Set<UUID> sampledThisTick = new HashSet<>();
    private static final Set<UUID> puppets = new HashSet<>();
    private static final Map<UUID, Boolean> originalNoAi = new HashMap<>();
    private static boolean wasReplaying = false;
    private static boolean dirty = false;

    public static boolean isReplaying() {
        return ReplayState.isReplaying();
    }

    public static boolean isPuppeted(Mob mob) {
        return puppets.contains(mob.getUUID());
    }

    static void seedFor(Mob mob, String dim, long now) {
        Track t = tracks.computeIfAbsent(mob.getUUID(), k -> new Track(dim));
        if (t.samples.isEmpty()) {
            t.samples.add(makeSample(mob, now));
            t.end = now;
        }
    }

    public static void seedInitial() {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob)) continue;
                if (Ghosts.is(mob)) continue;
                Track t = tracks.computeIfAbsent(mob.getUUID(), k -> new Track(dim));
                if (t.samples.isEmpty()) {
                    t.samples.add(makeSample(mob, now));
                    t.end = now;
                }
            }
        }
    }

    public static void onSpawnSeen(UUID id, String dim) {
        tracks.computeIfAbsent(id, k -> new Track(dim));
    }

    public static void sample() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;

        sampledThisTick.clear();
        double r = TimeTravel.entityTrackingDistance;
        double r2 = r * r;

        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            List<ServerPlayer> viewers = new ArrayList<>(level.players());
            if (viewers.isEmpty()) continue;

            for (ServerPlayer p : viewers) {
                List<Mob> nearby = level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r));
                for (Mob mob : nearby) {
                    if (Ghosts.is(mob)) continue;
                    UUID id = mob.getUUID();
                    if (!sampledThisTick.add(id)) continue;
                    if (p.distanceToSqr(mob) > r2) continue;
                    MobSpawnJournal.ensureRegistered(level, mob);
                    Track t = tracks.computeIfAbsent(id, k -> new Track(dim));
                    if (t.end == now) continue;

                    List<Sample> list = t.samples;
                    Sample last = list.isEmpty() ? null : list.get(list.size() - 1);

                    if (last != null && last.tick == now) {
                        t.end = now;
                        continue;
                    }
                    if (last != null && !mob.isUsingItem() && cheapSameState(last, mob)) {
                        PlayerData rec = BlockJournal.nearestRecorder(level, mob.blockPosition());
                        int visit = rec != null && rec.getCurrentVisit() != null ? rec.getCurrentVisit().id : -1;
                        if (visit == last.visit) {
                            t.end = now;
                            continue;
                        }
                    }
                    Sample s = makeSample(mob, now);
                    PlayerData rec = BlockJournal.nearestRecorder(level, mob.blockPosition());
                    s.visit = rec != null && rec.getCurrentVisit() != null ? rec.getCurrentVisit().id : -1;
                    if (list.isEmpty() || !sameState(list.get(list.size() - 1), s)) list.add(s);
                    t.end = now;
                    dirty = true;
                }
            }
        }

        ReplayState.observe(now);
    }

    private static boolean sameState(Sample a, Sample b) {
        return a.x == b.x && a.y == b.y && a.z == b.z
                && a.yRot == b.yRot && a.xRot == b.xRot
                && a.yHeadRot == b.yHeadRot && a.yBodyRot == b.yBodyRot
                && a.health == b.health && a.swinging == b.swinging
                && a.attackAnim == b.attackAnim && a.aggressive == b.aggressive
                && a.usingItem == b.usingItem && a.useItemRemaining == b.useItemRemaining
                && a.activeHand == b.activeHand
                && a.swellDir == b.swellDir && a.swell == b.swell;
    }

    private static boolean cheapSameState(Sample a, Mob mob) {
        return a.x == mob.getX() && a.y == mob.getY() && a.z == mob.getZ()
                && a.yRot == mob.getYRot() && a.xRot == mob.getXRot()
                && a.yHeadRot == mob.getYHeadRot() && a.yBodyRot == mob.yBodyRot
                && a.health == mob.getHealth()
                && a.swinging == mob.swinging
                && a.attackAnim == mob.attackAnim
                && a.aggressive == mob.isAggressive()
                && a.usingItem == mob.isUsingItem()
                && a.useItemRemaining == mob.getUseItemRemainingTicks()
                && a.activeHand == mob.getUsedItemHand().ordinal()
                && a.swellDir == (mob instanceof Creeper c ? c.getSwellDir() : 0)
                && a.swell == (mob instanceof Creeper c ? ((CreeperAccessor) c).tt$getSwell() : 0);
    }

    private static Sample makeSample(Mob mob, long now) {
        Sample s = new Sample();
        s.tick = now;
        s.visit = -1;
        s.x = mob.getX();
        s.y = mob.getY();
        s.z = mob.getZ();
        s.yRot = mob.getYRot();
        s.xRot = mob.getXRot();
        s.yHeadRot = mob.getYHeadRot();
        s.yBodyRot = mob.yBodyRot;
        s.health = mob.getHealth();
        s.swinging = mob.swinging;
        s.attackAnim = mob.attackAnim;
        s.aggressive = mob.isAggressive();
        s.usingItem = mob.isUsingItem();
        s.activeHand = mob.getUsedItemHand().ordinal();
        if (s.usingItem) {
            ItemStack use = mob.getUseItem();
            if (!use.isEmpty()) {
                s.useItemNbt = use.save(TimeTravel.server.registryAccess()).toString();
            }
            s.useItemRemaining = mob.getUseItemRemainingTicks();
        }
        if (mob instanceof Creeper creeper) {
            s.swellDir = creeper.getSwellDir();
            s.swell = ((CreeperAccessor) creeper).tt$getSwell();
        }
        return s;
    }

    public static void apply() {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;

        for (Map.Entry<UUID, Track> en : tracks.entrySet()) {
            Track t = en.getValue();
            if (t.samples.isEmpty()) continue;

            if (t.lastTick() < now) {
                releasePuppet(en.getKey());
                continue;
            }

            Sample s = sampleAt(t.samples, now);
            if (s == null) {
                releasePuppet(en.getKey());
                continue;
            }

            ServerLevel level = TimeTravel.server.getLevel(
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.dim)));
            if (level == null) continue;

            Entity e = findLoaded(level, en.getKey());
            if (!(e instanceof Mob mob) || mob.isRemoved()) continue;

            if (!puppets.contains(en.getKey())) {
                originalNoAi.put(en.getKey(), mob.isNoAi());
                puppets.add(en.getKey());
            }

            if (!MobJournal.isApplying()) {
                MobJournal.enterApplying();
                try {
                    applySample(mob, s);
                } finally {
                    MobJournal.exitApplying();
                }
            } else {
                applySample(mob, s);
            }
        }
    }

    private static void applySample(Mob mob, Sample s) {
        mob.setNoAi(true);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.fallDistance = 0;
        boolean doomed = MobJournal.isDoomed(mob);
        if (s.health <= 0) {
            if (doomed && !mob.isDeadOrDying()) {
                mob.setTarget(null);
                mob.getNavigation().stop();
                mob.setHealth(0);
                ItemFlowJournal.setSuppressDrop(true);
                try {
                    mob.die(mob.damageSources().generic());
                } finally {
                    ItemFlowJournal.setSuppressDrop(false);
                }
            }
            if (doomed) return;
        }
        if (doomed && (mob.isDeadOrDying() || mob.deathTime > 0)) {
            mob.deathTime = 0;
            mob.hurtTime = 0;
            mob.setPose(Pose.STANDING);
            mob.setHealth(1.0f);
        }
        float ceiling = s.health;
        if (mob.getHealth() > ceiling) {
            mob.setHealth(ceiling);
        }

        mob.setPos(s.x, s.y, s.z);
        mob.setYRot(s.yRot);
        mob.setXRot(s.xRot);
        mob.setYHeadRot(s.yHeadRot);
        mob.setYBodyRot(s.yBodyRot);

        mob.swinging = s.swinging;
        if (s.swinging && mob.swingTime == 0) mob.swingTime = 1;

        mob.setAggressive(s.aggressive);

        if (mob instanceof Creeper creeper) {
            if (creeper.getSwellDir() != s.swellDir) creeper.setSwellDir(s.swellDir);
            CreeperAccessor ca = (CreeperAccessor) creeper;
            if (Math.abs(ca.tt$getSwell() - s.swell) > 1) ca.tt$setSwell(s.swell);
        }

        LivingEntityAccessor acc = (LivingEntityAccessor) (Object) mob;
        acc.tt$setAttackAnim(s.attackAnim);
        acc.tt$setOAttackAnim(s.attackAnim);

        applyUseItem(mob, acc, s);
    }

    private static void applyUseItem(Mob mob, LivingEntityAccessor acc, Sample s) {
        if (s.usingItem && s.useItemNbt != null) {
            ItemStack current = acc.tt$getUseItem();
            boolean same = !current.isEmpty()
                    && current.save(TimeTravel.server.registryAccess()).toString().equals(s.useItemNbt);
            if (!same) {
                try {
                    CompoundTag tag = TagParser.parseTag(s.useItemNbt);
                    ItemStack stack = ItemStack.parse(TimeTravel.server.registryAccess(), tag)
                            .orElse(ItemStack.EMPTY);
                    if (!stack.isEmpty()) {
                        acc.tt$setUseItem(stack);
                        acc.tt$setUseItemRemaining(Math.max(1, s.useItemRemaining));
                        acc.tt$setLivingEntityFlag(1, true);
                    }
                } catch (Exception ignored) {
                }
            } else if (acc.tt$getUseItemRemaining() != s.useItemRemaining) {
                acc.tt$setUseItemRemaining(Math.max(1, s.useItemRemaining));
                acc.tt$setLivingEntityFlag(1, true);
            }
        } else {
            if (!acc.tt$getUseItem().isEmpty()) {
                acc.tt$setUseItem(ItemStack.EMPTY);
                acc.tt$setUseItemRemaining(0);
                acc.tt$setLivingEntityFlag(1, false);
            }
        }
    }

    private static Entity findLoaded(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid);
    }

    private static void releasePuppet(UUID id) {
        if (!puppets.remove(id)) return;
        Boolean orig = originalNoAi.remove(id);
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            Entity e = findLoaded(level, id);
            if (e instanceof Mob mob && !mob.isRemoved()) {
                mob.setNoAi(orig != null && orig);
                return;
            }
        }
    }

    private static void releaseAllPuppets() {
        for (UUID id : new ArrayList<>(puppets)) releasePuppet(id);
    }

    private static Sample sampleAt(List<Sample> s, long tick) {
        if (s.isEmpty()) return null;
        if (tick < s.get(0).tick) return null;
        int last = s.size() - 1;
        if (tick >= s.get(last).tick) return s.get(last);
        int lo = 0, hi = last;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long mt = s.get(mid).tick;
            if (mt == tick) return s.get(mid);
            if (mt < tick) lo = mid + 1;
            else hi = mid - 1;
        }
        return s.get(Math.max(0, lo - 1));
    }

    public static Vec3 positionAt(UUID id, long tick) {
        Track t = tracks.get(id);
        if (t == null || t.samples.isEmpty()) return null;
        Sample s = sampleAt(t.samples, tick);
        return s == null ? null : new Vec3(s.x, s.y, s.z);
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        boolean replaying = isReplaying();
        if (replaying) {
            wasReplaying = true;
            apply();
        } else {
            if (wasReplaying) {
                releaseAllPuppets();
                wasReplaying = false;
            }
            sample();
        }
    }

    public static void onJump(long from, long to) {
        if (to < from) {
            long now = TimeTravel.timelineTick;
            for (Map.Entry<UUID, Track> en : tracks.entrySet()) {
                Track t = en.getValue();
                if (t.samples.isEmpty()) continue;
                Sample s = sampleAt(t.samples, now);
                if (s == null) continue;

                ServerLevel level = TimeTravel.server.getLevel(
                        ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.dim)));
                if (level == null) continue;
                Entity e = level.getEntity(en.getKey());
                if (!(e instanceof Mob mob) || mob.isRemoved()) continue;

                mob.setHealth(s.health);
                if (mob.isDeadOrDying() || mob.deathTime > 0) {
                    mob.deathTime = 0;
                    mob.hurtTime = 0;
                    mob.setPose(Pose.STANDING);
                }
            }
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        releaseAllPuppets();
        for (Iterator<Map.Entry<UUID, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            Track t = it.next().getValue();
            t.samples.removeIf(s -> visitIds.contains(s.visit));
            if (t.samples.isEmpty()) it.remove();
        }
        dirty = true;
    }

    public static void rebuildUpcoming() {}

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_mob_trails.json");
    }

    public static void load() {
        tracks.clear();
        dirty = false;
        if (TimeTravel.worldFolder == null) return;
        Map<String, TrackData> loaded = JsonStore.load(file(), TRACKS_TYPE);
        if (loaded == null) return;
        for (Map.Entry<String, TrackData> en : loaded.entrySet()) {
            TrackData d = en.getValue();
            if (d == null || d.dim == null || d.samples == null) continue;
            Track t = new Track(d.dim);
            t.samples.addAll(d.samples);
            t.end = d.end;
            try {
                tracks.put(UUID.fromString(en.getKey()), t);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ReplayState.observe(t.lastTick());
        }
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        Map<String, TrackData> out = new HashMap<>();
        for (Map.Entry<UUID, Track> en : tracks.entrySet()) {
            TrackData d = new TrackData();
            d.dim = en.getValue().dim;
            d.end = en.getValue().end;
            d.samples = en.getValue().samples;
            out.put(en.getKey().toString(), d);
        }
        if (JsonStore.save(file(), out, TRACKS_TYPE)) dirty = false;
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    public static void clear() {
        tracks.clear();
        sampledThisTick.clear();
        puppets.clear();
        originalNoAi.clear();
        wasReplaying = false;
        save();
    }
}
