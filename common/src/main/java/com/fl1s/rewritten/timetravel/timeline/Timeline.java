package com.fl1s.rewritten.timetravel.timeline;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.api.TimelineEventBus;
import com.fl1s.rewritten.timetravel.clone.CloneManager;
import com.fl1s.rewritten.timetravel.clone.CloneTracks;
import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.compat.VoicechatInteractionCompat;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import com.fl1s.rewritten.timetravel.data.TimelineSession;
import com.fl1s.rewritten.timetravel.entity.EntityJournal;
import com.fl1s.rewritten.timetravel.journal.*;
import com.fl1s.rewritten.timetravel.mob.*;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.time.EraLevels;
import com.fl1s.rewritten.timetravel.time.TimeHead;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import com.fl1s.rewritten.timetravel.util.TimeFormat;
import com.fl1s.rewritten.timetravel.vehicle.VehicleSpawnJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleTrailJournal;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

/**
 * Core timeline: shared clock, jump orchestration, and per-server-tick work.
 */
public final class Timeline {

    private static void advancePresent(long tick) {
        if (tick > TimeTravel.presentTick) {
            TimeTravel.presentTick = tick;
            TimeTravel.config.presentTick = tick;
        }
    }

    public void start() {
        if (TimeTravel.isLooping) {
            TimeTravel.TIMELINE_LOGGER.info("Timeline already running");
            return;
        }
        if (!MocapAdapter.isReady()) {
            TimeTravel.TIMELINE_LOGGER.error("Mocap controller is not ready yet - cannot start timeline");
            return;
        }

        TimeTravel.isLooping = true;
        TimeTravel.config.isLooping = true;
        TimeTravel.dayTimeOrigin = TimeTravel.serverLevel.getDayTime() - TimeTravel.sharedTick;
        TimeTravel.config.dayTimeOrigin = TimeTravel.dayTimeOrigin;

        MobSpawnJournal.snapshotInitial();
        MobStateJournal.snapshotInitial();
        MobTrailJournal.seedInitial();
        BlockEntityJournal.snapshotInitial();
        WeatherJournal.snapshotInitial();
        VehicleSpawnJournal.snapshotInitial();
        VehicleTrailJournal.seedInitial();
        RedstoneJournal.rebuildUpcoming();

        TimelineSession s = TimeTravel.session();
        CloneManager clones = s.clones;
        clones.setIdle(false);
        clones.markNeedsRebuild();

        TimeTravel.forEachOnlineRecorded(s.visits::beginVisit);
        TimeTravel.JOURNALS.rebuildUpcomingAll();
        ProcessJournal.snapshotOrigins(TimeTravel.server.getPlayerList().getPlayers());
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
        TimeTravel.TIMELINE_LOGGER.info("Timeline started at tick {}", TimeTravel.sharedTick);
        TimelineEventBus.fireStart();
    }

    public void stop() {
        if (!TimeTravel.isLooping) return;
        TimeTravel.TIMELINE_LOGGER.info("Stopping timeline at tick {}", TimeTravel.sharedTick);

        TimelineSession s = TimeTravel.session();
        BlockJournal.flushWatches();
        MobTrailJournal.releaseAll();
        s.visits.endAllVisits();
        s.clones.stopAll();
        s.clones.clearPending();
        TimeTravel.JOURNALS.saveAll();
        MobBinding.save();

        TimeTravel.isLooping = false;
        TimeTravel.config.isLooping = false;
        TimeTravel.config.timelineTick = TimeTravel.sharedTick;
        TimeTravel.timelineSceneManager.saveRecordingPlayers();
        VoicechatInteractionCompat.clearCooldowns();
        EraLevels.releaseAll();
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
        TimelineEventBus.fireStop();
    }

