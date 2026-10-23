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
import com.fl1s.rewritten.timetravel.util.TimeFormat;
import com.fl1s.rewritten.timetravel.vehicle.VehicleSpawnJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleTrailJournal;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameRules;

/**
 * Core timeline: shared clock, jump orchestration, and per-server-tick work.
 */
public final class Timeline {

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
        TimeTravel.dayTimeOrigin = TimeTravel.serverLevel.getDayTime() - TimeTravel.timelineTick;
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
        TimeTravel.TIMELINE_LOGGER.info("Timeline started at tick {}", TimeTravel.timelineTick);
        TimelineEventBus.fireStart();
    }

    public void stop() {
        if (!TimeTravel.isLooping) return;
        TimeTravel.TIMELINE_LOGGER.info("Stopping timeline at tick {}", TimeTravel.timelineTick);

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
        TimeTravel.config.timelineTick = TimeTravel.timelineTick;
        TimeTravel.timelineSceneManager.saveRecordingPlayers();
        VoicechatInteractionCompat.clearCooldowns();
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

        TimeTravel.timelineTick = 0;
        TimeTravel.config.timelineTick = 0;
        TimeTravel.config.nextVisitId = 1;
        TimeTravel.timelineSceneManager.forEachRecordingPlayer(pd -> pd.setLastJumpServerTick(-1));
        s.lastJumpOrigin = -1;
        s.clones.markNeedsRebuild();
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
    }

    public String jumpTo(long target, String initiatorName) {
        if (!TimeTravel.isLooping) return "Timeline is not running (use /timetravel start)";
        if (!MocapAdapter.isReady()) return "Mocap is not ready yet";

        target = Math.max(0, target);
        if (TimeTravel.maxJumpTicks > 0) {
            long min = Math.max(0, TimeTravel.timelineTick - TimeTravel.maxJumpTicks);
            long max = TimeTravel.timelineTick + TimeTravel.maxJumpTicks;
            target = Math.min(max, Math.max(min, target));
        }
        if (target == TimeTravel.timelineTick) return "Already at " + TimeFormat.clock(target);

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

        long from = TimeTravel.timelineTick;
        s.lastJumpOrigin = from;
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

        TimeTravel.timelineTick = target;
        TimeTravel.config.timelineTick = target;
        if (TimeTravel.syncDayTime && TimeTravel.serverLevel.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            TimeTravel.serverLevel.setDayTime(TimeTravel.dayTimeOrigin + target);
        }
        clones.setIdle(false);

        TimeTravel.JOURNALS.onJumpAll(from, target);
        if (target < from) EffectJournal.onJumpBack(target);
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

        TimeTravel.timelineTick++;
        TimeTravel.config.timelineTick = TimeTravel.timelineTick;
        CloneTracks.pollAll();
        clones.replayEvents();
        TimeTravel.JOURNALS.tickAll();

        clones.tickActive();
        CloneTracks.applyHealthAll();
        if (TimeTravel.trackVoice) VoicechatCompat.onTick();
        s.deathChain.tick();

        if (TimeTravel.timelineTick % 20 == 0) TimeTravel.broadcastSync();
        if (TimeTravel.timelineTick % 1200 == 0) TimeTravel.config.save();
        if (TimeTravel.timelineTick % 1200 == 0) MobBinding.prune();
        if (TimeTravel.timelineTick % 6000 == 0) {
            TimeTravel.JOURNALS.saveIfDirtyAll();
            MobBinding.saveIfDirty();
        }
    }
}
