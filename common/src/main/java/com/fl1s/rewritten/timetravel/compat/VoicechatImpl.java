package com.fl1s.rewritten.timetravel.compat;

import com.fl1s.rewritten.timetravel.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.compat.voicechat.AudioPersistence;
import com.fl1s.rewritten.timetravel.compat.voicechat.AudioUtils;
import com.fl1s.rewritten.timetravel.compat.voicechat.TimedAudioFrame;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.*;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.*;

/**
 * Integration with <a href="https://github.com/henkelmax/simple-voice-chat">Simple Voice Chat</a>
 * for recording and playing back voice audio during time loops.
 * <p>
 * All methods are static and guard on {@link TimeTravel#voiceChatLoaded}. When SVC
 * is not installed, every public method is a safe no-op.
 * <p>
 * Voice playback decodes Opus frames into PCM, extracts speech regions
 * (contiguous bursts of frames without tick gaps), and schedules per-region
 * {@link AudioPlayer AudioPlayers} so the SVC speaker icon only appears
 * during actual speech. Each burst is decoded into its own buffer; there is
 * no full-visit PCM buffer.
 */
public final class VoicechatImpl {

    private VoicechatImpl() {}

    public static VoicechatServerApi serverApi;
    public static VolumeCategory LOOP_CATEGORY;

    /** Active AudioPlayers keyed by "playerName|iter|seg|regionIndex". */
    private static final Map<String, AudioPlayer> players = new ConcurrentHashMap<>();

    /** Scheduled speech regions waiting to be started at the right tick. */
    private static final List<ScheduledSpeechRegion> scheduledRegions =
            Collections.synchronizedList(new ArrayList<>());

    private static volatile ExecutorService decodePool = createDecodePool();

    private static ExecutorService createDecodePool() {
        return Executors.newFixedThreadPool(Math.max(2,
                        Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = new Thread(r, "TimeTravel-VoiceDecode");
                    t.setDaemon(true);
                    return t;
                });
    }

    private static ExecutorService getDecodePool() {
        ExecutorService pool = decodePool;
        if (pool == null || pool.isShutdown()) {
            synchronized (VoicechatImpl.class) {
                pool = decodePool;
                if (pool == null || pool.isShutdown()) {
                    pool = createDecodePool();
                    decodePool = pool;
                }
            }
        }
        return pool;
    }

    /* ------------- INIT ------------- */

    public static void initCategory() {
        LOOP_CATEGORY = serverApi.volumeCategoryBuilder()
                .setId(TimeTravel.MOD_ID + "_voice")
                .setName("Time Travel Voices")
                .build();
        serverApi.registerVolumeCategory(LOOP_CATEGORY);
    }

    /* ------------- PLAYBACK ------------- */

    private static volatile int generation = 0;

    public static void onGhostStart(PlayerData data, int visitId, int segment, long visitStartTick,
                                    net.minecraft.world.entity.Entity entity) {
        if (!TimeTravel.voiceChatLoaded || serverApi == null) return;

        List<TimedAudioFrame> frames = data.getAudio(visitId).get(segment);
        if (frames == null || frames.isEmpty()) return;

        final int gen = generation;
        getDecodePool().execute(() -> {
            List<DecodedRegion> regions = decodeSpeechRegions(frames);
            if (regions.isEmpty()) return;

            TimeTravel.server.execute(() -> {
                if (gen != generation) return;
                for (int ri = 0; ri < regions.size(); ri++) {
                    DecodedRegion region = regions.get(ri);
                    String key = key(data.getName(), visitId, segment) + "|" + ri;

                    scheduledRegions.add(new ScheduledSpeechRegion(
                            key, data, visitId, segment, ri, region.pcm(), entity,
                            visitStartTick + region.startTick(), region.startTick(),
                            region.position()
                    ));
                }
            });
        });
    }

