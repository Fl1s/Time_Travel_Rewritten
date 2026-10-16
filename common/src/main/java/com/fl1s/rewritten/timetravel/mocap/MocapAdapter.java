package com.fl1s.rewritten.timetravel.mocap;

import com.fl1s.rewritten.timetravel.Skin;
import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.mt1006.mocap.api.impl.controller.MocapControllerImpl;
import net.mt1006.mocap.api.v1.MocapAPI;
import net.mt1006.mocap.api.v1.controller.MocapController;
import net.mt1006.mocap.api.v1.controller.MocapPlaybackRoot;
import net.mt1006.mocap.api.v1.controller.config.MocapRecordingConfig;
import net.mt1006.mocap.api.v1.controller.playable.MocapActiveRecording;
import net.mt1006.mocap.api.v1.controller.playable.MocapRecordingFile;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.api.v1.modifiers.MocapPlayerSkin;
import net.mt1006.mocap.api.v1.modifiers.MocapTime;
import net.mt1006.mocap.api.v1.modifiers.MocapTimeModifiers;
import net.mt1006.mocap.mocap.actions.BreakBlock;
import net.mt1006.mocap.mocap.actions.PlaceBlock;
import net.mt1006.mocap.mocap.actions.RightClickBlock;
import net.mt1006.mocap.mocap.playing.playback.PlaybackRoot;
import net.mt1006.mocap.mocap.playing.playable.ActiveRecording;
import net.mt1006.mocap.mocap.recording.RecordingContext;
import net.mt1006.mocap.mocap.recording.RecordingManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Single point of contact with the Motion Capture mod.
 * <p>
 * All {@code net.mt1006.mocap.*} imports live here so that a Mocap API change
 * (or a change to the impl casts this mod relies on, e.g. {@code ActiveRecording}
 * and {@code PlaybackRoot}) needs to be fixed in one file instead of being
 * scattered across {@code TimeTravel} and {@code LifecycleEvent}.
 * <p>
 * Motion Capture records vehicles, projectiles and items but does not play them
 * back: playback of every tracked entity is delegated to the mod's own journals
 * ({@link com.fl1s.rewritten.timetravel.EntityJournal},
 * {@link com.fl1s.rewritten.timetravel.VehicleSpawnJournal}), so a backward
 * time jump can discard TNT, arrows and loot that mocap would otherwise leave
 * orphaned in the world.
 */
public final class MocapAdapter {
    private MocapAdapter() {}

    private static MocapController controller;
    private static final Map<String, MocapActiveRecording> activeRecordings = new ConcurrentHashMap<>();

    public static void init(MinecraftServer server, boolean hurtLoopedPlayers,
                            boolean trackItems, int entityTrackingDistance) {
        MocapAPI.executeAfterInit(() -> {
            controller = new MocapControllerImpl(server, TimeTravel.MOD_ID);

            controller.setSetting("experimental_release_warning", "false");
            controller.setSetting("start_as_recorded", "true");
            controller.setSetting("assign_player_name", "true");
            controller.setSetting("start_instantly", "true");
            controller.setSetting("on_death", "continue_synced");
            controller.setSetting("on_change_dimension", "split_recording");
            controller.setSetting("chat_recording", "false");
            controller.setSetting("chat_playback", "false");
            controller.setSetting("invulnerable_playback", String.valueOf(!hurtLoopedPlayers));
            controller.setSetting("use_creative_game_mode", String.valueOf(!hurtLoopedPlayers));
            controller.setSetting("entity_tracking_distance", "1");
            controller.setSetting("hit_range", "0");
            controller.setSetting("block_initialization", "false");
            controller.setSetting("drop_from_blocks", "false");

            updateEntitiesToTrack(trackItems, entityTrackingDistance);

            TimeTravel.LOOP_LOGGER.info("Mocap API controller initialized");
        });
    }

    public static boolean isReady() {
        return controller != null;
    }

    public static void reset() {
        activeRecordings.clear();
    }

    public static void updateEntitiesToTrack(boolean items, int entityTrackingDistance) {
        if (controller == null) return;
        String trackEntities = "@vehicles" + (items ? ";@items" : "");
        controller.setSetting("track_entities", trackEntities);
        controller.setSetting("play_entities", "");
        controller.setSetting("entity_tracking_distance", String.valueOf(items ? entityTrackingDistance : 1));
    }

    public static void setInvulnerablePlayback(boolean invulnerable, boolean creative) {
        if (controller == null) return;
        controller.setSetting("invulnerable_playback", String.valueOf(invulnerable));
        controller.setSetting("use_creative_game_mode", String.valueOf(creative));
    }

    public static void startRecording(ServerPlayer player, String playerName) {
        if (controller == null) {
            TimeTravel.LOOP_LOGGER.error("Mocap controller not ready, cannot record for {}", playerName);
            return;
        }
        MocapRecordingConfig recConfig = MocapRecordingConfig.createFromSettings();
        MocapActiveRecording recording = controller.startRecording(player, recConfig, true);
        if (recording != null) {
            activeRecordings.put(playerName, recording);
        } else {
            TimeTravel.LOOP_LOGGER.error("Failed to start mocap recording for player: {}", playerName);
        }
    }

    public static final class RecordingHandle {
        private final MocapActiveRecording rec;
        private RecordingHandle(MocapActiveRecording rec) { this.rec = rec; }
    }

