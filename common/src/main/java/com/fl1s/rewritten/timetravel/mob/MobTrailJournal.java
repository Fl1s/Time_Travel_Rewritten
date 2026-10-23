package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.BossFightJournal;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.mixin.CreeperAccessor;
import com.fl1s.rewritten.timetravel.mixin.LivingEntityAccessor;
import com.fl1s.rewritten.timetravel.data.Clones;
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
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;

public final class MobTrailJournal {
    private MobTrailJournal() {
    }

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
        public boolean angry;
        public String useItemNbt;
        public int activeHand;
        public int swellDir;
        public int swell;
        public int invul;
        public int phase = -1;
        public int pose = -1;
        public int clientAnger = -1;
    }

    private static final class Track {
        final String dim;
        ResourceKey<Level> key;
        final List<Sample> samples = new ArrayList<>();
        long end = -1;

        Track(String dim) {
            this.dim = dim;
        }

        long lastTick() {
            return Math.max(end, samples.isEmpty() ? -1 : samples.get(samples.size() - 1).tick);
        }

        long firstTick() {
            return samples.isEmpty() ? Long.MAX_VALUE : samples.get(0).tick;
        }
    }

    private static final class TrackData {
        String dim;
        long end;
        List<Sample> samples;
    }

    private static final long GRACE = 200;
    private static final Type TRACKS_TYPE = new TypeToken<HashMap<String, TrackData>>() {
    }.getType();
    private static final Map<UUID, Track> tracks = new HashMap<>();
    private static final Set<UUID> sampledThisTick = new HashSet<>();
    private static final Set<UUID> puppets = new HashSet<>();
    private static final Set<UUID> spawnedPuppets = new HashSet<>();
    private static final String PUPPET_TAG = "tt_puppet";
    private static final String ORIG_NOAI_TAG = "tt_orig_noai";
    private static final Map<UUID, Boolean> originalNoAi = new HashMap<>();
    private static final Set<UUID> bosses = new HashSet<>();
    private static final Map<UUID, Float> lastBossHealth = new HashMap<>();
    private static boolean wasReplaying = false;
    private static boolean dirty = false;

    public static boolean isReplaying() {
        return ReplayState.isReplaying();
    }

    public static boolean isPuppeted(Mob mob) {
        return puppets.contains(mob.getUUID());
    }

    public static void markSpawned(Entity e) {
        if (e == null) return;
        spawnedPuppets.add(e.getUUID());
        if (!puppets.contains(e.getUUID())) {
            puppets.add(e.getUUID());
            if (e instanceof Mob mob) {
                boolean orig = mob.isNoAi();
                originalNoAi.put(e.getUUID(), orig);
                mob.addTag(PUPPET_TAG);
                if (orig) mob.addTag(ORIG_NOAI_TAG);
            }
        }
    }

    private static boolean angryOf(Mob mob) {
        return mob instanceof NeutralMob n && n.getRemainingPersistentAngerTime() > 0;
    }

    public static boolean isBoss(Entity entity) {
        return entity instanceof EnderDragon || entity instanceof WitherBoss;
    }

    static void registerBoss(UUID id) {
        bosses.add(id);
    }

    private static int phaseOf(Mob mob) {
        return mob instanceof EnderDragon dragon ? dragon.getPhaseManager().getCurrentPhase().getPhase().getId() : -1;
    }

    private static void applyBossState(Mob mob, Sample s) {
        if (mob instanceof WitherBoss wither && wither.getInvulnerableTicks() != s.invul) {
            wither.setInvulnerableTicks(s.invul);
        }
        if (mob instanceof EnderDragon dragon && s.phase >= 0 && phaseOf(dragon) != s.phase) {
            dragon.getPhaseManager().setPhase(EnderDragonPhase.getById(s.phase));
        }
    }

    private static void discoverBosses() {
        if (TimeTravel.server == null) return;
        bosses.clear();
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (isBoss(entity) && !entity.isRemoved()) bosses.add(entity.getUUID());
            }
        }
    }

    private static List<Mob> candidates(ServerLevel level, ServerPlayer viewer, double r) {
        List<Mob> list = new ArrayList<>(level.getEntitiesOfClass(Mob.class, viewer.getBoundingBox().inflate(r)));
        for (UUID id : bosses) {
            if (level.getEntity(id) instanceof Mob boss && !list.contains(boss)) list.add(boss);
        }
        return list;
    }

    private static ServerLevel levelOf(Track t) {
        if (t.key == null) t.key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.dim));
        return TimeTravel.server.getLevel(t.key);
    }

    private static int visitAt(ServerLevel level, Mob mob) {
        PlayerData rec = BlockJournal.nearestRecorder(level, mob.blockPosition());
        return rec != null && rec.getCurrentVisit() != null ? rec.getCurrentVisit().id : -1;
    }

    static void seedFor(Mob mob, String dim, long now) {
        if (mob.isDeadOrDying()) return;
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
                if (!(entity instanceof Mob mob) || Clones.is(mob)) continue;
                if (Clones.isVisualOnly(mob)) continue;
                seedFor(mob, dim, now);
            }
        }
    }

    public static void onSpawnSeen(UUID id, String dim) {
        tracks.computeIfAbsent(id, k -> new Track(dim));
    }

    public static void sample() {
        sample(false);
    }

    private static void sample(boolean onlyOutOfTime) {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;

        if (now % 100 == 0) discoverBosses();
        sampledThisTick.clear();
        double r = TimeTravel.entityTrackingDistance;
        double r2 = r * r;

        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            List<ServerPlayer> viewers = new ArrayList<>(level.players());
            if (viewers.isEmpty()) continue;

            for (ServerPlayer p : viewers) {
                for (Mob mob : candidates(level, p, r)) {
                    if (Clones.is(mob)) continue;
                    if (Clones.isVisualOnly(mob)) continue;
                    if (mob.isDeadOrDying()) continue;
                    UUID id = mob.getUUID();
                    if (isBoss(mob)) bosses.add(id);
                    if (onlyOutOfTime) {
                        Track existing = tracks.get(id);
                        if (existing != null && existing.lastTick() >= now) continue;
                    }
                    if (!isBoss(mob) && p.distanceToSqr(mob) > r2) continue;
                    if (!sampledThisTick.add(id)) continue;

                    MobSpawnJournal.ensureRegistered(level, mob);
                    Track t = tracks.computeIfAbsent(id, k -> new Track(dim));
                    if (t.end == now) continue;

                    List<Sample> list = t.samples;
                    Sample last = list.isEmpty() ? null : list.get(list.size() - 1);
                    if (last != null && last.tick > now) continue;
                    if (last != null && last.tick == now) {
                        t.end = now;
                        continue;
                    }

                    int visit = visitAt(level, mob);
                    if (last != null && !mob.isUsingItem() && visit == last.visit && cheapSameState(last, mob)) {
                        t.end = now;
                        continue;
                    }
                    Sample s = makeSample(mob, now);
                    s.visit = visit;
                    if (last == null || !sameState(last, s)) {
                        list.add(s);
                        dirty = true;
                    }
                    t.end = now;
                }
            }
        }

        if (!onlyOutOfTime) ReplayState.observe(now);
    }

    private static boolean sameState(Sample a, Sample b) {
        return a.x == b.x && a.y == b.y && a.z == b.z && a.yRot == b.yRot && a.xRot == b.xRot && a.yHeadRot == b.yHeadRot && a.yBodyRot == b.yBodyRot && a.health == b.health && a.swinging == b.swinging && a.attackAnim == b.attackAnim && a.aggressive == b.aggressive && a.usingItem == b.usingItem && a.useItemRemaining == b.useItemRemaining && a.angry == b.angry && a.activeHand == b.activeHand && a.swellDir == b.swellDir && a.swell == b.swell && a.invul == b.invul && a.phase == b.phase && a.pose == b.pose && a.clientAnger == b.clientAnger;
    }

    private static boolean cheapSameState(Sample a, Mob mob) {
        return a.x == mob.getX() && a.y == mob.getY() && a.z == mob.getZ() && a.yRot == mob.getYRot() && a.xRot == mob.getXRot() && a.yHeadRot == mob.getYHeadRot() && a.yBodyRot == mob.yBodyRot && a.angry == angryOf(mob) && a.health == mob.getHealth() && a.swinging == mob.swinging && a.attackAnim == mob.attackAnim && a.aggressive == mob.isAggressive() && a.usingItem == mob.isUsingItem() && a.useItemRemaining == mob.getUseItemRemainingTicks() && a.activeHand == mob.getUsedItemHand().ordinal() && a.swellDir == (mob instanceof Creeper c ? c.getSwellDir() : 0) && a.swell == (mob instanceof Creeper c ? ((CreeperAccessor) c).tt$getSwell() : 0) && a.invul == (mob instanceof WitherBoss w ? w.getInvulnerableTicks() : 0) && a.phase == phaseOf(mob) && a.pose == WardenSupport.poseOf(mob) && a.clientAnger == WardenSupport.angerOf(mob);
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
            if (!use.isEmpty()) s.useItemNbt = use.save(TimeTravel.server.registryAccess()).toString();
            s.useItemRemaining = mob.getUseItemRemainingTicks();
        }
        if (mob instanceof Creeper creeper) {
            s.swellDir = creeper.getSwellDir();
            s.swell = ((CreeperAccessor) creeper).tt$getSwell();
        }
        if (mob instanceof WitherBoss wither) s.invul = wither.getInvulnerableTicks();
        s.phase = phaseOf(mob);
        s.pose = WardenSupport.poseOf(mob);
        s.clientAnger = WardenSupport.angerOf(mob);
        s.angry = angryOf(mob);
        return s;
    }

    public static void apply() {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;

        for (Map.Entry<UUID, Track> en : tracks.entrySet()) {
            UUID id = en.getKey();
            Track t = en.getValue();

            if (MobSpawnJournal.shouldBeDespawned(id, now)) {
                ServerLevel level = levelOf(t);
                if (level != null) {
                    Entity e = level.getEntity(id);
                    if (e instanceof Mob mob && !mob.isRemoved()) {
                        MobSpawnJournal.discardForReplay(mob);
                    }
                }
                releasePuppet(id);
                continue;
            }

            Sample s = null;
            if (!t.samples.isEmpty() && now >= t.firstTick() && now <= t.lastTick() + GRACE) {
                s = sampleAt(t.samples, now);
            }
            if (s == null) {
                releasePuppet(id);
                continue;
            }

            ServerLevel level = levelOf(t);
            if (level == null) continue;

            Entity e = level.getEntity(id);
            if (!(e instanceof Mob mob) || mob.isRemoved()) continue;
            if (mob.isDeadOrDying() && !MobJournal.isDoomed(mob)) continue;
            if (mob instanceof EnderDragon d && BossFightJournal.isDrivenDeath(d)) continue;

            if (!puppets.contains(id)) {
                boolean orig = mob.getTags().contains(PUPPET_TAG) ? mob.getTags().contains(ORIG_NOAI_TAG) : mob.isNoAi();
                originalNoAi.put(id, orig);
                puppets.add(id);
                mob.addTag(PUPPET_TAG);
                if (orig) mob.addTag(ORIG_NOAI_TAG);
            }

            Vec3 vel = Vec3.ZERO;
            if (mob instanceof EnderDragon) {
                Sample prev = sampleAt(t.samples, now - 1);
                if (prev != null) vel = new Vec3(s.x - prev.x, s.y - prev.y, s.z - prev.z);
            }

            if (!MobJournal.isApplying()) {
                MobJournal.enterApplying();
                try {
                    applySample(mob, s, vel);
                } finally {
                    MobJournal.exitApplying();
                }
            } else {
                applySample(mob, s, vel);
            }
        }
    }
    private static void applySpawned() {
        if (spawnedPuppets.isEmpty() || TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;

        for (UUID id : new ArrayList<>(spawnedPuppets)) {
            Track t = tracks.get(id);
            if (t == null) {
                spawnedPuppets.remove(id);
                puppets.remove(id);
                continue;
            }

            if (MobSpawnJournal.shouldBeDespawned(id, now)) {
                ServerLevel level = levelOf(t);
                if (level != null) {
                    Entity e = level.getEntity(id);
                    if (e instanceof Mob mob && !mob.isRemoved()) {
                        MobSpawnJournal.discardForReplay(mob);
                    }
                }
                releasePuppet(id);
                continue;
            }

            Sample s = null;
            if (!t.samples.isEmpty() && now >= t.firstTick() && now <= t.lastTick() + GRACE) {
                s = sampleAt(t.samples, now);
            }
            if (s == null) {
                releasePuppet(id);
                continue;
            }
            ServerLevel level = levelOf(t);
            if (level == null) continue;
            Entity e = level.getEntity(id);
            if (!(e instanceof Mob mob) || mob.isRemoved()) {
                releasePuppet(id);
                continue;
            }
            if (mob.isDeadOrDying() && !MobJournal.isDoomed(mob)) continue;
            if (mob instanceof EnderDragon d && BossFightJournal.isDrivenDeath(d)) continue;

            Vec3 vel = Vec3.ZERO;
            if (mob instanceof EnderDragon) {
                Sample prev = sampleAt(t.samples, now - 1);
                if (prev != null) vel = new Vec3(s.x - prev.x, s.y - prev.y, s.z - prev.z);
            }

            if (!MobJournal.isApplying()) {
                MobJournal.enterApplying();
                try {
                    applySample(mob, s, vel);
                } finally {
                    MobJournal.exitApplying();
                }
            } else {
                applySample(mob, s, vel);
            }
        }
    }

    private static void applySample(Mob mob, Sample s, Vec3 vel) {
        mob.setNoAi(!(mob instanceof EnderDragon));
        mob.setDeltaMovement(vel);
        mob.fallDistance = 0;

        if (s.health <= 0) return;

        if (MobJournal.isDoomed(mob) && (mob.isDeadOrDying() || mob.deathTime > 0)) {
            mob.deathTime = 0;
            mob.hurtTime = 0;
            mob.setPose(Pose.STANDING);
            mob.setHealth(Math.min(s.health, mob.getMaxHealth()));
        }
        if (mob instanceof WitherBoss) {
            Float prev = lastBossHealth.put(mob.getUUID(), s.health);
            if (prev != null && s.health > prev && mob.getHealth() < s.health) {
                mob.setHealth(Math.min(s.health, mob.getHealth() + (s.health - prev)));
            }
        }
        if (mob.getHealth() > s.health) mob.setHealth(s.health);

        mob.setPos(s.x, s.y, s.z);
        mob.setYRot(s.yRot);
        mob.setXRot(s.xRot);
        mob.setYHeadRot(s.yHeadRot);
        mob.setYBodyRot(s.yBodyRot);

        WardenSupport.applyFx(mob);
        mob.swinging = s.swinging;
        if (s.swinging && mob.swingTime == 0) mob.swingTime = 1;
        mob.setAggressive(s.aggressive);
        if (mob instanceof NeutralMob n) {
            if (s.angry) {
                if (n.getRemainingPersistentAngerTime() < 20) n.setRemainingPersistentAngerTime(40);
            } else if (n.getRemainingPersistentAngerTime() > 0) {
                n.setRemainingPersistentAngerTime(0);
            }
            n.setPersistentAngerTarget(null);
        }

        applyBossState(mob, s);
        WardenSupport.applyState(mob, s);

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
            boolean same = !current.isEmpty() && current.save(TimeTravel.server.registryAccess()).toString().equals(s.useItemNbt);
            if (!same) {
                try {
                    CompoundTag tag = TagParser.parseTag(s.useItemNbt);
                    ItemStack stack = ItemStack.parse(TimeTravel.server.registryAccess(), tag).orElse(ItemStack.EMPTY);
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
        } else if (!acc.tt$getUseItem().isEmpty()) {
            acc.tt$setUseItem(ItemStack.EMPTY);
            acc.tt$setUseItemRemaining(0);
            acc.tt$setLivingEntityFlag(1, false);
        }
    }

    private static void releasePuppet(UUID id) {
        spawnedPuppets.remove(id);
        if (!puppets.remove(id)) return;
        lastBossHealth.remove(id);
        Boolean orig = originalNoAi.remove(id);
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof Mob mob && !mob.isRemoved()) {
                mob.setNoAi(orig != null && orig);
                mob.removeTag(PUPPET_TAG);
                mob.removeTag(ORIG_NOAI_TAG);
                if (mob instanceof Warden w) WardenSupport.settle(w);
                return;
            }
        }
    }

    private static void sweepStrayPuppets(boolean includeTracked) {
        if (TimeTravel.server == null) return;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob) || Clones.is(mob)) continue;
                UUID id = mob.getUUID();
                if (puppets.contains(id)) continue;
                if (mob.getTags().contains(PUPPET_TAG)) {
                    mob.setNoAi(mob.getTags().contains(ORIG_NOAI_TAG));
                    mob.removeTag(PUPPET_TAG);
                    mob.removeTag(ORIG_NOAI_TAG);
                    if (mob instanceof Warden w) WardenSupport.settle(w);
                } else if (includeTracked && mob.isNoAi() && tracks.containsKey(id)) {
                    mob.setNoAi(false);
                }
            }
        }
    }

    public static void restoreAi() {
        releaseAllPuppets();
        sweepStrayPuppets(true);
    }

    public static void releaseAll() {
        releaseAllPuppets();
        sweepStrayPuppets(false);
    }

    private static void releaseAllPuppets() {
        for (UUID id : new ArrayList<>(puppets)) releasePuppet(id);
    }

    private static void releaseTemporaryPuppets() {
        for (UUID id : new ArrayList<>(puppets)) {
            if (spawnedPuppets.contains(id)) continue;
            releasePuppet(id);
        }
    }

    private static Sample sampleAt(List<Sample> s, long tick) {
        if (s.isEmpty() || tick < s.get(0).tick) return null;
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

    static List<Sample> samplesOf(UUID id) {
        Track t = tracks.get(id);
        return t == null ? null : t.samples;
    }

    public static Vec3 positionAt(UUID id, long tick) {
        Track t = tracks.get(id);
        if (t == null || t.samples.isEmpty()) return null;
        Sample s = sampleAt(t.samples, tick);
        return s == null ? null : new Vec3(s.x, s.y, s.z);
    }

    public static void rewriteHealth(UUID id, long tick, float lost) {
        Track t = tracks.get(id);
        if (t == null) return;
        for (Sample s : t.samples) {
            if (s.tick >= tick && s.health > 1.0F) s.health = Math.max(1.0F, s.health - lost);
        }
        dirty = true;
    }

    public static void tick() {
        if (!TimeTravel.isLooping) return;
        if (isReplaying()) {
            wasReplaying = true;
            apply();
            sample(true);
        } else {
            if (wasReplaying) {
                releaseTemporaryPuppets();
                wasReplaying = false;
            }
            if (TimeTravel.timelineTick % 100 == 0) sweepStrayPuppets(false);
            sample();
            applySpawned();
        }
    }

    public static void onJump(long from, long to) {
        if (TimeTravel.server == null) return;
        long now = TimeTravel.timelineTick;
        lastBossHealth.clear();
        for (Map.Entry<UUID, Track> en : tracks.entrySet()) {
            Track t = en.getValue();
            if (t.samples.isEmpty()) continue;
            Sample s = sampleAt(t.samples, now);
            if (s == null || s.health <= 0) continue;

            ServerLevel level = levelOf(t);
            if (level == null) continue;
            Entity e = level.getEntity(en.getKey());
            if (!(e instanceof Mob mob) || mob.isRemoved()) continue;

            mob.setHealth(Math.min(s.health, mob.getMaxHealth()));
            applyBossState(mob, s);
            if (mob.deathTime > 0) {
                mob.deathTime = 0;
                mob.hurtTime = 0;
                mob.setPose(Pose.STANDING);
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

    public static void rebuildUpcoming() {
    }

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
            t.samples.removeIf(x -> x.health <= 0);
            t.samples.sort(Comparator.comparingLong(x -> x.tick));
            if (t.samples.isEmpty()) continue;
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
        restoreAi();
        tracks.clear();
        sampledThisTick.clear();
        puppets.clear();
        spawnedPuppets.clear();
        originalNoAi.clear();
        bosses.clear();
        lastBossHealth.clear();
        wasReplaying = false;
        save();
    }
}