    public void reset() {
        stop();
        TimelineSession s = TimeTravel.session();
        s.clones.stopAll();
        s.clones.clearPending();

        TimeTravel.removeAllRecordings();
        TimeTravel.JOURNALS.clearAll();
        ReplayState.reset();
        RedstoneJournal.clear();
        MobBinding.clear();
        VoicechatCompat.deleteAllAudio();
        TimeTravel.timelineSceneManager.forEachRecordingPlayer(pd -> pd.getVisits().clear());

        TimeTravel.sharedTick = 0;
        TimeTravel.config.timelineTick = 0;
        TimeTravel.presentTick = 0;
        TimeTravel.config.presentTick = 0;
        s.heads.resetAll();
        TimeTravel.config.nextVisitId = 1;
        TimeTravel.timelineSceneManager.forEachRecordingPlayer(pd -> pd.setLastJumpServerTick(-1));
        s.lastJumpOrigin = -1;
        s.clones.markNeedsRebuild();
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
    }

    private String jumpHead(long target, String initiatorName) {
        TimelineSession s = TimeTravel.session();
        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(initiatorName);
        if (pd == null) return "Only recorded players can travel while internalTime is on";

        long from = s.heads.positionOf(pd);
        target = Math.min(target, TimeTravel.presentTick);
        if (TimeTravel.maxJumpTicks > 0) {
            long min = Math.max(0, from - TimeTravel.maxJumpTicks);
            long max = Math.min(TimeTravel.presentTick, from + TimeTravel.maxJumpTicks);
            target = Math.min(max, Math.max(min, target));
        }
        if (target == from) return "Already at " + TimeFormat.clock(target);

        long nowServerTick = TimeTravel.server.getTickCount();
        if (TimeTravel.jumpCooldownTicks > 0 && pd.getLastJumpServerTick() >= 0
                && nowServerTick - pd.getLastJumpServerTick() < TimeTravel.jumpCooldownTicks) {
            return "Time jump cooldown - wait a moment";
        }
        pd.setLastJumpServerTick(nowServerTick);

        long previousOffset = pd.getTimeOffset();
        pd.setLastJumpOrigin(from);
        s.heads.move(pd, TimeTravel.presentTick - target);

        TimeHead head = s.heads.headOf(pd);
        ServerPlayer player = TimeTravel.server.getPlayerList().getPlayerByName(pd.getName());
        ServerLevel destination = TimeTravel.serverLevel;
        if (!head.isPresent()) {
            destination = EraLevels.getOrCreate(head);
            if (destination == null) {
                s.heads.move(pd, previousOffset);
                pd.setLastJumpServerTick(-1);
                return "Could not open that era - see the server log";
            }
        }
        if (player != null && player.level() != destination) {
            player.teleportTo(destination, player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }

        TimeTravel.timelineSceneManager.saveRecordingPlayers();
        TimeTravel.config.save();
        if (player != null) TimeTravel.sendSync(player);

        TimeTravel.TIMELINE_LOGGER.info("Internal time jump by {}: {} -> {} (offset {})",
                initiatorName, from, target, pd.getTimeOffset());
        return null;
    }

    public String jumpTo(long target, String initiatorName) {
        if (!TimeTravel.isLooping) return "Timeline is not running (use /timetravel start)";
        if (!MocapAdapter.isReady()) return "Mocap is not ready yet";

        target = Math.max(0, target);
        if (TimeTravel.internalTime) return jumpHead(target, initiatorName);
        if (TimeTravel.maxJumpTicks > 0) {
            long min = Math.max(0, TimeTravel.sharedTick - TimeTravel.maxJumpTicks);
            long max = TimeTravel.sharedTick + TimeTravel.maxJumpTicks;
            target = Math.min(max, Math.max(min, target));
        }
        if (target == TimeTravel.sharedTick) return "Already at " + TimeFormat.clock(target);

        TimelineSession s = TimeTravel.session();
        PlayerData initiatorPd = TimeTravel.timelineSceneManager.getRecordingPlayer(initiatorName);
        long nowServerTick = TimeTravel.server.getTickCount();
        if (initiatorPd != null) {
            if (TimeTravel.jumpCooldownTicks > 0 && initiatorPd.getLastJumpServerTick() >= 0
                    && nowServerTick - initiatorPd.getLastJumpServerTick() < TimeTravel.jumpCooldownTicks) {
                return "Time jump cooldown - wait a moment";
            }
            initiatorPd.setLastJumpServerTick(nowServerTick);
        }

        long from = TimeTravel.sharedTick;
        s.lastJumpOrigin = from;
        if (initiatorPd != null) initiatorPd.setLastJumpOrigin(from);
        int dir = target > from ? 1 : -1;
        TimeTravel.TIMELINE_LOGGER.info("Time jump by {}: {} -> {}", initiatorName, from, target);

        TimelineEventBus.fireBeforeJump(from, target);
        BlockJournal.flushWatches();
        EntityJournal.fastForward(from, target);
        MobJournal.fastForward(from, target);
        ProcessJournal.beforeJump();
        s.visits.endAllVisits(dir);

        CloneManager clones = s.clones;
        clones.stopAll();

        advancePresent(target);
        s.heads.moveAll(TimeTravel.presentTick - target);
        TimeTravel.sharedTick = target;
        TimeTravel.config.timelineTick = target;
        if (TimeTravel.syncDayTime && TimeTravel.serverLevel.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            TimeTravel.serverLevel.setDayTime(TimeTravel.dayTimeOrigin + target);
        }
        clones.setIdle(false);

        TimeTravel.JOURNALS.onJumpAll(from, target);
        TimeTravel.broadcastStopDragonSound();

        TimeTravel.forEachOnlineRecorded((p, pd) -> s.visits.beginVisit(p, pd, dir));
        clones.rebuild();
        clones.flushPending();
        clones.flushSounds();

        TimeTravel.timelineSceneManager.saveRecordingPlayers();
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
        TimelineEventBus.fireAfterJump(from, target);

        String msg = initiatorName + " jumped " + (target > from ? "forward" : "back") + " to "
                + TimeFormat.format(target, TimeTravel.displayTimeInTicks) + " ("
                + TimeFormat.delta(target - from, TimeTravel.displayTimeInTicks) + ")";
        TimeTravel.server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        return null;
    }

    public void tick() {
        TimelineSession s = TimeTravel.session();
        Runnable task;
        while ((task = s.delayedTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                TimeTravel.TIMELINE_LOGGER.error("Error executing delayed task", e);
            }
        }

        BlockJournal.flushWatches();

        if (!TimeTravel.isLooping || !MocapAdapter.isReady()) return;

        CloneManager clones = s.clones;
        if (TimeTravel.countOnlineRecorded() == 0) {
            if (!clones.isIdle()) {
                clones.setIdle(true);
                clones.stopAll();
                clones.markNeedsRebuild();
            }
            return;
        }
        clones.setIdle(false);

        if (clones.consumeNeedsRebuild()) clones.rebuild();
        clones.flushPending();
        clones.flushSounds();

        TimeTravel.presentTick++;
        TimeTravel.config.presentTick = TimeTravel.presentTick;
        TimeTravel.sharedTick = s.heads.sharedPosition();
        TimeTravel.config.timelineTick = TimeTravel.sharedTick;
        CloneTracks.pollAll();
        clones.replayEvents();
        TimeTravel.JOURNALS.tickAll();

        clones.tickActive();
        CloneTracks.applyHealthAll();
        if (TimeTravel.trackVoice) VoicechatCompat.onTick();
        s.deathChain.tick();

        EraLevels.sweep();

        if (TimeTravel.sharedTick % 20 == 0) TimeTravel.broadcastSync();
        if (TimeTravel.sharedTick % 1200 == 0) TimeTravel.config.save();
        if (TimeTravel.sharedTick % 1200 == 0) MobBinding.prune();
        if (TimeTravel.sharedTick % 6000 == 0) {
            TimeTravel.JOURNALS.saveIfDirtyAll();
            MobBinding.saveIfDirty();
        }
    }
}
