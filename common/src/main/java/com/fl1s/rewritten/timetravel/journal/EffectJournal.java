package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

public final class EffectJournal {
    private EffectJournal() {
    }

    private static final int SOUND = 0;
    private static final int LEVEL_EVENT = 1;
    private static final int BLOCK_EVENT = 2;
    private static final double NEAR_SQ = 8.0 * 8.0;

    public static final class Entry {
        int kind;
        String dim;
        double x, y, z;
        String id;
        String source;
        float volume, pitch;
        int a, b;
        long tick;
        long seq;
        int visit;
        transient ResourceLocation cachedLocation;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_effects.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    private static boolean replaying = false;

    public static void recordSound(ServerLevel level, Player except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        if (!TimeTravel.isLooping || replaying || ReplayState.SUPPRESS) return;
        PlayerData pd = recorder(level, except, x, y, z);
        if (pd == null) return;
        Optional<ResourceKey<SoundEvent>> key = sound.unwrapKey();
        if (key.isEmpty()) return;

        String id = key.get().location().toString();
        if (isPlayerHurt(id)) return;
        Entry e = create(SOUND, level, pd, x, y, z);
        e.id = id;
        e.source = source.name();
        e.volume = volume;
        e.pitch = pitch;
        add(e);
    }

    public static void recordLevelEvent(ServerLevel level, Player except, int type, BlockPos pos, int data) {
        if (!TimeTravel.isLooping || replaying || ReplayState.SUPPRESS || except == null) return;
        PlayerData pd = recorder(level, except, pos.getX(), pos.getY(), pos.getZ());
        if (pd == null) return;

        Entry e = create(LEVEL_EVENT, level, pd, pos.getX(), pos.getY(), pos.getZ());
        e.a = type;
        e.b = data;
        add(e);
    }

    public static void recordBlockEvent(ServerLevel level, BlockPos pos, Block block, int a, int b) {
        if (!TimeTravel.isLooping || replaying || ReplayState.SUPPRESS) return;
        if (!level.isLoaded(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof LidBlockEntity) && !(be instanceof ShulkerBoxBlockEntity)) return;
        PlayerData pd = recorder(level, null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (pd == null) return;

        for (int i = STORE.entries().size() - 1; i >= 0; i--) {
            Entry p = STORE.entries().get(i);
            if (p.tick != TimeTravel.timelineTick) break;
            if (p.kind == BLOCK_EVENT && p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ() && p.a == a && p.b == b)
                return;
        }
        Entry e = create(BLOCK_EVENT, level, pd, pos.getX(), pos.getY(), pos.getZ());
        e.id = BuiltInRegistries.BLOCK.getKey(block).toString();
        e.a = a;
        e.b = b;
        add(e);
    }

    private static boolean isClone(Player p) {
        return Clones.is(p);
    }

    private static boolean isPlayerHurt(String id) {
        return id != null && id.startsWith("minecraft:entity.player.hurt");
    }

    private static PlayerData recorder(ServerLevel level, Player except, double x, double y, double z) {
        if (TimeTravel.loopSceneManager == null) return null;
        ServerPlayer sp = null;
        if (except != null) {
            if (!(except instanceof ServerPlayer p) || isClone(p)) return null;
            sp = p;
        } else {
            for (ServerPlayer p : level.players()) {
                if (isClone(p)) continue;
                if (p.distanceToSqr(x, y, z) <= NEAR_SQ) {
                    sp = p;
                    break;
                }
            }
        }
        if (sp == null) return null;
        PlayerData pd = TimeTravel.loopSceneManager.getRecordingPlayer(sp.getName().getString());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return null;
        return pd;
    }

    private static Entry create(int kind, ServerLevel level, PlayerData pd, double x, double y, double z) {
        Entry e = new Entry();
        e.kind = kind;
        e.dim = level.dimension().location().toString();
        e.x = x;
        e.y = y;
        e.z = z;
        e.tick = TimeTravel.timelineTick;
        e.seq = STORE.nextSeq();
        e.visit = pd.getCurrentVisit().id;
        return e;
    }

    private static void add(Entry e) {
        STORE.entries().add(e);
        STORE.markDirty();
    }

    public static void tick() {
        STORE.tick(EffectJournal::play);
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void onJump(long from, long to) {
        rebuildUpcoming();
    }

    private static ResourceLocation cachedLocation(Entry e) {
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

    private static void play(Entry e) {
        if (e.kind == SOUND && isPlayerHurt(e.id)) return;
        ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
        if (level == null) return;
        replaying = true;
        try {
            switch (e.kind) {
                case SOUND -> {
                    ResourceLocation loc = cachedLocation(e);
                    if (loc != null) {
                        BuiltInRegistries.SOUND_EVENT.getOptional(loc).ifPresent(se -> level.playSound(null, e.x, e.y, e.z, se, SoundSource.valueOf(e.source), e.volume, e.pitch));
                    }
                }
                case LEVEL_EVENT -> level.levelEvent(null, e.a, BlockPos.containing(e.x, e.y, e.z), e.b);
                case BLOCK_EVENT -> {
                    ResourceLocation loc = cachedLocation(e);
                    if (loc != null) {
                        BuiltInRegistries.BLOCK.getOptional(loc).ifPresent(block -> level.blockEvent(BlockPos.containing(e.x, e.y, e.z), block, e.a, e.b));
                    }
                }
                default -> {
                }
            }
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to replay effect at {} {} {}", e.x, e.y, e.z, ex);
        } finally {
            replaying = false;
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
    }

    public static void load() {
        STORE.load();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void save() {
        STORE.save();
    }

    public static void clear() {
        STORE.clear();
    }
}
