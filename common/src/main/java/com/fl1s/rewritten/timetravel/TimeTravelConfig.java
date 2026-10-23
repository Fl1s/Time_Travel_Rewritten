package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class TimeTravelConfig {

    public String scenePrefix = "timeline_scene";
    public boolean firstStart = true;
    public boolean isLooping = false;
    public long timelineTick = 0;
    public long dayTimeOrigin = 0;
    public int nextVisitId = 1;
    public int configVersion = 0;

    public boolean syncDayTime = true;
    public boolean jumpRequiresOp = false;
    public boolean chainDeath = true;
    public int jumpCooldownTicks = 20;
    public Map<String, Long> marks = new java.util.LinkedHashMap<>();
    public long maxJumpTicks = 0;
    public int cloneLookaheadTicks = 0;

    public boolean showTimelineInfo = true;
    public boolean displayTimeInTicks = false;
    public boolean trackItems = true;
    public int entityTrackingDistance = 32; // Distance in blocks for mocap entity tracking (items, vehicles)
    public boolean trackChat = true;
    public boolean oldSfx = false;
    public boolean hurtPastSelves = true;

    public float hudScale = 1.0f;
    public boolean hudAtBottom = false;
    public boolean compactHud = false;

    /* ------------- Voice (Simple Voice Chat) ------------- */

    public boolean voiceInteractionEnabled = true;
    public int voiceInteractionThresholdDb = -50;
    public int voiceInteractionCooldownTicks = 20;
    public float voiceAudioDistance = 48.0f;
    public boolean showPlayerVoiceIcon = true;
    public boolean trackVoice = true;

    /* ------------- Player data ------------- */

    public Map<String, PlayerData> recordingPlayers = new HashMap<>();

    /* ------------- Serialization (not persisted) ------------- */

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;

    public static TimeTravelConfig load(Path configDir) {
        configPath = configDir.resolve("timetravel_rewritten.json");
        TimeTravelConfig config = null;

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                config = GSON.fromJson(reader, TimeTravelConfig.class);
            } catch (Exception e) {
                TimeTravel.TIMELINE_LOGGER.error("Failed to load config file", e);
            }
        }

        if (config == null) {
            config = new TimeTravelConfig();
            TimeTravel.TIMELINE_LOGGER.info("Config file not found or invalid. Using default config.");
        }

        if (config.recordingPlayers == null) {
            config.recordingPlayers = new HashMap<>();
        }

        config.recordingPlayers.values().forEach(PlayerData::initTransients);

        if (config.configVersion < 1) {
            config.configVersion = 1;
        }
        if (config.configVersion < 2) {
            config.chainDeath = true;
            config.hurtPastSelves = true;
            config.configVersion = 2;
        }
        if (config.maxJumpTicks < 0) config.maxJumpTicks = 0;
        if (config.jumpCooldownTicks < 0) config.jumpCooldownTicks = 20;
        if (config.marks == null) config.marks = new java.util.LinkedHashMap<>();
        if (config.cloneLookaheadTicks < 0 || config.cloneLookaheadTicks > 100) config.cloneLookaheadTicks = 0;
        if (config.timelineTick < 0) config.timelineTick = 0;
        if (config.nextVisitId < 1) config.nextVisitId = 1;
        if (config.entityTrackingDistance < 1) config.entityTrackingDistance = 32;
        if (config.voiceInteractionThresholdDb < -127 || config.voiceInteractionThresholdDb > 0) {
            config.voiceInteractionThresholdDb = -50;
        }
        if (config.hudScale < 0.5f || config.hudScale > 2.0f) config.hudScale = 1.0f;
        if (config.voiceInteractionCooldownTicks < 1) config.voiceInteractionCooldownTicks = 20;
        if (config.voiceAudioDistance < 1.0f) config.voiceAudioDistance = 48.0f;

        config.save();
        return config;
    }

    public void save() {
        if (configPath == null) {
            TimeTravel.TIMELINE_LOGGER.error("Cannot save config: configPath is null (load() not called yet)");
            return;
        }
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to save config file", e);
        }
    }
}
