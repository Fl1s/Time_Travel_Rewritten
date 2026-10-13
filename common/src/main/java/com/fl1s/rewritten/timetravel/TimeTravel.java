package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.compat.VoicechatInteractionCompat;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.net.TimelineSyncPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.mt1006.mocap.api.v1.controller.MocapController;
import net.mt1006.mocap.api.v1.controller.MocapPlaybackRoot;
import net.mt1006.mocap.api.v1.controller.config.MocapRecordingConfig;
import net.mt1006.mocap.api.v1.controller.playable.MocapActiveRecording;
import net.mt1006.mocap.api.v1.controller.playable.MocapRecordingFile;
import net.mt1006.mocap.api.v1.io.CommandInfo;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.api.v1.modifiers.MocapPlayerSkin;
import net.mt1006.mocap.api.v1.modifiers.MocapTime;
import net.mt1006.mocap.api.v1.modifiers.MocapTimeModifiers;
import net.mt1006.mocap.mocap.playing.playback.PlaybackRoot;
import net.mt1006.mocap.mocap.actions.BreakBlock;
import net.mt1006.mocap.mocap.actions.PlaceBlock;
import net.mt1006.mocap.mocap.actions.RightClickBlock;
import net.mt1006.mocap.mocap.playing.playable.ActiveRecording;
import net.mt1006.mocap.mocap.recording.RecordingContext;
import net.mt1006.mocap.mocap.recording.RecordingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class TimeTravel {
	public static final String MOD_ID = "timetravel_rewritten";
	public static final Logger LOOP_LOGGER = LoggerFactory.getLogger("TimeTravel");
	public static MinecraftServer server;
	public static ServerLevel serverLevel;
	public static MocapController mocapController;
	public static final Map<String, MocapActiveRecording> activeRecordings = new ConcurrentHashMap<>();

	public static boolean isLooping;
	public static long timelineTick;
	public static long dayTimeOrigin;
	public static boolean syncDayTime;
	public static boolean jumpRequiresOp;
	public static long maxJumpTicks;
	public static int jumpCooldownTicks = 20;
	public static long lastJumpOrigin = -1;
	private static long lastJumpServerTick = -1;

	public static boolean chainDeath;

	public static boolean showLoopInfo;
	public static boolean displayTimeInTicks;
	public static boolean trackItems;
	public static int entityTrackingDistance;
	public static boolean trackChat;
	public static boolean hurtLoopedPlayers;


	public static boolean voiceChatLoaded;
	public static boolean voiceInteractionLoaded;
	public static boolean voiceInteractionEnabled;
	public static int voiceInteractionThresholdDb;
	public static int voiceInteractionCooldownTicks;
	public static float voiceAudioDistance;
	public static boolean showPlayerVoiceIcon;
	public static boolean trackVoice;

	public static TimeTravelConfig config;
	public static LoopSceneManager loopSceneManager;
	public static Path worldFolder;

	public static final Queue<Runnable> delayedTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();

	private record PendingPiece(long startTick, String recording, String playerName, int skipTicks, int visitId,
			int segment, long visitStartTick) {}

	private record Ghost(MocapPlaybackRoot root, String playerName, int visitId, GhostFx fx) {}


	private record PendingSound(long tick, String dim, double x, double y, double z, int dir, String playerName, int visitId) {}

	private static final PriorityQueue<PendingSound> pendingSounds =
			new PriorityQueue<>(Comparator.comparingLong(PendingSound::tick));


	private static final PriorityQueue<PendingPiece> pending =
			new PriorityQueue<>(Comparator.comparingLong(PendingPiece::startTick));
	private static final List<Ghost> ghostRoots = new ArrayList<>();
	public static int ghostLookaheadTicks = 0;
	private static boolean needsRebuild = true;
	private static boolean idle = false;

	public static void queueTask(Runnable task) {
		delayedTasks.add(task);
	}

	public static void init() {
		LOOP_LOGGER.info("Initializing TimeTravel mod (Common)");
	}

	public static CommandInfo getMocapCommandInfo() {
		return mocapController.getCommandInfo();
	}

	//timeline contol
	public static void startLoop() {
		if (isLooping) {
			LOOP_LOGGER.info("Timeline already running");
			return;
		}
		if (mocapController == null) {
			LOOP_LOGGER.error("Mocap controller is not ready yet - cannot start timeline");
			return;
		}

		isLooping = true;
		config.isLooping = true;
		dayTimeOrigin = serverLevel.getDayTime() - timelineTick;
		config.dayTimeOrigin = dayTimeOrigin;
		idle = false;

		forEachOnlineRecorded(TimeTravel::beginVisit);
		needsRebuild = true;
		BlockJournal.rebuildUpcoming();
		MobJournal.rebuildUpcoming();
		ProcessJournal.rebuildUpcoming();
		ChatTimeline.rebuildUpcoming();
		config.save();
		broadcastSync();
		LOOP_LOGGER.info("Timeline started at tick {}", timelineTick);
	}

	public static void stopLoop() {
		if (!isLooping) return;
		LOOP_LOGGER.info("Stopping timeline at tick {}", timelineTick);

		BlockJournal.flushWatches();
		endAllVisits();
		stopGhosts();
		pending.clear();
		BlockJournal.save();
		MobJournal.save();
		ChatTimeline.save();

		isLooping = false;
		config.isLooping = false;
		config.timelineTick = timelineTick;
		loopSceneManager.saveRecordingPlayers();
		VoicechatInteractionCompat.clearCooldowns();
		config.save();
		broadcastSync();
	}

	public static void resetTimeline() {
		stopLoop();
		stopGhosts();
		pending.clear();

		removeAllRecordings();
		BlockJournal.clear();
		MobJournal.clear();
		ProcessJournal.clear();
		ChatTimeline.clear();
		VoicechatCompat.deleteAllAudio();
		loopSceneManager.forEachRecordingPlayer(pd -> pd.getVisits().clear());

		timelineTick = 0;
		config.timelineTick = 0;
		config.nextVisitId = 1;
		lastJumpServerTick = -1;
		lastJumpOrigin = -1;
		needsRebuild = true;
		config.save();
		broadcastSync();
	}

	public static String jumpTo(long target, String initiatorName) {
		if (!isLooping) return "Timeline is not running (use /timetravel start)";
		if (mocapController == null) return "Mocap is not ready yet";

		target = Math.max(0, target);
		if (maxJumpTicks > 0) {
			long min = Math.max(0, timelineTick - maxJumpTicks);
			long max = timelineTick + maxJumpTicks;
			target = Math.min(max, Math.max(min, target));
		}
		if (target == timelineTick) return "Already at " + TimeFormat.clock(target);

		long nowServerTick = server.getTickCount();
		if (jumpCooldownTicks > 0 && lastJumpServerTick >= 0 && nowServerTick - lastJumpServerTick < jumpCooldownTicks) {
			return "Time jump cooldown - wait a moment";
		}
		lastJumpServerTick = nowServerTick;

		long from = timelineTick;
		lastJumpOrigin = from;
		int dir = target > from ? 1 : -1;
		LOOP_LOGGER.info("Time jump by {}: {} -> {}", initiatorName, from, target);

		BlockJournal.flushWatches();
		ProcessJournal.beforeJump();
		endAllVisits(dir);

		stopGhosts();

		timelineTick = target;
		config.timelineTick = target;
		if (syncDayTime) {
			serverLevel.setDayTime(dayTimeOrigin + target);
		}
		idle = false;

		BlockJournal.onJump(from, target);
		MobJournal.onJump(from, target);
		ProcessJournal.afterJump(from, target);
		ChatTimeline.onJump(target);

		forEachOnlineRecorded((p, pd) -> beginVisit(p, pd, dir));
		rebuildPending();
		flushPending();
		flushSounds();

		loopSceneManager.saveRecordingPlayers();
		config.save();
		broadcastSync();

		String msg = initiatorName + " jumped " + (target > from ? "forward" : "back") + " to "
				+ TimeFormat.format(target, displayTimeInTicks) + " ("
				+ TimeFormat.delta(target - from, displayTimeInTicks) + ")";
		server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
		return null;
	}

	public static void onServerTick() {
		Runnable task;
		while ((task = delayedTasks.poll()) != null) {
			try {
				task.run();
			} catch (Exception e) {
				LOOP_LOGGER.error("Error executing delayed task", e);
			}
		}

		BlockJournal.flushWatches();

		if (!isLooping || mocapController == null) return;

		if (countOnlineRecorded() == 0) {
			if (!idle) {
				idle = true;
				stopGhosts();
				needsRebuild = true;
			}
			return;
		}
		idle = false;

		if (needsRebuild) rebuildPending();
		flushPending();
		flushSounds();

		timelineTick++;
		config.timelineTick = timelineTick;
		BlockJournal.tick();
		MobJournal.tick();
		ProcessJournal.tick();
		ChatTimeline.tick();
		if (syncDayTime) {
			serverLevel.setDayTime(dayTimeOrigin + timelineTick);
		}
		for (Ghost ghost : ghostRoots) {
			if (!ghost.root().isFinished()) ghost.fx().tick();
		}
		if (trackVoice) VoicechatCompat.onTick();
		pollDeaths();
		pollGhostKills();

		if (timelineTick % 20 == 0) broadcastSync();
		if (timelineTick % 100 == 0) ghostRoots.removeIf(g -> g.root().isFinished());
		if (timelineTick % 1200 == 0) config.save();
		if (timelineTick % 6000 == 0) {
			BlockJournal.saveIfDirty();
			MobJournal.saveIfDirty();
			ChatTimeline.saveIfDirty();
		}
	}

	public static void handlePlayerDimensionChange(ServerPlayer player) {
		if (!isLooping) return;

		PlayerData pd = loopSceneManager.getRecordingPlayer(player.getName().getString());
		if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

		ResourceKey<Level> currentDimension = player.level().dimension();
		if (currentDimension.equals(pd.getLastDimensionKey())) return;

		pd.addPieceStart(timelineTick);
		pd.nextSegment();
		pd.setLastDimensionKey(currentDimension);

		LOOP_LOGGER.info("Player '{}' changed dimension at tick {} (pieces in chunk: {})",
				player.getName().getString(), timelineTick, pd.getPieceStarts().size());
	}

	//player lifecycle
	public static void onPlayerJoined(ServerPlayer player, PlayerData pd) {
		if (isLooping && mocapController != null && pd.getCurrentVisit() == null) {
			beginVisit(player, pd);
			if (idle) { idle = false; needsRebuild = true; }
		}
		sendSync(player);
	}

	public static void onPlayerLeft(PlayerData pd) {
		if (isLooping && pd != null && pd.getCurrentVisit() != null) {
			endVisit(pd);
			loopSceneManager.saveRecordingPlayers();
			config.save();
		}
	}

	public static void onServerStopping() {
		if (isLooping) {
			endAllVisits();
			stopGhosts();
			loopSceneManager.saveRecordingPlayers();
			config.isLooping = true;
			config.timelineTick = timelineTick;
		}
		BlockJournal.flushWatches();
		BlockJournal.save();
		MobJournal.save();
		ChatTimeline.save();
		if (worldFolder != null && config != null) config.save();
		VoicechatInteractionCompat.clearCooldowns();
	}

	//visits and recording
	private static void beginVisit(ServerPlayer player, PlayerData pd) {
		beginVisit(player, pd, 0);
	}

	private static void beginVisit(ServerPlayer player, PlayerData pd, int arrivalDir) {
		Visit visit = new Visit(config.nextVisitId++, timelineTick);
		visit.arrivalDir = arrivalDir;
		visit.startDim = player.level().dimension().location().toString();
		visit.sx = player.getX();
		visit.sy = player.getY();
		visit.sz = player.getZ();
		pd.setCurrentVisit(visit);
		pd.resetSegments();
		pd.resetPieceCounter();
		pd.setLastDimensionKey(player.level().dimension());
		startChunk(player, pd);
	}

	private static void endVisit(PlayerData pd) {
		endVisit(pd, 0);
	}

	private static void endVisit(PlayerData pd, int departDir) {
		Visit visit = pd.getCurrentVisit();
		if (visit == null) return;

		ServerPlayer online = server.getPlayerList().getPlayerByName(pd.getName());
		if (online != null) {
			visit.endDim = online.level().dimension().location().toString();
			visit.ex = online.getX();
			visit.ey = online.getY();
			visit.ez = online.getZ();
		}
		visit.departDir = (online != null) ? departDir : 0;

		finishChunk(pd);
		visit.endTick = timelineTick;
		pd.setCurrentVisit(null);

		if (visit.doomed) {
			for (Visit.Piece piece : visit.pieces) deleteRecording(piece.recording);
			VoicechatCompat.deleteVisitAudio(pd, visit.id);
			LOOP_LOGGER.info("Visit {} of '{}' was doomed by a death chain - erased", visit.id, pd.getName());
		} else if (!visit.pieces.isEmpty() && visit.endTick > visit.startTick) {
			pd.getVisits().add(visit);
			if (trackVoice) VoicechatCompat.saveVisitAudio(pd, visit.id);
			LOOP_LOGGER.info("Visit {} of '{}' saved: ticks {}..{}, {} piece(s)",
					visit.id, pd.getName(), visit.startTick, visit.endTick, visit.pieces.size());
		} else {
			LOOP_LOGGER.info("Visit {} of '{}' was empty - dropped", visit.id, pd.getName());
		}
	}

	private static void endAllVisits() {
		endAllVisits(0);
	}

	private static void endAllVisits(int departDir) {
		for (PlayerData pd : new ArrayList<>(loopSceneManager.getRecordingPlayers())) {
			if (pd.getCurrentVisit() != null) endVisit(pd, departDir);
		}
	}

	private static void startChunk(ServerPlayer player, PlayerData pd) {
		pd.clearPieceStarts();
		pd.addPieceStart(timelineTick);
		pd.setChunkStartTick(timelineTick);

		MocapRecordingConfig recConfig = MocapRecordingConfig.createFromSettings();
		MocapActiveRecording recording = mocapController.startRecording(player, recConfig, true);
		if (recording != null) {
			activeRecordings.put(pd.getName(), recording);
		} else {
			LOOP_LOGGER.error("Failed to start mocap recording for player: {}", pd.getName());
		}
	}

	private static void finishChunk(PlayerData pd) {
		CommandOutput out = CommandOutput.LOGS;
		String playerName = pd.getName();
		Visit visit = pd.getCurrentVisit();
		ArrayList<Long> starts = pd.getPieceStarts();
		int ctxIndex = 0;

		//main record
		MocapActiveRecording primaryRec = activeRecordings.remove(playerName);
		if (primaryRec != null && primaryRec.isValid()) {
			RecordingContext primaryCtx = ((ActiveRecording) primaryRec).ctx;
			primaryCtx.stop(out);
			if (primaryCtx.state == RecordingContext.State.WAITING_FOR_DECISION) {
				savePiece(primaryRec, pd, visit, startFor(starts, ctxIndex), out);
			} else {
				LOOP_LOGGER.warn("Primary recording for '{}' failed to stop properly (state: {}). Skipping save.",
						playerName, primaryCtx.state);
			}
		} else {
			LOOP_LOGGER.warn("No primary mocap recording found for player: {}", playerName);
		}
		ctxIndex++;

		//splits
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
				MocapActiveRecording splitRec = ActiveRecording.get(ctx);
				savePiece(splitRec, pd, visit, startFor(starts, ctxIndex), out);
			} else {
				LOOP_LOGGER.warn("Split context for {} in unexpected state: {}, skipping", playerName, ctx.state);
			}
			ctxIndex++;
		}
	}

	private static long startFor(List<Long> starts, int index) {
		if (starts.isEmpty()) return timelineTick;
		return starts.get(Math.min(index, starts.size() - 1));
	}

	private static void stripBlockActions(MocapActiveRecording rec) {
		try {
			if (!(rec instanceof ActiveRecording active) || active.ctx == null) return;
			java.util.function.Predicate<Object> isWorldAction = a -> a instanceof PlaceBlock
					|| a instanceof BreakBlock || a instanceof RightClickBlock;
			active.ctx.data.actions.removeIf(isWorldAction);
			active.ctx.data.blockActions.removeIf(isWorldAction);
		} catch (Exception e) {
			LOOP_LOGGER.error("Failed to strip block actions from a recording", e);
		}
	}

	private static void savePiece(MocapActiveRecording rec, PlayerData pd, Visit visit, long startTick, CommandOutput out) {
		if (visit == null) {
			rec.discard(out);
			return;
		}
		stripBlockActions(rec);
		String name = String.format("%s_v%d_p%d", pd.getName().toLowerCase(), visit.id, pd.nextPieceNumber());
		MocapRecordingFile file = rec.save(out, name);
		if (file != null) {
			visit.pieces.add(new Visit.Piece(name, startTick));
		} else {
			LOOP_LOGGER.error("Failed to save recording '{}' for player: {}", name, pd.getName());
		}
	}

	//past selves replay
	private static void rebuildPending() {
		pending.clear();
		pendingSounds.clear();
		loopSceneManager.forEachRecordingPlayer(pd -> {
			for (Visit visit : pd.getVisits()) {
				if (visit.pieces.isEmpty()) continue;

				if (visit.arrivalDir != 0 && visit.startDim != null && visit.startTick >= timelineTick) {
					pendingSounds.add(new PendingSound(visit.startTick, visit.startDim, visit.sx, visit.sy, visit.sz, visit.arrivalDir, pd.getName(), visit.id));
				}
				if (visit.departDir != 0 && visit.endDim != null && visit.endTick > timelineTick) {
					pendingSounds.add(new PendingSound(visit.endTick, visit.endDim, visit.ex, visit.ey, visit.ez, visit.departDir, pd.getName(), visit.id));
				}

				for (int i = 0; i < visit.pieces.size(); i++) {
					Visit.Piece piece = visit.pieces.get(i);
					long end = (i + 1 < visit.pieces.size()) ? visit.pieces.get(i + 1).startTick : visit.endTick;
					if (piece.startTick >= timelineTick) {
						pending.add(new PendingPiece(piece.startTick, piece.recording, pd.getName(), 0, visit.id, i, visit.startTick));
					} else if (end > timelineTick) {
						pending.add(new PendingPiece(timelineTick, piece.recording, pd.getName(), (int) (timelineTick - piece.startTick), visit.id, i, visit.startTick));
					}
				}
			}
		});
		needsRebuild = false;
		LOOP_LOGGER.info("Ghost queue rebuilt at tick {}: {} piece(s) pending", timelineTick, pending.size());
	}

	private static void flushPending() {
		while (!pending.isEmpty() && pending.peek().startTick() <= timelineTick + ghostLookaheadTicks) {
			PendingPiece piece = pending.poll();
			startGhost(piece, (int) Math.max(0, piece.startTick() - timelineTick));
		}
	}

	private static void pollDeaths() {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isGhost(player)) continue;
			PlayerData pd = loopSceneManager.getRecordingPlayer(player.getGameProfile().getName());
			if (pd == null || !pd.getActive()) continue;
			Visit visit = pd.getCurrentVisit();
			if (visit == null) continue;

			boolean dead = player.isDeadOrDying();
			if (dead && !visit.wasDead) {
				visit.deathTicks.add(timelineTick);
				if (visit.deathTick < 0) visit.deathTick = timelineTick;
			}
			visit.wasDead = dead;
		}
	}

	private static void pollGhostKills() {
		if (ghostRoots.isEmpty()) return;

		for (Ghost ghost : new ArrayList<>(ghostRoots)) {
			GhostFx fx = ghost.fx();
			if (!fx.isDead()) {
				fx.killHandled = false;
				continue;
			}
			if (fx.killHandled) continue;
			fx.killHandled = true;

			if (!chainDeath || timelineTick <= fx.graceUntil) continue;
			if (isRecordedDeath(ghost.playerName(), ghost.visitId())) continue;

			LOOP_LOGGER.info("Clone of {} (visit {}) was killed during playback!!! Collapsing the chain", ghost.playerName(), ghost.visitId());
			collapseChain(ghost.playerName(), ghost.visitId());
		}
	}

	private static boolean isRecordedDeath(String playerName, int visitId) {
		PlayerData pd = loopSceneManager.getRecordingPlayer(playerName);
		if (pd == null) return true;
		for (Visit v : pd.getVisits()) {
			if (v.id != visitId) continue;
			for (long d : v.deathTicks) {
				if (Math.abs(timelineTick - d) <= 15) return true;
			}
			return v.deathTick >= 0 && Math.abs(timelineTick - v.deathTick) <= 15;
		}
		return true;
	}

	private static void collapseChain(String playerName, int fromVisitId) {
		PlayerData pd = loopSceneManager.getRecordingPlayer(playerName);
		if (pd == null) return;

		Set<Integer> ids = new HashSet<>();
		List<Visit> doomedVisits = new ArrayList<>();
		for (Visit v : pd.getVisits()) {
			if (v.id >= fromVisitId) {
				doomedVisits.add(v);
				ids.add(v.id);
			}
		}
		Visit live = pd.getCurrentVisit();
		boolean killLive = live != null && live.id >= fromVisitId;
		if (killLive) {
			live.doomed = true;
			ids.add(live.id);
		}
		if (ids.isEmpty()) return;

		ghostRoots.removeIf(g -> {
			if (!g.playerName().equals(playerName) || g.visitId() < fromVisitId) return false;
			try {
				if (!g.root().isFinished()) g.root().stop();
			} catch (Exception e) {
				LOOP_LOGGER.error("Failed to stop ghost of a collapsed chain", e);
			}
			return true;
		});
		VoicechatCompat.stopPlaybackFrom(playerName, fromVisitId);
		pending.removeIf(pp -> pp.playerName().equals(playerName) && pp.visitId() >= fromVisitId);
		pendingSounds.removeIf(ps -> ps.playerName().equals(playerName) && ps.visitId() >= fromVisitId);

		for (Visit v : doomedVisits) {
			for (Visit.Piece piece : v.pieces) deleteRecording(piece.recording);
			VoicechatCompat.deleteVisitAudio(pd, v.id);
		}
		pd.getVisits().removeIf(v -> v.id >= fromVisitId);

		BlockJournal.eraseVisits(ids);
		MobJournal.eraseVisits(ids);
		ChatTimeline.eraseVisits(ids);
		ChatTimeline.resend(timelineTick);

		loopSceneManager.saveRecordingPlayers();
		config.save();

		if (killLive) {
			ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
			if (player != null && player.isAlive()) player.kill();
		}
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("Time paradox: the timeline of " + playerName + " collapsed from visit #" + fromVisitId), false);
	}

	private static void deleteRecording(String name) {
		try {
			MocapRecordingFile file = MocapRecordingFile.get(CommandOutput.LOGS, name);
			if (file != null && file.exists()) file.remove(CommandOutput.LOGS);
		} catch (Exception e) {
			LOOP_LOGGER.error("Failed to delete recording {}", name, e);
		}
	}

	private static void flushSounds() {
		while (!pendingSounds.isEmpty() && pendingSounds.peek().tick() <= timelineTick) {
			playTimeSound(pendingSounds.poll());
		}
	}

	private static void playTimeSound(PendingSound s) {
		try {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(s.dim())));
			if (level == null) return;
			if (s.dir() > 0) {
				level.playSound(null, s.x(), s.y(), s.z(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.6f, 1.7f);
				level.playSound(null, s.x(), s.y(), s.z(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.4f, 1.5f);
			} else {
				level.playSound(null, s.x(), s.y(), s.z(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.6f, 0.6f);
				level.playSound(null, s.x(), s.y(), s.z(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.4f, 0.5f);
			}
		} catch (Exception e) {
			LOOP_LOGGER.error("Failed to play time sound", e);
		}
	}

	private static void startGhost(PendingPiece piece, int delayTicks) {
		PlayerData pd = loopSceneManager.getRecordingPlayer(piece.playerName());
		if (pd == null) return;

		MocapRecordingFile file = MocapRecordingFile.get(CommandOutput.LOGS, piece.recording());
		if (file == null || !file.exists()) {
			LOOP_LOGGER.warn("Recording '{}' not found - skipping ghost", piece.recording());
			return;
		}

		Skin skin = pd.getSkin();
		MocapPlayerSkin mocapSkin = switch (skin.skinType) {
			case MINESKIN -> MocapPlayerSkin.fromMineSkin(skin.value);
			case FILE -> MocapPlayerSkin.fromFile(skin.value);
			default -> MocapPlayerSkin.fromPlayer(skin.value);
		};
		MocapModifiers modifiers = MocapModifiers.playerName(pd.getNickname()).withPlayerSkin(mocapSkin);
		MocapTimeModifiers timeMods = modifiers.getTimeModifiers();
		if (delayTicks > 0) {
			timeMods = timeMods.withStartDelay(MocapTime.fromTicks(delayTicks));
		}
		modifiers = modifiers.withTimeModifiers(timeMods);

		try {
			MocapPlaybackRoot root = file.startPlayback(getMocapCommandInfo(), modifiers);
			if (root != null) {
				net.minecraft.world.entity.Entity ghostEntity = GhostFx.findNewestGhost(pd.getNickname());
				GhostFx fx = new GhostFx(ghostEntity);
				if (piece.skipTicks() > 0) fx.graceUntil = timelineTick + 10;
				ghostRoots.add(new Ghost(root, piece.playerName(), piece.visitId(), fx));
				if (trackVoice && voiceChatLoaded) {
					VoicechatCompat.onGhostStart(pd, piece.visitId(), piece.segment(), piece.visitStartTick(), ghostEntity);
				}
				if (piece.skipTicks() > 0 && root instanceof PlaybackRoot playbackRoot) {
					for (int i = 0; i < piece.skipTicks() && !root.isFinished(); i++) {
						playbackRoot.tick();
					}
				}
			}
		} catch (Exception e) {
			LOOP_LOGGER.error("Failed to start ghost '{}': {}", piece.recording(), e.getMessage(), e);
		}
	}

	private static void stopGhosts() {
		for (Ghost ghost : ghostRoots) {
			try {
				if (!ghost.root().isFinished()) ghost.root().stop();
			} catch (Exception e) {
				LOOP_LOGGER.error("Failed to stop ghost playback", e);
			}
		}
		ghostRoots.clear();
		VoicechatCompat.stopPlayback(null);
	}

	private static boolean isGhost(ServerPlayer player) {
		return player.getClass().getName().contains("FakePlayer");
	}

	private static void forEachOnlineRecorded(BiConsumer<ServerPlayer, PlayerData> action) {
		for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (isGhost(player)) continue;
			PlayerData pd = loopSceneManager.getRecordingPlayer(player.getName().getString());
			if (pd == null || !pd.getActive()) continue;
			action.accept(player, pd);
		}
	}

	public static int countOnlineRecorded() {
		int count = 0;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isGhost(player)) continue;
			PlayerData pd = loopSceneManager.getRecordingPlayer(player.getGameProfile().getName());
			if (pd != null && pd.getActive()) count++;
		}
		return count;
	}

	public static int countPendingGhosts() {
		return pending.size();
	}

	public static void sendSync(ServerPlayer player) {
		TimelineNet.sendToPlayer(player, new TimelineSyncPayload(timelineTick, isLooping, displayTimeInTicks, showLoopInfo));
	}

	public static void broadcastSync() {
		if (server == null) return;
		TimelineSyncPayload payload = new TimelineSyncPayload(timelineTick, isLooping, displayTimeInTicks, showLoopInfo);
		for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (isGhost(player)) continue;
			TimelineNet.sendToPlayer(player, payload);
		}
	}

	public static void modifyPlayerAttributes(String targetPlayerName, String newPlayerNickname, Skin newSkin) {
		PlayerData targetPlayer = loopSceneManager.getRecordingPlayer(targetPlayerName);
		if (targetPlayer == null) {
			LOOP_LOGGER.error("Cannot modify attributes: player '{}' not found in recording players", targetPlayerName);
			return;
		}

		targetPlayer.setNickname(newPlayerNickname);
		targetPlayer.setSkin(newSkin);
		LOOP_LOGGER.info("Modified attributes for player '{}' -> '{}' with skin '{}'", targetPlayerName, newPlayerNickname, newSkin.value);
	}

	/** Updates the entities to be tracked for recording and playback settings. */
	public static void updateEntitiesToTrack(boolean items) {
		if (mocapController == null) return;
		String entitiesToTrack = "@vehicles" + (items ? ";@items" : "");
		int distance = items ? entityTrackingDistance : 1;
		mocapController.setSetting("track_entities", entitiesToTrack);
		mocapController.setSetting("play_entities", entitiesToTrack);
		mocapController.setSetting("entity_tracking_distance", String.valueOf(distance));
	}

	/** Deletes all mocap recording files created by this mod (new {@code <name>_v*} and legacy {@code <name>_loop*}). */
	public static void removeAllRecordings() {
		Path recordingsDir = worldFolder.resolve("mocap_files").resolve("recordings");
		if (!Files.isDirectory(recordingsDir)) return;

		Set<String> prefixes = new HashSet<>();
		loopSceneManager.forEachRecordingPlayer(pd -> {
			prefixes.add(pd.getName().toLowerCase() + "_v");
			prefixes.add(pd.getName().toLowerCase() + "_loop");
		});

		try (var stream = Files.newDirectoryStream(recordingsDir, "*.mcmocap_rec")) {
			for (Path file : stream) {
				String fileName = file.getFileName().toString();
				String stem = fileName.substring(0, fileName.length() - ".mcmocap_rec".length());
				for (String prefix : prefixes) {
					if (stem.startsWith(prefix)) {
						try {
							Files.delete(file);
						} catch (IOException e) {
							LOOP_LOGGER.error("Failed to delete recording file: {}", file);
						}
						break;
					}
				}
			}
		} catch (IOException e) {
			LOOP_LOGGER.error("Failed to scan recordings directory for cleanup", e);
		}
	}

	/** Converts time in ticks to HH:MM:SS */
	public static String convertTicksToTime(int ticks) {
		int time = ticks / 20;
		return String.format("%02d:%02d:%02d", time / 3600, (time % 3600) / 60, time % 60);
	}
}