    public static net.minecraft.world.entity.Entity findNewestGhost(String nickname) {
        if (nickname == null || TimeTravel.server == null) return null;

        Set<UUID> realPlayerUUIDs = new HashSet<>();
        for (net.minecraft.server.level.ServerPlayer sp : TimeTravel.server.getPlayerList().getPlayers()) {
            realPlayerUUIDs.add(sp.getUUID());
        }

        net.minecraft.world.entity.Entity best = null;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (net.minecraft.server.level.ServerPlayer sp : level.players()) {
                if (realPlayerUUIDs.contains(sp.getUUID())) continue;
                if (!nickname.equals(sp.getGameProfile().getName())) continue;
                if (best == null || sp.getId() > best.getId()) best = sp;
            }
        }
        return best;
    }

    /* ------------- DECODING ------------- */

    /**
     * A contiguous burst of decoded PCM plus the tick at which the burst
     * begins and the position of its first frame.
     */
    private record DecodedRegion(int startTick, short[] pcm, Vec3 position) {}

    /**
     * Decodes a list of timed Opus frames into per-burst PCM buffers.
     * <p>
     * A "burst" is a maximal run of frames with no tick gap (each next frame's
     * tick is at most previousTick + 1). Within a burst, frames are placed
     * back-to-back; between bursts there is a silence gap of one or more ticks.
     * Bursts with no non-zero samples are discarded.
     * <p>
     * This replaces a previous implementation that allocated a PCM buffer for
     * the entire visit duration, which could reach hundreds of MB for long
     * recordings.
     */
    private static List<DecodedRegion> decodeSpeechRegions(List<TimedAudioFrame> frames) {
        List<DecodedRegion> result = new ArrayList<>();
        if (frames.isEmpty()) return result;

        OpusDecoder decoder = serverApi.createDecoder();
        try {
            int i = 0;
            while (i < frames.size()) {
                int burstStart = i;
                int prevTick = frames.get(i).tick();
                i++;
                while (i < frames.size() && frames.get(i).tick() <= prevTick + 1) {
                    prevTick = frames.get(i).tick();
                    i++;
                }

                int burstSize = i - burstStart;
                short[] pcm = new short[burstSize * FRAME_SIZE];
                int writeHead = 0;
                boolean hasSignal = false;

                for (int j = burstStart; j < i; j++) {
                    short[] decoded = decoder.decode(frames.get(j).opus());
                    int copyLen = Math.min(decoded.length, FRAME_SIZE);
                    System.arraycopy(decoded, 0, pcm, writeHead, copyLen);
                    if (!hasSignal) {
                        for (int k = 0; k < copyLen; k++) {
                            if (decoded[k] != 0) {
                                hasSignal = true;
                                break;
                            }
                        }
                    }
                    writeHead += FRAME_SIZE;
                }

                if (hasSignal) {
                    result.add(new DecodedRegion(
                            frames.get(burstStart).tick(),
                            pcm,
                            frames.get(burstStart).position()));
                }
            }
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("[Voice] Decode failed", e);
        } finally {
            decoder.close();
        }
        return result;
    }

    /* ------------- VOICE DISTANCE ------------ */

    private static float getVoiceDistance() {
        if (serverApi == null) return TimeTravel.voiceAudioDistance;
        try {
            return (float) serverApi.getServerConfig().getDouble("max_voice_distance", TimeTravel.voiceAudioDistance);
        } catch (Exception e) {
            return TimeTravel.voiceAudioDistance;
        }
    }

    /* ------------- CONSTANTS ------------- */

    // 48 kHz · 50 ms/tick = 2400 samples per tick (nominal, used for catch-up).
    private static final int SAMPLES_PER_TICK = 2400;
    // Each Opus frame = 20 ms = 960 samples.
    private static final int FRAME_SIZE = 960;

    /* ------------- CHANNEL START ------------- */

    private static void startRegionEntityChannel(String key, short[] regionPcm,
                                                 net.minecraft.world.entity.Entity mcEntity) {
        de.maxhenkel.voicechat.api.Entity svcEntity = serverApi.fromEntity(mcEntity);
        EntityAudioChannel channel = serverApi.createEntityAudioChannel(
                UUID.randomUUID(), svcEntity
        );
        if (channel == null) {
            TimeTravel.LOOP_LOGGER.warn("[Voice] Failed to create EntityAudioChannel for key={}", key);
            return;
        }
        channel.setCategory(LOOP_CATEGORY.getId());
        channel.setDistance(getVoiceDistance());

        AudioPlayer player = createSimpleAudioPlayer(channel, regionPcm, mcEntity);
        players.put(key, player);
        player.startPlaying();
    }

    private static void startRegionLocationalChannel(String key, short[] regionPcm,
                                                     PlayerData data, int iter, int seg,
                                                     int regionStartTick,
                                                     net.minecraft.world.entity.Entity boundEntity,
                                                     Vec3 position) {
        ServerLevel mcLevel = (boundEntity != null && boundEntity.level() instanceof ServerLevel sl)
                ? sl : TimeTravel.serverLevel;
        de.maxhenkel.voicechat.api.ServerLevel level =
                serverApi.fromServerLevel(mcLevel);

        double px, py, pz;
        if (boundEntity != null) {
            px = boundEntity.getX();
            py = boundEntity.getY();
            pz = boundEntity.getZ();
        } else if (position != null) {
            px = position.x;
            py = position.y;
            pz = position.z;
        } else {
            px = 0;
            py = 0;
            pz = 0;
        }

        LocationalAudioChannel channel = serverApi.createLocationalAudioChannel(
                UUID.randomUUID(), level, serverApi.createPosition(px, py, pz)
        );
        channel.setCategory(LOOP_CATEGORY.getId());
        channel.setDistance(getVoiceDistance());

        AudioPlayer player = createSimpleAudioPlayer(channel, regionPcm, boundEntity);
        players.put(key, player);
        player.startPlaying();
    }

    private static AudioPlayer createSimpleAudioPlayer(AudioChannel channel, short[] regionPcm,
                                                       net.minecraft.world.entity.Entity boundEntity) {
        final int[] cursor = {0};

        return serverApi.createAudioPlayer(
                channel,
                serverApi.createEncoder(),
                () -> {
                    if (cursor[0] + FRAME_SIZE > regionPcm.length) return null;

                    short[] out = new short[FRAME_SIZE];
                    System.arraycopy(regionPcm, cursor[0], out, 0, FRAME_SIZE);
                    cursor[0] += FRAME_SIZE;

                    if (boundEntity != null && TimeTravel.voiceInteractionEnabled
                            && AudioUtils.isAboveThreshold(out, TimeTravel.voiceInteractionThresholdDb)) {
                        TimeTravel.server.execute(() ->
                                VoicechatInteractionCompat.emitVoiceEvent(boundEntity)
                        );
                    }

                    return out;
                }
        );
    }

    /* ------------- TICK UPDATE ------------- */

    public static void onTick() {
        if (!TimeTravel.voiceChatLoaded) return;
        processScheduledRegions();
    }

    private static void processScheduledRegions() {
        if (scheduledRegions.isEmpty()) return;

        long now = TimeTravel.timelineTick;

        List<ScheduledSpeechRegion> snapshot;
        synchronized (scheduledRegions) {
            snapshot = new ArrayList<>(scheduledRegions);
        }

        for (ScheduledSpeechRegion region : snapshot) {
            if (now < region.absStartTick) continue;
            scheduledRegions.remove(region);

            int totalFrames = region.pcm.length / FRAME_SIZE;
            long elapsedSamples = (now - region.absStartTick) * SAMPLES_PER_TICK;
            int skipFrames = (int) (elapsedSamples / FRAME_SIZE);
            if (skipFrames >= totalFrames) continue;

            short[] pcm = skipFrames > 0
                    ? Arrays.copyOfRange(region.pcm, skipFrames * FRAME_SIZE, region.pcm.length)
                    : region.pcm;

            if (region.entity != null && region.entity.isRemoved()) continue;

            if (region.entity != null && TimeTravel.showPlayerVoiceIcon) {
                startRegionEntityChannel(region.key, pcm, region.entity);
            } else {
                startRegionLocationalChannel(region.key, pcm,
                        region.data, region.iter, region.seg,
                        region.relStartTick, region.entity, region.position);
            }
        }
    }

    /* ------------- ENTITY LOOKUP ------------- */

    public static List<net.minecraft.world.entity.Entity> findAllMocapEntities(String nickname) {
        List<net.minecraft.world.entity.Entity> result = new ArrayList<>();
        if (nickname == null || TimeTravel.server == null) return result;

        Set<UUID> realPlayerUUIDs = new HashSet<>();
        for (net.minecraft.server.level.ServerPlayer sp : TimeTravel.server.getPlayerList().getPlayers()) {
            realPlayerUUIDs.add(sp.getUUID());
        }

        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    if (realPlayerUUIDs.contains(serverPlayer.getUUID())) continue;
                    if (nickname.equals(serverPlayer.getGameProfile().getName())) {
                        result.add(entity);
                    }
                }
            }
        }

        result.sort(Comparator.comparingInt(net.minecraft.world.entity.Entity::getId));
        return result;
    }

    /* ------------- PERSISTENCE ------------- */

    public static CompletableFuture<Void> saveAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeTravel.LOOP_LOGGER.info("[Voice] Saving all audio to disk...");
        return AudioPersistence.saveAllAudio().thenRun(() ->
                TimeTravel.LOOP_LOGGER.info("[Voice] Audio save complete.")
        );
    }

    public static CompletableFuture<Void> savePlayerAudio(PlayerData playerData) {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeTravel.LOOP_LOGGER.info("[Voice] Saving audio for player {}...", playerData.getName());
        return AudioPersistence.savePlayerAudio(playerData).thenRun(() ->
                TimeTravel.LOOP_LOGGER.info("[Voice] Audio save complete for {}.", playerData.getName())
        );
    }

    public static CompletableFuture<Void> loadAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeTravel.LOOP_LOGGER.info("[Voice] Loading audio from disk...");
        return AudioPersistence.loadAllAudio().thenRun(() ->
                TimeTravel.LOOP_LOGGER.info("[Voice] Audio load complete.")
        );
    }

    public static CompletableFuture<Void> deleteAllAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeTravel.LOOP_LOGGER.info("[Voice] Deleting all voice audio...");
        return AudioPersistence.deleteAllAudio().thenRun(() ->
                TimeTravel.LOOP_LOGGER.info("[Voice] All voice audio deleted.")
        );
    }

    public static void shutdown() {
        if (!TimeTravel.voiceChatLoaded) return;
        stopPlayback(null);
        ExecutorService pool = decodePool;
        if (pool != null) pool.shutdown();
        AudioPersistence.shutdown();
        TimeTravel.LOOP_LOGGER.info("[Voice] VoicechatImpl shut down.");
    }

    /* ------------- STOP ------------- */

    public static void stopPlayback(String player) {
        if (!TimeTravel.voiceChatLoaded) return;
        generation++;
        if (player == null || player.isEmpty()) {
            players.values().forEach(AudioPlayer::stopPlaying);
            players.clear();
            scheduledRegions.clear();
            return;
        }
        players.forEach((k, p) -> {
            if (k.startsWith(player + "|")) p.stopPlaying();
        });
        players.keySet().removeIf(k -> k.startsWith(player + "|"));
        scheduledRegions.removeIf(r -> r.data.getName().equals(player));
    }

    public static void stopPlaybackFrom(String player, int fromVisit) {
        if (!TimeTravel.voiceChatLoaded) return;
        generation++;
        java.util.function.Predicate<String> match = k -> {
            String[] parts = k.split("\\|");
            if (parts.length < 3 || !parts[0].equals(player)) return false;
            try {
                return Integer.parseInt(parts[1]) >= fromVisit;
            } catch (NumberFormatException e) {
                return false;
            }
        };
        players.forEach((k, p) -> {
            if (match.test(k)) p.stopPlaying();
        });
        players.keySet().removeIf(match);
        scheduledRegions.removeIf(r -> r.data.getName().equals(player) && r.iter >= fromVisit);
    }

    public static CompletableFuture<Void> deleteVisitAudio(PlayerData data, int visitId) {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        data.removeAudio(visitId);
        return AudioPersistence.deleteIterationAudio(data.getName(), visitId);
    }

    public static CompletableFuture<Void> saveVisitAudio(PlayerData data, int visitId) {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return AudioPersistence.saveVisitAudio(data, visitId);
    }

    /* ------------- KEY / HELPER TYPES ------------- */

    private static String key(String p, int i, int s) {
        return p + "|" + i + "|" + s;
    }

    private record ScheduledSpeechRegion(
            String key, PlayerData data, int iter, int seg, int regionIndex,
            short[] pcm, net.minecraft.world.entity.Entity entity,
            long absStartTick, int relStartTick,
            Vec3 position
    ) {}
}