    public static List<RecordingHandle> stopAllRecordings(String playerName) {
        List<RecordingHandle> result = new ArrayList<>();
        CommandOutput out = CommandOutput.LOGS;

        MocapActiveRecording primaryRec = activeRecordings.remove(playerName);
        if (primaryRec != null && primaryRec.isValid()) {
            RecordingContext ctx = ((ActiveRecording) primaryRec).ctx;
            ctx.stop(out);
            if (ctx.state == RecordingContext.State.WAITING_FOR_DECISION) {
                result.add(new RecordingHandle(primaryRec));
            } else {
                TimeTravel.LOOP_LOGGER.warn("Primary recording for '{}' failed to stop properly (state: {}). Skipping save.",
                        playerName, ctx.state);
            }
        } else {
            TimeTravel.LOOP_LOGGER.warn("No primary mocap recording found for player: {}", playerName);
        }

        List<RecordingContext> splitContexts = new ArrayList<>();
        for (RecordingContext ctx : new ArrayList<>(RecordingManager.allContexts())) {
            if (!ctx.source.name.equals("+" + TimeTravel.MOD_ID)) continue;
            if (!ctx.recordedPlayer.getName().getString().equals(playerName)) continue;
            if (ctx.isRemoved()) continue;
            splitContexts.add(ctx);
        }

        for (RecordingContext ctx : splitContexts) {
            ctx.stop(out);
            if (ctx.state == RecordingContext.State.WAITING_FOR_DECISION) {
                result.add(new RecordingHandle(ActiveRecording.get(ctx)));
            } else {
                TimeTravel.LOOP_LOGGER.warn("Split context for {} in unexpected state: {}, skipping", playerName, ctx.state);
            }
        }

        return result;
    }

    public static boolean save(RecordingHandle handle, String name) {
        if (handle == null) return false;
        try {
            stripBlockActions(handle.rec);
            MocapRecordingFile file = handle.rec.save(CommandOutput.LOGS, name);
            return file != null;
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("Failed to save recording '{}'", name, e);
            return false;
        }
    }

    public static void discard(RecordingHandle handle) {
        if (handle == null) return;
        try {
            handle.rec.discard(CommandOutput.LOGS);
        } catch (Exception ignored) {
        }
    }

    public static void discardAllRecordings(String playerName) {
        MocapActiveRecording primary = activeRecordings.remove(playerName);
        if (primary != null) {
            try { primary.discard(CommandOutput.LOGS); } catch (Exception ignored) {}
        }
        for (RecordingContext ctx : new ArrayList<>(RecordingManager.allContexts())) {
            if (!ctx.source.name.equals("+" + TimeTravel.MOD_ID)) continue;
            if (!ctx.recordedPlayer.getName().getString().equals(playerName)) continue;
            if (ctx.isRemoved()) continue;
            try { ctx.stop(CommandOutput.LOGS); } catch (Exception ignored) {}
        }
    }

    public static void deleteRecording(String name) {
        try {
            MocapRecordingFile file = MocapRecordingFile.get(CommandOutput.LOGS, name);
            if (file != null && file.exists()) file.remove(CommandOutput.LOGS);
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("Failed to delete recording {}", name, e);
        }
    }

    private static void stripBlockActions(MocapActiveRecording rec) {
        try {
            if (!(rec instanceof ActiveRecording active) || active.ctx == null) return;
            Predicate<Object> isWorldAction = a -> a instanceof PlaceBlock
                    || a instanceof BreakBlock || a instanceof RightClickBlock;
            active.ctx.data.actions.removeIf(isWorldAction);
            active.ctx.data.blockActions.removeIf(isWorldAction);
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("Failed to strip block actions from a recording", e);
        }
    }

    public static GhostPlayback startPlayback(String recordingName, String nickname, Skin skin,
                                              int delayTicks) {
        if (controller == null) return null;

        MocapRecordingFile file = MocapRecordingFile.get(CommandOutput.LOGS, recordingName);
        if (file == null || !file.exists()) {
            TimeTravel.LOOP_LOGGER.warn("Recording '{}' not found - skipping ghost", recordingName);
            return null;
        }

        MocapPlayerSkin mocapSkin = switch (skin.skinType) {
            case MINESKIN -> MocapPlayerSkin.fromMineSkin(skin.value);
            case FILE -> MocapPlayerSkin.fromFile(skin.value);
            default -> MocapPlayerSkin.fromPlayer(skin.value);
        };
        MocapModifiers modifiers = MocapModifiers.playerName(nickname).withPlayerSkin(mocapSkin);
        MocapTimeModifiers timeMods = modifiers.getTimeModifiers();
        if (delayTicks > 0) {
            timeMods = timeMods.withStartDelay(MocapTime.fromTicks(delayTicks));
        }
        modifiers = modifiers.withTimeModifiers(timeMods);

        try {
            MocapPlaybackRoot root = file.startPlayback(controller.getCommandInfo(), modifiers);
            if (root == null) return null;
            return new GhostPlaybackImpl(root);
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("Failed to start ghost '{}': {}", recordingName, e.getMessage(), e);
            return null;
        }
    }

    private static final class GhostPlaybackImpl implements GhostPlayback {
        private final MocapPlaybackRoot root;
        GhostPlaybackImpl(MocapPlaybackRoot root) { this.root = root; }
        @Override public boolean isFinished() { return root.isFinished(); }
        @Override public void stop() { root.stop(); }

        @Override
        public void tick(int count) {
            if (!(root instanceof PlaybackRoot playbackRoot)) return;
            for (int i = 0; i < count && !root.isFinished(); i++) {
                playbackRoot.tick();
            }
        }
    }
}