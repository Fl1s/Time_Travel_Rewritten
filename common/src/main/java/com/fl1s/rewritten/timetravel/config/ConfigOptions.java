package com.fl1s.rewritten.timetravel.config;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;

import java.util.ArrayList;
import java.util.List;

public final class ConfigOptions {
    private ConfigOptions() {
    }

    public static final List<ConfigOption> CLIENT = List.of(
            new ConfigOption.Bool("display", "showTimelineInfo",
                    "Show the timeline info overlay.",
                    true, v -> TimeTravel.showTimelineInfo = v),
            new ConfigOption.Bool("display", "displayTimeInTicks",
                    "Show time in ticks instead of minutes and seconds.",
                    false, v -> TimeTravel.displayTimeInTicks = v),
            new ConfigOption.Dec("display", "hudScale",
                    "Scale of the timeline hud overlay.",
                    1.0, 0.5, 2.0, v -> TimeTravel.hudScale = (float) v),
            new ConfigOption.Bool("display", "hudAtBottom",
                    "Places the timeline hud at the bottom of the screen.",
                    false, v -> TimeTravel.hudAtBottom = v),
            new ConfigOption.Bool("display", "compactHud",
                    "Shows the current time only.",
                    false, v -> TimeTravel.compactHud = v),
            new ConfigOption.Bool("display", "isDarkHud",
                    "Uses dark theme for the timeline overlay.",
                    false, v -> TimeTravel.isDarkHud = v),
            new ConfigOption.Int("display", "maxHistorySize",
                    "Uses dark theme for the timeline overlay.",
                    50, 1, 100, v -> TimeTravel.maxHistorySize = v),

            new ConfigOption.Bool("sounds", "oldSfx",
                    "Use the original vanilla-based time travel sounds (v0.8.9 - v0.11.0).",
                    false, v -> TimeTravel.oldSfx = v),

            new ConfigOption.Bool("voice", "showPlayerVoiceIcon",
                    "Show the voice chat icon above players.",
                    true, v -> TimeTravel.showPlayerVoiceIcon = v),

            new ConfigOption.Bool("past self", "knightOfTimeMode", "links your time travel abilities to a clock and gears or something.. anyways GO READ HOMESTUCK!!! >:[", false, v -> {
                TimeTravel.knightOfTimeMode = v;
                if (v) TimeTravel.flowerManMode = false;
            }),

            new ConfigOption.Bool("past self", "flowerManMode", "With your powers combined! The TRUE power of friendship!", false, v -> {
                TimeTravel.flowerManMode = v;
                if (v) TimeTravel.knightOfTimeMode = false;
            })
    );

    public static final List<ConfigOption> SERVER = List.of(
            new ConfigOption.Bool("timeline", "syncDayTime",
                    "Synchronize the world day time with the timeline.",
                    true, v -> TimeTravel.syncDayTime = v),
            new ConfigOption.Bool("timeline", "jumpRequiresOp",
                    "Only operators may jump in time.",
                    true, v -> TimeTravel.jumpRequiresOp = v),
            new ConfigOption.Int("timeline", "maxJumpTicks",
                    "Maximum length of a single time jump, in ticks.",
                    Integer.MAX_VALUE, 1, Integer.MAX_VALUE, v -> TimeTravel.maxJumpTicks = v),
            new ConfigOption.Int("timeline", "jumpCooldownTicks",
                    "Cooldown between two time jumps, in ticks.",
                    20, 0, 1_000_000, v -> TimeTravel.jumpCooldownTicks = v),
            new ConfigOption.Bool("timeline", "chainDeath",
                    "If a past self dies outside of its recorded death, the timeline collapses from that visit.",
                    true, v -> TimeTravel.chainDeath = v),

            new ConfigOption.Bool("recording", "trackItems",
                    "Record item entities in addition to vehicles.",
                    true, v -> TimeTravel.trackItems = v),
            new ConfigOption.Int("recording", "entityTrackingDistance",
                    "Range in blocks around the player in which entities are tracked (used when trackItems is on).",
                    32, 1, 256, v -> TimeTravel.entityTrackingDistance = v),
            new ConfigOption.Bool("recording", "trackChat",
                    "Record chat messages on the timeline.",
                    true, v -> TimeTravel.trackChat = v),
            new ConfigOption.Bool("recording", "hurtPastSelves",
                    "If true, past selves take damage. Otherwise they are invulnerable and in creative mode during playback.",
                    false, v -> TimeTravel.hurtPastSelves = v),

            new ConfigOption.Int("clones", "cloneLookaheadTicks",
                    "How many ticks ahead of time the playback of a past self is started.",
                    0, 0, 200, v -> TimeTravel.cloneLookaheadTicks = v),

            new ConfigOption.Bool("voice", "trackVoice",
                    "Record voice chat audio on the timeline (requires Simple Voice Chat).",
                    true, v -> TimeTravel.trackVoice = v),
            new ConfigOption.Bool("voice", "voiceInteractionEnabled",
                    "Let voice trigger vibrations for sculk sensors (requires Voice Chat Interaction).",
                    true, v -> TimeTravel.voiceInteractionEnabled = v),
            new ConfigOption.Int("voice", "voiceInteractionThresholdDb",
                    "Voice loudness threshold in dB for voice interaction.",
                    -50, -127, 127, v -> TimeTravel.voiceInteractionThresholdDb = v),
            new ConfigOption.Int("voice", "voiceInteractionCooldownTicks",
                    "Cooldown between voice interaction events, in ticks.",
                    20, 0, 1200, v -> TimeTravel.voiceInteractionCooldownTicks = v),
            new ConfigOption.Dec("voice", "voiceAudioDistance",
                    "Hearing distance of replayed voice, in blocks.",
                    48.0, 1.0, 512.0, v -> TimeTravel.voiceAudioDistance = (float) v)
    );

    public static final List<ConfigOption> ALL = concat(CLIENT, SERVER);

    private static List<ConfigOption> concat(List<ConfigOption> a, List<ConfigOption> b) {
        List<ConfigOption> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    public static void afterApply() {
        if (TimeTravel.knightOfTimeMode && TimeTravel.flowerManMode) {
            TimeTravel.flowerManMode = false;
        }

        if (!MocapAdapter.isReady()) return;
        MocapAdapter.setInvulnerablePlayback(!TimeTravel.hurtPastSelves, !TimeTravel.hurtPastSelves);
        TimeTravel.updateEntitiesToTrack(TimeTravel.trackItems);
    }
}
