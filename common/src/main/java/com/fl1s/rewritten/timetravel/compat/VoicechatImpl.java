package com.fl1s.rewritten.timetravel.compat;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.compat.voicechat.AudioPersistence;
import com.fl1s.rewritten.timetravel.compat.voicechat.AudioUtils;
import com.fl1s.rewritten.timetravel.compat.voicechat.TimedAudioFrame;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.*;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
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
 * Voice playback decodes Opus frames into PCM and extracts speech regions
 * (contiguous bursts of frames without tick gaps). Each region is streamed to its
 * audio channel from the server tick, a fixed amount of samples per tick, so playback
 * is bound to the timeline clock: when the world is paused, the voices stop with it.
 */
public final class VoicechatImpl {

    private VoicechatImpl() {}

    public static VoicechatServerApi serverApi;
    public static VolumeCategory TIMELINE_CATEGORY;

    private static final String CATEGORY_ID = "tt_voice";
    private static volatile boolean categoryWarned = false;

    private static final int SAMPLES_PER_TICK = 2400;
    private static final int FRAME_SIZE = 960;
    private static final int GAP_MS = 200;
    private static final int SAMPLES_PER_MS = 48;
    private static final Map<String, ActiveRegion> players = new ConcurrentHashMap<>();
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

    public static void initCategory() {
        try {
            TIMELINE_CATEGORY = serverApi.volumeCategoryBuilder()
                    .setId(CATEGORY_ID)
                    .setName("TT Voices")
                    .build();
            serverApi.registerVolumeCategory(TIMELINE_CATEGORY);
        } catch (Exception e) {
            TimeTravel.TIMELINE_LOGGER.error("[Voice] Failed to register volume category '{}' - "
                    + "clone voices will be silent", CATEGORY_ID, e);
            TIMELINE_CATEGORY = null;
        }
    }

    private static String safeCategoryId() {
        VolumeCategory cat = TIMELINE_CATEGORY;
        if (cat == null) {
            if (!categoryWarned) {
                categoryWarned = true;
                TimeTravel.TIMELINE_LOGGER.warn(
                        "[Voice] Volume category unavailable - skipping clone voice playback");
            }
            return null;
        }
        return cat.getId();
    }

    private static volatile int generation = 0;

    public static void onCloneStart(PlayerData data, int visitId, int segment, long visitStartTick,
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

    public static net.minecraft.world.entity.Entity findNewestClone(String nickname) {
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

    private record DecodedRegion(int startTick, short[] pcm, Vec3 position) {}

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
            TimeTravel.TIMELINE_LOGGER.error("[Voice] Decode failed", e);
        } finally {
            decoder.close();
        }
        return result;
    }

    private static float getVoiceDistance() {
        if (serverApi == null) return TimeTravel.voiceAudioDistance;
        try {
            return (float) serverApi.getServerConfig().getDouble("max_voice_distance", TimeTravel.voiceAudioDistance);
        } catch (Exception e) {
            return TimeTravel.voiceAudioDistance;
        }
    }

    private static final class ActiveRegion {
        final String key;
        final AudioChannel channel;
        final OpusEncoder encoder;
        final short[] pcm;
        final net.minecraft.world.entity.Entity entity;
        int cursor;
        int budget;
        long sentSamples;
        long startNanos = -1;
        long lastPumpNanos;

        ActiveRegion(String key, AudioChannel channel, OpusEncoder encoder, short[] pcm,
                     net.minecraft.world.entity.Entity entity) {
            this.key = key;
            this.channel = channel;
            this.encoder = encoder;
            this.pcm = pcm;
            this.entity = entity;
        }

        boolean finished() {
            return cursor + FRAME_SIZE > pcm.length;
        }

        private void compensateGap(long now) {
            long gapMs = (now - lastPumpNanos) / 1_000_000L;
            if (gapMs <= GAP_MS) return;
            long playedMs = (lastPumpNanos - startNanos) / 1_000_000L;
            long lead = Math.max(0, sentSamples - playedMs * SAMPLES_PER_MS);
            long heard = Math.min(lead, gapMs * SAMPLES_PER_MS);
            int back = (int) (heard / FRAME_SIZE) * FRAME_SIZE;
            cursor = Math.max(0, cursor - back);
            budget = 0;
            sentSamples = 0;
            startNanos = now;
        }

        void pump() {
            long now = System.nanoTime();
            if (startNanos < 0) {
                startNanos = now;
            } else {
                compensateGap(now);
            }
            lastPumpNanos = now;

            budget += SAMPLES_PER_TICK;
            while (budget >= FRAME_SIZE && !finished()) {
                short[] out = new short[FRAME_SIZE];
                System.arraycopy(pcm, cursor, out, 0, FRAME_SIZE);
                cursor += FRAME_SIZE;
                budget -= FRAME_SIZE;
                sentSamples += FRAME_SIZE;
                if (entity != null && TimeTravel.voiceInteractionEnabled
                        && AudioUtils.isAboveThreshold(out, TimeTravel.voiceInteractionThresholdDb)) {
                    VoicechatInteractionCompat.emitVoiceEvent(entity);
                }
                channel.send(encoder.encode(out));
            }
        }

