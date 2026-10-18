package com.fl1s.rewritten.timetravel.events;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.compat.VoicechatInteractionCompat;
import com.fl1s.rewritten.timetravel.journal.ProcessJournal;
import com.fl1s.rewritten.timetravel.mob.MobBinding;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.util.LoopSceneManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
public class LifecycleEvent {
    public static void onServerStart(MinecraftServer server) {
        TimeTravel.resetSession();
        ProcessJournal.clear();
        TimeTravel.worldFolder = server.getWorldPath(LevelResource.ROOT);
        TimeTravel.config = TimeTravelConfig.load(TimeTravel.worldFolder);
        TimeTravel.loopSceneManager = new LoopSceneManager(TimeTravel.config);

        TimeTravelConfig c = TimeTravel.config;
        TimeTravel.isLooping = c.isLooping;
        TimeTravel.timelineTick = c.timelineTick;
        TimeTravel.dayTimeOrigin = c.dayTimeOrigin;
        TimeTravel.syncDayTime = c.syncDayTime;
        TimeTravel.jumpRequiresOp = c.jumpRequiresOp;
        TimeTravel.chainDeath = c.chainDeath;
        TimeTravel.jumpCooldownTicks = c.jumpCooldownTicks;
        TimeTravel.maxJumpTicks = c.maxJumpTicks;
        TimeTravel.cloneLookaheadTicks = c.cloneLookaheadTicks;

        TimeTravel.showLoopInfo = c.showLoopInfo;
        TimeTravel.displayTimeInTicks = c.displayTimeInTicks;
        TimeTravel.trackItems = c.trackItems;
        TimeTravel.entityTrackingDistance = c.entityTrackingDistance;
        TimeTravel.trackChat = c.trackChat;
        TimeTravel.hurtLoopedPlayers = c.hurtLoopedPlayers;

        TimeTravel.voiceInteractionEnabled = c.voiceInteractionEnabled;
        TimeTravel.voiceInteractionThresholdDb = c.voiceInteractionThresholdDb;
        TimeTravel.voiceInteractionCooldownTicks = c.voiceInteractionCooldownTicks;
        TimeTravel.voiceAudioDistance = c.voiceAudioDistance;
        TimeTravel.showPlayerVoiceIcon = c.showPlayerVoiceIcon;
        TimeTravel.trackVoice = c.trackVoice;

        TimeTravel.loopSceneManager.setRecordingPlayers(c.recordingPlayers);

        TimeTravel.server = server;
        TimeTravel.serverLevel = server.overworld();

        TimeTravel.JOURNALS.loadAll();
        MobBinding.load();

        MocapAdapter.init(server, TimeTravel.hurtLoopedPlayers, TimeTravel.trackItems, TimeTravel.entityTrackingDistance);

        VoicechatInteractionCompat.resolveGameEvent();
        VoicechatCompat.loadAudio();
    }

    public static void onServerStopping() {
        TimeTravel.onServerStopping();
        if (TimeTravel.voiceChatLoaded) {
            try {
                VoicechatCompat.saveAudio().get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                TimeTravel.LOOP_LOGGER.warn("Timed out saving voice audio on shutdown");
            } catch (Exception e) {
                TimeTravel.LOOP_LOGGER.error("Failed to save voice audio on shutdown", e);
            }
        }

        VoicechatCompat.shutdown();
    }
}
