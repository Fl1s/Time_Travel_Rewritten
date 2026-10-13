package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.compat.VoicechatInteractionCompat;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.net.TimelineSyncPayload;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

public class TimeTravel {
	public static final String MOD_ID = "timetravel_rewritten";
	public static final Logger LOOP_LOGGER = LoggerFactory.getLogger("TimeTravel");
	public static MinecraftServer server;
	public static ServerLevel serverLevel;

	public static boolean isLooping;
	public static long timelineTick;
	public static long dayTimeOrigin;
	public static boolean syncDayTime;
	public static boolean jumpRequiresOp;
	public static long maxJumpTicks;
	public static int jumpCooldownTicks = 20;

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

	public static int ghostLookaheadTicks = 0;

	private static TimelineSession session;

	public static void resetSession() {
		session = new TimelineSession();
		MocapAdapter.reset();
	}

	static TimelineSession session() {
		TimelineSession s = session;
		if (s == null) {
			throw new IllegalStateException("TimelineSession not initialized (onServerStart not called?)");
		}
		return s;
	}

	public static long lastJumpOrigin() {
		TimelineSession s = session;
		return s == null ? -1 : s.lastJumpOrigin;
	}

	public static void queueTask(Runnable task) {
		TimelineSession s = session;
		if (s != null) s.delayedTasks.add(task);
	}

	public static void init() {
		LOOP_LOGGER.info("Initializing TimeTravel mod (Common)");
	}

	//timeline contol
	public static void startLoop() {
		if (isLooping) {
			LOOP_LOGGER.info("Timeline already running");
			return;
		}
		if (!MocapAdapter.isReady()) {
			LOOP_LOGGER.error("Mocap controller is not ready yet - cannot start timeline");
			return;
		}

		isLooping = true;
		config.isLooping = true;
		dayTimeOrigin = serverLevel.getDayTime() - timelineTick;
		config.dayTimeOrigin = dayTimeOrigin;

		GhostManager ghosts = session().ghosts;
		ghosts.setIdle(false);
		ghosts.markNeedsRebuild();

		VisitManager visits = session().visits;
		forEachOnlineRecorded(visits::beginVisit);
		BlockJournal.rebuildUpcoming();
		MobJournal.rebuildUpcoming();
		EntityJournal.rebuildUpcoming();
		EffectJournal.rebuildUpcoming();
		ProcessJournal.rebuildUpcoming();
		ChatTimeline.rebuildUpcoming();
		AdvancementTimeline.rebuildUpcoming();
		ProcessJournal.snapshotOrigins(server.getPlayerList().getPlayers());
		config.save();
		broadcastSync();
		LOOP_LOGGER.info("Timeline started at tick {}", timelineTick);
	}

	public static void stopLoop() {
		if (!isLooping) return;
		LOOP_LOGGER.info("Stopping timeline at tick {}", timelineTick);

		BlockJournal.flushWatches();
		session().visits.endAllVisits();
		session().ghosts.stopAll();
		session().ghosts.clearPending();
		BlockJournal.save();
		MobJournal.save();
		ChatTimeline.save();
		AdvancementTimeline.save();
		EntityJournal.save();
		EffectJournal.save();
		MobBinding.save();

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
		session().ghosts.stopAll();
		session().ghosts.clearPending();

		removeAllRecordings();
		BlockJournal.clear();
		MobJournal.clear();
		ProcessJournal.clear();
		ChatTimeline.clear();
		AdvancementTimeline.clear();
		EntityJournal.clear();
		EffectJournal.clear();
		MobBinding.clear();
		VoicechatCompat.deleteAllAudio();
		loopSceneManager.forEachRecordingPlayer(pd -> pd.getVisits().clear());

		timelineTick = 0;
		config.timelineTick = 0;
		config.nextVisitId = 1;
		loopSceneManager.forEachRecordingPlayer(pd -> pd.setLastJumpServerTick(-1));
		session().lastJumpOrigin = -1;
		session().ghosts.markNeedsRebuild();
		config.save();
		broadcastSync();
	}

