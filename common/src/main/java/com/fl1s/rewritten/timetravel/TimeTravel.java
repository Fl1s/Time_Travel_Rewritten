package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.clone.CloneFx;
import com.fl1s.rewritten.timetravel.clone.CloneManager;
import com.fl1s.rewritten.timetravel.compat.VoicechatInteractionCompat;
import com.fl1s.rewritten.timetravel.compat.anim.AnimRelay;
import com.fl1s.rewritten.timetravel.compat.anim.AttackRelay;
import com.fl1s.rewritten.timetravel.compat.anim.BetterCombatIdleRelay;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.TimelineSession;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.JournalRegistry;
import com.fl1s.rewritten.timetravel.mob.MobBinding;
import com.fl1s.rewritten.timetravel.mob.MobTrailJournal;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.net.TimelineSyncPayload;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Skin;
import com.fl1s.rewritten.timetravel.util.TimelineSceneManager;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
	public static final Logger TIMELINE_LOGGER = LoggerFactory.getLogger("TimeTravel");
	public static final boolean DEBUG = Boolean.getBoolean("timetravel.debug");
	public static MinecraftServer server;
	public static ServerLevel serverLevel;
	public static boolean isDarkHud;

	public static boolean isLooping;
	public static long timelineTick;
	public static long dayTimeOrigin;
	public static boolean syncDayTime;
	public static boolean jumpRequiresOp;
	public static long maxJumpTicks;
	public static int jumpCooldownTicks = 20;

	public static boolean chainDeath;

	public static boolean showTimelineInfo;
	public static boolean knightOfTimeMode;
	public static boolean flowerManMode;
	public static boolean displayTimeInTicks;
	public static boolean trackItems;
	public static boolean oldSfx;
	public static int entityTrackingDistance;
	public static boolean trackChat;
	public static boolean hurtPastSelves;

	public static float hudScale = 1.0f;
	public static boolean hudAtBottom = false;
	public static boolean compactHud = false;
	public static int maxHistorySize;

	public static boolean voiceChatLoaded;
	public static boolean voiceInteractionLoaded;
	public static boolean voiceInteractionEnabled;
	public static int voiceInteractionThresholdDb;
	public static int voiceInteractionCooldownTicks;
	public static float voiceAudioDistance;
	public static boolean showPlayerVoiceIcon;
	public static boolean trackVoice;

	public static TimeTravelConfig config;
	public static TimelineSceneManager timelineSceneManager;
	public static Path worldFolder;

	public static int cloneLookaheadTicks = 0;

	public static final JournalRegistry JOURNALS = new JournalRegistry();

	private static TimelineSession session;

	public static void resetSession() {
		session = new TimelineSession();
		MocapAdapter.reset();
	}

	public static TimelineSession session() {
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
		TIMELINE_LOGGER.info("Initializing TimeTravel mod (Common)");
		AnimRelay.init();
		AttackRelay.init();
	}

	//timeline contol
	public static void startTimeline() {
		session().timeline.start();
	}

	public static void stopTimeline() {
		session().timeline.stop();
	}

	public static void resetTimeline() {
		session().timeline.reset();
	}

	public static String jumpTo(long target, String initiatorName) {
		return session().timeline.jumpTo(target, initiatorName);
	}

	public static void onServerTick() {
		session().timeline.tick();
	}

	public static void handlePlayerDimensionChange(ServerPlayer player) {
		if (!isLooping) return;

		PlayerData pd = timelineSceneManager.getRecordingPlayer(player.getName().getString());
		if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return;

		ResourceKey<Level> currentDimension = player.level().dimension();
		if (currentDimension.equals(pd.getLastDimensionKey())) return;

		pd.addPieceStart(timelineTick);
		pd.nextSegment();
		pd.setLastDimensionKey(currentDimension);

		TIMELINE_LOGGER.info("Player '{}' changed dimension at tick {} (pieces in chunk: {})",
				player.getName().getString(), timelineTick, pd.getPieceStarts().size());
	}

	//player lifecycle
	public static void onPlayerJoined(ServerPlayer player, PlayerData pd) {
		if (isLooping && MocapAdapter.isReady() && pd.getCurrentVisit() == null) {
			TimelineSession s = session();
			s.visits.beginVisit(player, pd);
			CloneManager clones = s.clones;
			if (clones.isIdle()) {
				clones.setIdle(false);
				clones.markNeedsRebuild();
			}
		}
		sendSync(player);
	}

	public static void onPlayerLeft(PlayerData pd) {
		if (isLooping && pd != null && pd.getCurrentVisit() != null) {
			session().visits.endVisit(pd);
			timelineSceneManager.saveRecordingPlayers();
			config.save();
		}
	}

	public static void onServerStopping() {
		if (isLooping) {
			MobTrailJournal.releaseAll();
			session().visits.endAllVisits();
			session().clones.stopAll();
			timelineSceneManager.saveRecordingPlayers();
			config.isLooping = true;
			config.timelineTick = timelineTick;
		}
		BlockJournal.flushWatches();
		JOURNALS.saveAll();
		MobBinding.save();
		if (worldFolder != null && config != null) config.save();
		VoicechatInteractionCompat.clearCooldowns();
	}

	public static void forEachOnlineRecorded(BiConsumer<ServerPlayer, PlayerData> action) {
		for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (isClone(player)) continue;
			PlayerData pd = timelineSceneManager.getRecordingPlayer(player.getName().getString());
			if (pd == null || !pd.getActive()) continue;
			action.accept(player, pd);
		}
	}

	public static int countOnlineRecorded() {
		int count = 0;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isClone(player)) continue;
			PlayerData pd = timelineSceneManager.getRecordingPlayer(player.getGameProfile().getName());
			if (pd != null && pd.getActive()) count++;
		}
		return count;
	}

	static boolean isClone(ServerPlayer player) {
		return Clones.is(player);
	}

	public static boolean isRealPlayer(ServerPlayer player) {
		MinecraftServer s = server;
		if (s == null || player == null || Clones.is(player)) return false;
		return s.getPlayerList().getPlayer(player.getUUID()) == player;
	}

	public static int visitIdOf(Entity e) {
		TimelineSession s = session;
		return s == null ? -1 : s.clones.visitIdOf(e);
	}

	public static CloneFx fxOf(Entity e) {
		TimelineSession s = session;
		return s == null ? null : s.clones.fxOf(e);
	}

	public static Entity entityOfVisit(int visitId) {
		TimelineSession s = session;
		return s == null ? null : s.clones.entityOfVisit(visitId);
	}

	public static boolean visitPending(int visitId) {
		TimelineSession s = session;
		return s != null && s.clones.visitPending(visitId);
	}

	public static boolean visitKnown(int visitId) {
		TimelineSession s = session;
		return s != null && s.clones.visitKnown(visitId);
	}

	public static int countPendingClones() {
		TimelineSession s = session;
		return s == null ? 0 : s.clones.countPending();
	}

	public static void sendSync(ServerPlayer player) {
		TimelineNet.sendToPlayer(player, new TimelineSyncPayload(timelineTick, isLooping, displayTimeInTicks, showTimelineInfo));
	}

	public static void broadcastSync() {
		if (server == null) return;
		TimelineSyncPayload payload = new TimelineSyncPayload(timelineTick, isLooping, displayTimeInTicks, showTimelineInfo);
		for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (isClone(player)) continue;
			TimelineNet.sendToPlayer(player, payload);
		}
	}

	public static void broadcastStopDragonSound() {
		if (server == null) return;
		ClientboundStopSoundPacket packet = new ClientboundStopSoundPacket(SoundEvents.ENDER_DRAGON_DEATH.getLocation(), null);
		for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (isClone(player)) continue;
			player.connection.send(packet);
		}
	}

	public static void modifyPlayerAttributes(String targetPlayerName, String newPlayerNickname, Skin newSkin) {
		PlayerData targetPlayer = timelineSceneManager.getRecordingPlayer(targetPlayerName);
		if (targetPlayer == null) {
			TIMELINE_LOGGER.error("Cannot modify attributes: player '{}' not found in recording players", targetPlayerName);
			return;
		}

		targetPlayer.setNickname(newPlayerNickname);
		targetPlayer.setSkin(newSkin);
		TIMELINE_LOGGER.info("Modified attributes for player '{}' -> '{}' with skin '{}'", targetPlayerName, newPlayerNickname, newSkin.value);
	}

	public static void updateEntitiesToTrack(boolean items) {
		MocapAdapter.updateEntitiesToTrack(items, entityTrackingDistance);
	}

	public static void removeAllRecordings() {
		Path recordingsDir = worldFolder.resolve("mocap_files").resolve("recordings");
		if (!Files.isDirectory(recordingsDir)) return;

		Set<String> prefixes = new HashSet<>();
		timelineSceneManager.forEachRecordingPlayer(pd -> {
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
							TIMELINE_LOGGER.error("Failed to delete recording file: {}", file);
						}
						break;
					}
				}
			}
		} catch (IOException e) {
			TIMELINE_LOGGER.error("Failed to scan recordings directory for cleanup", e);
		}
	}
}
