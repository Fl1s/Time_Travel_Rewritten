package com.fl1s.rewritten.timetravel.events;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.compat.VoicechatInteractionCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.mt1006.mocap.api.impl.controller.MocapControllerImpl;
import net.mt1006.mocap.api.v1.MocapAPI;
import net.mt1006.mocap.api.v1.controller.MocapController;

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
        TimeTravel.ghostLookaheadTicks = c.ghostLookaheadTicks;

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
        BlockJournal.load();
        EntityJournal.load();
        EffectJournal.load();
        MobJournal.load();
        MobBinding.load();
        ChatTimeline.load();
        AdvancementTimeline.load();

        TimeTravel.server = server;
        TimeTravel.serverLevel = server.overworld();

        // Create the mocap API controller (must happen after MocapMod has initialized)
        MocapAPI.executeAfterInit(() -> {
            // Workaround: MocapAPI.createController() has an inverted condition bug,
            // so instantiate the impl class directly.
            TimeTravel.mocapController = new MocapControllerImpl(server, TimeTravel.MOD_ID);

            MocapController mc = TimeTravel.mocapController;

            mc.setSetting("experimental_release_warning", "false");
            mc.setSetting("start_as_recorded", "true");
            mc.setSetting("assign_player_name", "true");
            mc.setSetting("start_instantly", "true");
            mc.setSetting("on_death", "continue_synced");
            mc.setSetting("on_change_dimension", "split_recording");
            mc.setSetting("chat_recording", "false");
            mc.setSetting("chat_playback", "false");
            mc.setSetting("invulnerable_playback", String.valueOf(!TimeTravel.hurtLoopedPlayers));
            mc.setSetting("use_creative_game_mode", String.valueOf(!TimeTravel.hurtLoopedPlayers));
            mc.setSetting("entity_tracking_distance", "1");
            mc.setSetting("hit_range", "1");
            mc.setSetting("block_initialization", "false");
            mc.setSetting("drop_from_blocks", "false");

            TimeTravel.updateEntitiesToTrack(TimeTravel.trackItems);

            TimeTravel.LOOP_LOGGER.info("Mocap API controller initialized");
        });

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
