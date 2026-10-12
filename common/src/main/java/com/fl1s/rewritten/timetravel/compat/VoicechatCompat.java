package com.fl1s.rewritten.timetravel.compat;

import com.fl1s.rewritten.timetravel.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A safe facade for Voicechat API interactions.
 * Prevents java.lang.NoClassDefFoundError by ensuring no third-party classes
 * are referenced in method signatures or fields.
 */
public final class VoicechatCompat {

    private VoicechatCompat() {}

    public static void onGhostStart(PlayerData data, int visitId, int segment, long visitStartTick, Entity entity) {
        if (!TimeTravel.voiceChatLoaded) return;
        VoicechatImpl.onGhostStart(data, visitId, segment, visitStartTick, entity);
    }

    public static Entity findNewestGhost(String nickname) {
        if (!TimeTravel.voiceChatLoaded) return null;
        return VoicechatImpl.findNewestGhost(nickname);
    }

    public static void stopPlaybackFrom(String player, int fromVisit) {
        if (!TimeTravel.voiceChatLoaded) return;
        VoicechatImpl.stopPlaybackFrom(player, fromVisit);
    }

    public static CompletableFuture<Void> saveVisitAudio(PlayerData data, int visitId) {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.saveVisitAudio(data, visitId);
    }

    public static CompletableFuture<Void> deleteVisitAudio(PlayerData data, int visitId) {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.deleteVisitAudio(data, visitId);
    }

    public static void onTick() {
        if (!TimeTravel.voiceChatLoaded) return;
        VoicechatImpl.onTick();
    }

    public static List<Entity> findAllMocapEntities(String nickname) {
        if (!TimeTravel.voiceChatLoaded) return Collections.emptyList();
        return VoicechatImpl.findAllMocapEntities(nickname);
    }

    public static CompletableFuture<Void> saveAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.saveAudio();
    }

    public static CompletableFuture<Void> savePlayerAudio(PlayerData playerData) {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.savePlayerAudio(playerData);
    }

    public static CompletableFuture<Void> loadAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.loadAudio();
    }

    public static CompletableFuture<Void> deleteAllAudio() {
        if (!TimeTravel.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.deleteAllAudio();
    }

    public static void shutdown() {
        if (!TimeTravel.voiceChatLoaded) return;
        VoicechatImpl.shutdown();
    }

    public static void stopPlayback(String player) {
        if (!TimeTravel.voiceChatLoaded) return;
        VoicechatImpl.stopPlayback(player);
    }
}