	public static String jumpTo(long target, String initiatorName) {
		if (!isLooping) return "Timeline is not running (use /timetravel start)";
		if (!MocapAdapter.isReady()) return "Mocap is not ready yet";

		target = Math.max(0, target);
		if (maxJumpTicks > 0) {
			long min = Math.max(0, timelineTick - maxJumpTicks);
			long max = timelineTick + maxJumpTicks;
			target = Math.min(max, Math.max(min, target));
		}
		if (target == timelineTick) return "Already at " + TimeFormat.clock(target);

		PlayerData initiatorPd = loopSceneManager.getRecordingPlayer(initiatorName);
		long nowServerTick = server.getTickCount();
		if (initiatorPd != null) {
			if (jumpCooldownTicks > 0 && initiatorPd.getLastJumpServerTick() >= 0 && nowServerTick - initiatorPd.getLastJumpServerTick() < jumpCooldownTicks) {
				return "Time jump cooldown - wait a moment";
			}
			initiatorPd.setLastJumpServerTick(nowServerTick);
		}

		long from = timelineTick;
		session().lastJumpOrigin = from;
		int dir = target > from ? 1 : -1;
		LOOP_LOGGER.info("Time jump by {}: {} -> {}", initiatorName, from, target);

		BlockJournal.flushWatches();
		EntityJournal.fastForward(from, target);
		ProcessJournal.beforeJump();
		session().visits.endAllVisits(dir);

		GhostManager ghosts = session().ghosts;
		ghosts.stopAll();

		timelineTick = target;
		config.timelineTick = target;
		if (syncDayTime) {
			serverLevel.setDayTime(dayTimeOrigin + target);
		}
		ghosts.setIdle(false);

		BlockJournal.onJump(from, target);
		MobJournal.onJump(from, target);
		EntityJournal.onJump();
		EffectJournal.rebuildUpcoming();
		ProcessJournal.afterJump(from, target);
		ChatTimeline.onJump(target);
		AdvancementTimeline.onJump();

		VisitManager visits = session().visits;
		forEachOnlineRecorded((p, pd) -> visits.beginVisit(p, pd, dir));
		ghosts.rebuild();
		ghosts.flushPending();
		ghosts.flushSounds();

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
		TimelineSession s = session();
		Runnable task;
		while ((task = s.delayedTasks.poll()) != null) {
			try {
				task.run();
			} catch (Exception e) {
				LOOP_LOGGER.error("Error executing delayed task", e);
			}
		}

		BlockJournal.flushWatches();

		if (!isLooping || !MocapAdapter.isReady()) return;

		GhostManager ghosts = s.ghosts;
		if (countOnlineRecorded() == 0) {
			if (!ghosts.isIdle()) {
				ghosts.setIdle(true);
				ghosts.stopAll();
				ghosts.markNeedsRebuild();
			}
			return;
		}
		ghosts.setIdle(false);

		if (ghosts.consumeNeedsRebuild()) ghosts.rebuild();
		ghosts.flushPending();
		ghosts.flushSounds();

		timelineTick++;
		config.timelineTick = timelineTick;
		BlockJournal.tick();
		EntityJournal.tick();
		MobJournal.tick();
		ProcessJournal.tick();
		ChatTimeline.tick();
		AdvancementTimeline.tick();
		EffectJournal.tick();
		if (syncDayTime) {
			serverLevel.setDayTime(dayTimeOrigin + timelineTick);
		}
		ghosts.tickActive();
		if (trackVoice) VoicechatCompat.onTick();
		s.deathChain.tick();

		if (timelineTick % 20 == 0) broadcastSync();
		if (timelineTick % 1200 == 0) config.save();
		if (timelineTick % 1200 == 0) MobBinding.prune();
		if (timelineTick % 6000 == 0) {
			BlockJournal.saveIfDirty();
			MobJournal.saveIfDirty();
			ChatTimeline.saveIfDirty();
			AdvancementTimeline.saveIfDirty();
			EntityJournal.saveIfDirty();
			EffectJournal.saveIfDirty();
			MobBinding.saveIfDirty();
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
		if (isLooping && MocapAdapter.isReady() && pd.getCurrentVisit() == null) {
			session().visits.beginVisit(player, pd);
			GhostManager ghosts = session().ghosts;
			if (ghosts.isIdle()) {
				ghosts.setIdle(false);
				ghosts.markNeedsRebuild();
			}
		}
		sendSync(player);
	}

	public static void onPlayerLeft(PlayerData pd) {
		if (isLooping && pd != null && pd.getCurrentVisit() != null) {
			session().visits.endVisit(pd);
			loopSceneManager.saveRecordingPlayers();
			config.save();
		}
	}

	public static void onServerStopping() {
		if (isLooping) {
			session().visits.endAllVisits();
			session().ghosts.stopAll();
			loopSceneManager.saveRecordingPlayers();
			config.isLooping = true;
			config.timelineTick = timelineTick;
		}
		BlockJournal.flushWatches();
		BlockJournal.save();
		MobJournal.save();
		ChatTimeline.save();
		AdvancementTimeline.save();
		EntityJournal.save();
		EffectJournal.save();
		MobBinding.save();
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
	private static void startChunk(ServerPlayer player, PlayerData pd) {
		pd.clearPieceStarts();
		pd.addPieceStart(timelineTick);
		pd.setChunkStartTick(timelineTick);
		MocapAdapter.startRecording(player, pd.getName());
	}

	public static int visitIdOf(Entity e) {
		TimelineSession s = session;
		return s == null ? -1 : s.ghosts.visitIdOf(e);
	}

	public static GhostFx fxOf(Entity e) {
		TimelineSession s = session;
		return s == null ? null : s.ghosts.fxOf(e);
	}

	public static Entity entityOfVisit(int visitId) {
		TimelineSession s = session;
		return s == null ? null : s.ghosts.entityOfVisit(visitId);
	}

	public static boolean visitPending(int visitId) {
		TimelineSession s = session;
		return s != null && s.ghosts.visitPending(visitId);
	}

	public static boolean visitKnown(int visitId) {
		TimelineSession s = session;
		return s != null && s.ghosts.visitKnown(visitId);
	}

	public static int countPendingGhosts() {
		TimelineSession s = session;
		return s == null ? 0 : s.ghosts.countPending();
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

	private static boolean isGhost(ServerPlayer player) {
		return Ghosts.is(player);
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

	public static void updateEntitiesToTrack(boolean items) {
		MocapAdapter.updateEntitiesToTrack(items, entityTrackingDistance);
	}

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
}