        void stop() {
            try {
                channel.flush();
            } catch (Exception ignored) {
            }
            try {
                encoder.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void startRegionEntityChannel(String key, short[] regionPcm,
                                                 net.minecraft.world.entity.Entity mcEntity) {
        String catId = safeCategoryId();
        if (catId == null) return;

        de.maxhenkel.voicechat.api.Entity svcEntity = serverApi.fromEntity(mcEntity);
        EntityAudioChannel channel = serverApi.createEntityAudioChannel(UUID.randomUUID(), svcEntity);
        if (channel == null) {
            TimeTravel.TIMELINE_LOGGER.warn("[Voice] Failed to create EntityAudioChannel for key={}", key);
            return;
        }
        channel.setCategory(catId);
        channel.setDistance(getVoiceDistance());
        register(key, channel, regionPcm, mcEntity);
    }

    private static void startRegionLocationalChannel(String key, short[] regionPcm,
                                                     PlayerData data, int iter, int seg,
                                                     int regionStartTick,
                                                     net.minecraft.world.entity.Entity boundEntity,
                                                     Vec3 position) {
        String catId = safeCategoryId();
        if (catId == null) return;

        ServerLevel mcLevel = (boundEntity != null && boundEntity.level() instanceof ServerLevel sl)
                ? sl : TimeTravel.serverLevel;
        de.maxhenkel.voicechat.api.ServerLevel level = serverApi.fromServerLevel(mcLevel);

        double px = 0, py = 0, pz = 0;
        if (boundEntity != null) {
            px = boundEntity.getX();
            py = boundEntity.getY();
            pz = boundEntity.getZ();
        } else if (position != null) {
            px = position.x;
            py = position.y;
            pz = position.z;
        }

        LocationalAudioChannel channel = serverApi.createLocationalAudioChannel(
                UUID.randomUUID(), level, serverApi.createPosition(px, py, pz));
        channel.setCategory(catId);
        channel.setDistance(getVoiceDistance());
        register(key, channel, regionPcm, boundEntity);
    }

    private static void register(String key, AudioChannel channel, short[] regionPcm,
                                 net.minecraft.world.entity.Entity entity) {
        ActiveRegion region = new ActiveRegion(key, channel, serverApi.createEncoder(), regionPcm, entity);
        ActiveRegion old = players.put(key, region);
        if (old != null) old.stop();
        region.pump();
    }

    public static void onTick() {
        if (!TimeTravel.voiceChatLoaded) return;
        processScheduledRegions();
        pumpActive();
    }

    private static void pumpActive() {
        if (players.isEmpty()) return;
        for (ActiveRegion region : new ArrayList<>(players.values())) {
            if (region.entity != null && region.entity.isRemoved()) {
                players.remove(region.key);
                region.stop();
                continue;
            }
            region.pump();
            if (region.finished()) {
                players.remove(region.key);
                region.stop();
            }
        }
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

    public static CompletableFuture<Void> saveAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeTravel.TIMELINE_LOGGER.info("[Voice] Saving all audio to disk...");
        return AudioPersistence.saveAllAudio().thenRun(() ->
                TimeTravel.TIMELINE_LOGGER.info("[Voice] Audio save complete.")
        );
    }

    public static CompletableFuture<Void> savePlayerAudio(PlayerData playerData) {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeTravel.TIMELINE_LOGGER.info("[Voice] Saving audio for player {}...", playerData.getName());
        return AudioPersistence.savePlayerAudio(playerData).thenRun(() ->
                TimeTravel.TIMELINE_LOGGER.info("[Voice] Audio save complete for {}.", playerData.getName())
        );
    }

    public static CompletableFuture<Void> loadAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeTravel.TIMELINE_LOGGER.info("[Voice] Loading audio from disk...");
        return AudioPersistence.loadAllAudio().thenRun(() ->
                TimeTravel.TIMELINE_LOGGER.info("[Voice] Audio load complete.")
        );
    }

    public static CompletableFuture<Void> deleteAllAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        TimeTravel.TIMELINE_LOGGER.info("[Voice] Deleting all voice audio...");
        return AudioPersistence.deleteAllAudio().thenRun(() ->
                TimeTravel.TIMELINE_LOGGER.info("[Voice] All voice audio deleted.")
        );
    }

    public static void shutdown() {
        if (!TimeTravel.voiceChatLoaded) return;
        stopPlayback(null);
        ExecutorService pool = decodePool;
        if (pool != null) pool.shutdown();
        AudioPersistence.shutdown();
        TimeTravel.TIMELINE_LOGGER.info("[Voice] VoicechatImpl shut down.");
    }

    public static void stopPlayback(String player) {
        if (!TimeTravel.voiceChatLoaded) return;
        generation++;
        if (player == null || player.isEmpty()) {
            players.values().forEach(ActiveRegion::stop);
            players.clear();
            scheduledRegions.clear();
            return;
        }
        players.forEach((k, p) -> {
            if (k.startsWith(player + "|")) p.stop();
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
            if (match.test(k)) p.stop();
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
