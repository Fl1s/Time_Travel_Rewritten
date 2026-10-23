package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records server-sent spell particles and replays them on the timeline.
 */
public final class ParticleJournal {
    private ParticleJournal() {
    }

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit;
        public String dim;
        public String type;
        public String opts;
        public double x, y, z;
        public int count;
        public double dx, dy, dz;
        public double speed;
        public boolean force;
        transient ParticleOptions decoded;
        transient boolean decodeFailed;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_particles.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static final int MAX_PER_VISIT = 20000;
    private static final Map<Integer, Integer> perVisitCount = new HashMap<>();

    private static final List<String> SOURCE_PREFIXES = new CopyOnWriteArrayList<>(List.of("io.redspace.ironsspellbooks."));
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final Set<String> warned = new HashSet<>();

    private static boolean replaying = false;

    public static void addSourcePrefix(String prefix) {
        if (prefix != null && !prefix.isEmpty()) SOURCE_PREFIXES.add(prefix);
    }

    public static boolean isReplaying() {
        return replaying;
    }

    public static void onSpawn(ServerLevel level, ParticleOptions type, double x, double y, double z, int count, double dx, double dy, double dz, double speed, boolean force) {
        record(level, type, x, y, z, count, dx, dy, dz, speed, force, false);
    }

    public static void onSpellSpawn(ServerLevel level, ParticleOptions type, double x, double y, double z, int count, double dx, double dy, double dz, double speed, boolean force) {
        record(level, type, x, y, z, count, dx, dy, dz, speed, force, true);
    }

    private static void record(ServerLevel level, ParticleOptions options, double x, double y, double z, int count, double dx, double dy, double dz, double speed, boolean force, boolean trustedSource) {
        if (!TimeTravel.isLooping) return;
        if (ReplayState.SUPPRESS) return;
        if (replaying) return;
        if (TimeTravel.timelineSceneManager == null) return;
        // count == 0 is valid: the client spawns one particle and uses dx/dy/dz as its velocity
        if (count < 0 || options == null) return;
        // A replayed mob (Wisp...) ticks on its own and emits particles that are already in the journal
        if (PuppetGuard.isTickingPuppetMob()) return;

        PlayerData pd = BlockJournal.nearestRecorder(level, BlockPos.containing(x, y, z));
        if (pd == null || pd.getCurrentVisit() == null) return;
        int visitId = pd.getCurrentVisit().id;

        int used = perVisitCount.getOrDefault(visitId, 0);
        if (used >= MAX_PER_VISIT) return;

        if (!trustedSource && !isFromSpellCode()) return;

        String type = null;
        String opts = null;
        if (options instanceof SimpleParticleType simple) {
            ResourceLocation key = BuiltInRegistries.PARTICLE_TYPE.getKey(simple);
            if (key == null) return;
            type = key.toString();
        } else {
            opts = encode(level, options);
            if (opts == null) return;
        }

        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = visitId;
        e.dim = level.dimension().location().toString();
        e.type = type;
        e.opts = opts;
        e.x = x;
        e.y = y;
        e.z = z;
        e.count = count;
        e.dx = dx;
        e.dy = dy;
        e.dz = dz;
        e.speed = speed;
        e.force = force;
        STORE.entries().add(e);
        perVisitCount.put(visitId, used + 1);
        STORE.markDirty();
    }

    private static boolean isFromSpellCode() {
        return WALKER.walk(frames -> frames.limit(32).anyMatch(f -> {
            String name = f.getClassName();
            for (String prefix : SOURCE_PREFIXES) if (name.startsWith(prefix)) return true;
            return false;
        }));
    }

    private static String encode(ServerLevel level, ParticleOptions options) {
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
            return ParticleTypes.CODEC.encodeStart(ops, options).resultOrPartial(err -> warnOnce("encode " + options.getType(), err)).map(Tag::toString).orElse(null);
        } catch (Throwable t) {
            warnOnce("encode " + options.getType(), t.toString());
            return null;
        }
    }

    private static ParticleOptions resolve(ServerLevel level, Entry e) {
        if (e.decoded != null) return e.decoded;
        if (e.decodeFailed) return null;
        try {
            if (e.opts != null) {
                RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
                e.decoded = ParticleTypes.CODEC.parse(ops, TagParser.parseTag(e.opts)).resultOrPartial(err -> warnOnce("decode " + e.opts, err)).orElse(null);
            } else if (e.type != null) {
                ParticleType<?> raw = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse(e.type));
                if (raw instanceof SimpleParticleType simple) e.decoded = simple;
            }
        } catch (Exception ex) {
            warnOnce("decode " + e.type, ex.toString());
        }
        if (e.decoded == null) e.decodeFailed = true;
        return e.decoded;
    }

    private static void warnOnce(String what, String err) {
        if (warned.add(what)) TimeTravel.TIMELINE_LOGGER.warn("[TT-PARTICLES] cannot {}: {}", what, err);
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        STORE.tick(ParticleJournal::replay);
    }

    private static void replay(Entry e) {
        ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
        if (level == null) return;
        try {
            ParticleOptions options = resolve(level, e);
            if (options == null) return;
            replaying = true;
            try {
                if (e.force) {
                    for (ServerPlayer p : level.players()) {
                        if (Clones.is(p)) continue;
                        level.sendParticles(p, options, true, e.x, e.y, e.z, e.count, e.dx, e.dy, e.dz, e.speed);
                    }
                } else {
                    level.sendParticles(options, e.x, e.y, e.z, e.count, e.dx, e.dy, e.dz, e.speed);
                }
            } finally {
                replaying = false;
            }
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay particle {}", e.type != null ? e.type : e.opts, ex);
        }
    }

    public static void onJump(long from, long to) {
        if (to > from) {
            for (Entry e : STORE.entries()) {
                if (e.tick > from && e.tick <= to) replay(e);
            }
        }
        STORE.rebuildUpcoming();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        perVisitCount.keySet().removeAll(visitIds);
        STORE.eraseVisits(visitIds);
    }

    public static void load() {
        STORE.load();
        perVisitCount.clear();
        for (Entry e : STORE.entries()) perVisitCount.merge(e.visit, 1, Integer::sum);
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        perVisitCount.clear();
        STORE.clear();
    }
}
