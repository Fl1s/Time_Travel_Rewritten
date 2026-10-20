package com.fl1s.rewritten.timetravel.mob;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.net.WardenAnimPayload;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class WardenEventJournal {
    private WardenEventJournal() {
    }

    private static final int SOUND = 0;
    private static final int EVENT = 1;
    private static final int BOOM = 2;

    private static final int EVENT_ATTACK = 4;
    private static final int EVENT_TENDRIL = 61;
    private static final int EVENT_SONIC_BOOM = 62;

    private static final double SEND_RANGE_SQ = 96.0 * 96.0;
    private static final int SEND_DELAY = 3;
    private static final double BOOM_TAIL = 6.0;
    private static final float BOOM_DAMAGE = 10.0F;

    public static final class Entry {
        public int kind;
        public long tick;
        public long seq;
        public int visit;
        public UUID uuid;
        public String dim;
        public double x, y, z;
        public double x2, y2, z2;
        public int count;
        public String id;
        public String source;
        public float volume, pitch;
        public int a;
        transient ResourceLocation cachedLocation;
    }

    private record Pending(long at, UUID uuid, String dim, int anim, int elapsed) {
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_warden_events.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final List<Pending> pending = new ArrayList<>();
    private static Entry currentBoom = null;
    private static boolean replaying = false;
    private static boolean capturing = false;

    public static boolean suppressesEffects() {
        return capturing || replaying;
    }

    private static int graceOf(int state) {
        return switch (state) {
            case EVENT_SONIC_BOOM -> 34;
            case EVENT_ATTACK -> 10;
            case EVENT_TENDRIL -> 10;
            default -> 0;
        };
    }

    private static int durationOf(int anim) {
        return switch (anim) {
            case WardenAnimPayload.EMERGE -> 134;
            case WardenAnimPayload.DIG -> 100;
            case WardenAnimPayload.ROAR -> 84;
            case WardenAnimPayload.SNIFF -> 84;
            default -> 0;
        };
    }

    private static int animOfPose(Pose pose) {
        return switch (pose) {
            case EMERGING -> WardenAnimPayload.EMERGE;
            case DIGGING -> WardenAnimPayload.DIG;
            case ROARING -> WardenAnimPayload.ROAR;
            case SNIFFING -> WardenAnimPayload.SNIFF;
            default -> -1;
        };
    }

    private static boolean active() {
        if (replaying || !TimeTravel.isLooping || ReplayState.SUPPRESS) return false;
        return TimeTravel.timelineSceneManager != null && TimeTravel.server != null;
    }

    private static boolean liveWarden(ServerLevel level, UUID uuid) {
        if (uuid == null) return false;
        return level.getEntity(uuid) instanceof Warden warden && !MobTrailJournal.isPuppeted(warden);
    }

    private static Entry create(int kind, ServerLevel level, UUID uuid, double x, double y, double z) {
        PlayerData pd = BlockJournal.nearestRecorder(level, BlockPos.containing(x, y, z));
        if (pd == null || pd.getCurrentVisit() == null) return null;
        Entry e = new Entry();
        e.kind = kind;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        e.uuid = uuid;
        e.dim = level.dimension().location().toString().intern();
        e.x = x;
        e.y = y;
        e.z = z;
        return e;
    }

    private static void add(Entry e) {
        STORE.entries().add(e);
        STORE.markDirty();
    }

    public static void onSound(Warden warden, SoundEvent sound, float volume, float pitch) {
        if (!active() || warden.isSilent() || MobTrailJournal.isPuppeted(warden)) return;
        if (!(warden.level() instanceof ServerLevel level)) return;
        ResourceLocation id = BuiltInRegistries.SOUND_EVENT.getKey(sound);
        if (id == null) return;
        Entry e = create(SOUND, level, warden.getUUID(), warden.getX(), warden.getY(), warden.getZ());
        if (e == null) return;
        e.id = id.toString();
        e.source = warden.getSoundSource().name();
        e.volume = volume;
        e.pitch = pitch;
        add(e);
        capturing = true;
    }

    public static void endSound() {
        capturing = false;
    }

    public static void onEvent(Warden warden, byte state) {
        if (graceOf(state) == 0 || !active() || MobTrailJournal.isPuppeted(warden)) return;
        if (!(warden.level() instanceof ServerLevel level)) return;
        Entry e = create(EVENT, level, warden.getUUID(), warden.getX(), warden.getY(), warden.getZ());
        if (e == null) return;
        e.a = state;
        add(e);
    }

    private static Warden nearestWarden(ServerLevel level, Vec3 origin) {
        Warden best = null;
        double bestDist = Double.MAX_VALUE;
        for (Warden w : level.getEntitiesOfClass(Warden.class, new AABB(origin, origin).inflate(3.0))) {
            double d = w.distanceToSqr(origin);
            if (d < bestDist) {
                best = w;
                bestDist = d;
            }
        }
        return best;
    }

    public static void onBoomParticle(ServerLevel level, double x, double y, double z) {
        if (!active()) return;
        long now = TimeTravel.timelineTick;
        String dim = level.dimension().location().toString();
        Entry cur = currentBoom;
        if (cur != null && cur.tick == now && cur.dim.equals(dim)) {
            double dx = x - cur.x2, dy = y - cur.y2, dz = z - cur.z2;
            if (Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - 1.0) < 0.01) {
                cur.x2 = x;
                cur.y2 = y;
                cur.z2 = z;
                cur.count++;
                if (cur.count == 2) {
                    Vec3 first = new Vec3(cur.x, cur.y, cur.z);
                    Vec3 dir = new Vec3(x, y, z).subtract(first).normalize();
                    Warden source = nearestWarden(level, first.subtract(dir));
                    cur.uuid = source == null ? null : source.getUUID();
                }
                STORE.markDirty();
                return;
            }
        }
        Entry e = create(BOOM, level, null, x, y, z);
        currentBoom = e;
        if (e == null) return;
        e.x2 = x;
        e.y2 = y;
        e.z2 = z;
        e.count = 1;
        add(e);
    }

    private static ResourceLocation locationOf(Entry e) {
        ResourceLocation loc = e.cachedLocation;
        if (loc == null) {
            try {
                loc = ResourceLocation.parse(e.id);
                e.cachedLocation = loc;
            } catch (Exception ex) {
                return null;
            }
        }
        return loc;
    }

    private static void playBoom(ServerLevel level, Entry e) {
        Entity entity = e.uuid == null ? null : level.getEntity(e.uuid);
        if (entity instanceof Warden w && !MobTrailJournal.isPuppeted(w)) return;

        Vec3 first = new Vec3(e.x, e.y, e.z);
        Vec3 last = new Vec3(e.x2, e.y2, e.z2);
        Vec3 delta = last.subtract(first);
        double len = delta.length();
        Vec3 dir = len < 1.0E-6 ? Vec3.ZERO : delta.scale(1.0 / len);
        int points = (int) Math.round(len) + 1;
        for (int i = 0; i < points; i++) {
            Vec3 p = first.add(dir.scale(i));
            level.sendParticles(ParticleTypes.SONIC_BOOM, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }

        if (!(entity instanceof Warden warden) || dir == Vec3.ZERO) return;
        Vec3 from = first.subtract(dir);
        Vec3 to = last.subtract(dir.scale(BOOM_TAIL));
        for (ServerPlayer player : new ArrayList<>(level.players())) {
            if (Clones.is(player) || !player.isAlive()) continue;
            AABB box = player.getBoundingBox().inflate(0.3);
            if (!box.contains(from) && box.clip(from, to).isEmpty()) continue;
            player.hurt(level.damageSources().sonicBoom(warden), BOOM_DAMAGE);
            double resistance = 1.0 - player.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            player.push(dir.x * 2.5 * resistance, dir.y * 0.5 * resistance, dir.z * 2.5 * resistance);
            player.hurtMarked = true;
        }
    }

    private static void play(Entry e) {
        ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
        if (level == null) return;
        replaying = true;
        try {
            switch (e.kind) {
                case SOUND -> {
                    if (liveWarden(level, e.uuid)) return;
                    ResourceLocation loc = locationOf(e);
                    if (loc != null) {
                        BuiltInRegistries.SOUND_EVENT.getOptional(loc).ifPresent(se -> level.playSound(null, e.x, e.y, e.z, se, SoundSource.valueOf(e.source), e.volume, e.pitch));
                    }
                }
                case EVENT -> {
                    if (liveWarden(level, e.uuid)) return;
                    Entity entity = level.getEntity(e.uuid);
                    if (entity instanceof Warden && !entity.isRemoved()) level.broadcastEntityEvent(entity, (byte) e.a);
                }
                case BOOM -> playBoom(level, e);
                default -> {
                }
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay warden event", ex);
        } finally {
            replaying = false;
        }
    }

    private static void queueAnim(UUID uuid, String dim, int anim, long elapsed) {
        pending.add(new Pending(TimeTravel.server.getTickCount() + SEND_DELAY, uuid, dim, anim, (int) elapsed));
    }

    private static void flushPending() {
        if (pending.isEmpty()) return;
        long now = TimeTravel.server.getTickCount();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.at() > now) continue;
            it.remove();
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(p.dim()));
            if (level == null) continue;
            Entity entity = level.getEntity(p.uuid());
            if (!(entity instanceof Warden) || entity.isRemoved()) continue;
            WardenAnimPayload payload = new WardenAnimPayload(entity.getId(), p.anim(), p.elapsed() + SEND_DELAY);
            for (ServerPlayer player : new ArrayList<>(level.players())) {
                if (Clones.is(player) || player.distanceToSqr(entity) > SEND_RANGE_SQ) continue;
                TimelineNet.sendToPlayer(player, payload);
            }
        }
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        STORE.tick(WardenEventJournal::play);
        flushPending();
    }

    private static void catchUpPoses(long now) {
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Warden warden) || warden.isRemoved()) continue;
                List<MobTrailJournal.Sample> samples = MobTrailJournal.samplesOf(warden.getUUID());
                if (samples == null || samples.isEmpty()) continue;
                int idx = -1;
                for (int i = 0; i < samples.size(); i++) {
                    if (samples.get(i).tick > now) break;
                    idx = i;
                }
                if (idx < 0) continue;
                int pose = samples.get(idx).pose;
                Pose[] all = Pose.values();
                if (pose < 0 || pose >= all.length) continue;
                int anim = animOfPose(all[pose]);
                if (anim < 0) continue;
                long start = samples.get(idx).tick;
                for (int i = idx - 1; i >= 0 && samples.get(i).pose == pose; i--) start = samples.get(i).tick;
                long elapsed = now - start;
                if (elapsed <= 0 || elapsed >= durationOf(anim)) continue;
                queueAnim(warden.getUUID(), dim, anim, elapsed);
            }
        }
    }

    public static void onJump(long from, long to) {
        STORE.rebuildUpcoming();
        pending.clear();
        currentBoom = null;
        if (TimeTravel.server == null || !TimeTravel.isLooping) return;
        long now = TimeTravel.timelineTick;
        for (Entry e : STORE.entries()) {
            if (e.kind != EVENT || e.tick > now) continue;
            long elapsed = now - e.tick;
            if (elapsed >= graceOf(e.a)) continue;
            if (e.a == EVENT_ATTACK) queueAnim(e.uuid, e.dim, WardenAnimPayload.ATTACK, elapsed);
            else if (e.a == EVENT_SONIC_BOOM) queueAnim(e.uuid, e.dim, WardenAnimPayload.SONIC_BOOM, elapsed);
            else play(e);
        }
        catchUpPoses(now);
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        currentBoom = null;
        STORE.eraseVisits(visitIds);
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void load() {
        STORE.load();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        pending.clear();
        currentBoom = null;
        STORE.clear();
    }
}
