package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.compat.VoicechatInteractionCompat;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import net.minecraft.network.chat.Component;

/**
 * Core timeline: shared clock, jump orchestration, and per-server-tick work.
 */
public final class Timeline {

    public void start() {
        if (TimeTravel.isLooping) {
            TimeTravel.LOOP_LOGGER.info("Timeline already running");
            return;
        }
        if (!MocapAdapter.isReady()) {
            TimeTravel.LOOP_LOGGER.error("Mocap controller is not ready yet - cannot start timeline");
            return;
        }

        TimeTravel.isLooping = true;
        TimeTravel.config.isLooping = true;
        TimeTravel.dayTimeOrigin = TimeTravel.serverLevel.getDayTime() - TimeTravel.timelineTick;
        TimeTravel.config.dayTimeOrigin = TimeTravel.dayTimeOrigin;

        MobSpawnJournal.snapshotInitial();
        MobTrailJournal.seedInitial();
        BlockEntityJournal.snapshotInitial();
        VehicleSpawnJournal.snapshotInitial();
        VehicleTrailJournal.seedInitial();
        RedstoneJournal.rebuildUpcoming();

        TimelineSession s = TimeTravel.session();
        GhostManager ghosts = s.ghosts;
        ghosts.setIdle(false);
        ghosts.markNeedsRebuild();

        TimeTravel.forEachOnlineRecorded(s.visits::beginVisit);
        TimeTravel.JOURNALS.rebuildUpcomingAll();
        ProcessJournal.snapshotOrigins(TimeTravel.server.getPlayerList().getPlayers());
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
        TimeTravel.LOOP_LOGGER.info("Timeline started at tick {}", TimeTravel.timelineTick);
    }

    public void stop() {
        if (!TimeTravel.isLooping) return;
        TimeTravel.LOOP_LOGGER.info("Stopping timeline at tick {}", TimeTravel.timelineTick);

        TimelineSession s = TimeTravel.session();
        BlockJournal.flushWatches();
        s.visits.endAllVisits();
        s.ghosts.stopAll();
        s.ghosts.clearPending();
        TimeTravel.JOURNALS.saveAll();
        MobBinding.save();

        TimeTravel.isLooping = false;
        TimeTravel.config.isLooping = false;
        TimeTravel.config.timelineTick = TimeTravel.timelineTick;
        TimeTravel.loopSceneManager.saveRecordingPlayers();
        VoicechatInteractionCompat.clearCooldowns();
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
    }

    public void reset() {
        stop();
        TimelineSession s = TimeTravel.session();
        s.ghosts.stopAll();
        s.ghosts.clearPending();

        TimeTravel.removeAllRecordings();
        TimeTravel.JOURNALS.clearAll();
        ReplayState.reset();
        RedstoneJournal.clear();
        MobBinding.clear();
        VoicechatCompat.deleteAllAudio();
        TimeTravel.loopSceneManager.forEachRecordingPlayer(pd -> pd.getVisits().clear());

        TimeTravel.timelineTick = 0;
        TimeTravel.config.timelineTick = 0;
        TimeTravel.config.nextVisitId = 1;
        TimeTravel.loopSceneManager.forEachRecordingPlayer(pd -> pd.setLastJumpServerTick(-1));
        s.lastJumpOrigin = -1;
        s.ghosts.markNeedsRebuild();
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
        PlayerData initiatorPd = TimeTravel.loopSceneManager.getRecordingPlayer(initiatorName);
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
        TimeTravel.LOOP_LOGGER.info("Time jump by {}: {} -> {}", initiatorName, from, target);

        BlockJournal.flushWatches();
        EntityJournal.fastForward(from, target);
        ProcessJournal.beforeJump();
        s.visits.endAllVisits(dir);

        GhostManager ghosts = s.ghosts;
        ghosts.stopAll();

        TimeTravel.timelineTick = target;
        TimeTravel.config.timelineTick = target;
        if (TimeTravel.syncDayTime) {
            TimeTravel.serverLevel.setDayTime(TimeTravel.dayTimeOrigin + target);
        }
        ghosts.setIdle(false);

        TimeTravel.JOURNALS.onJumpAll(from, target);

        TimeTravel.forEachOnlineRecorded((p, pd) -> s.visits.beginVisit(p, pd, dir));
        ghosts.rebuild();
        ghosts.flushPending();
        ghosts.flushSounds();

        TimeTravel.loopSceneManager.saveRecordingPlayers();
        TimeTravel.config.save();
        TimeTravel.broadcastSync();

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
                TimeTravel.LOOP_LOGGER.error("Error executing delayed task", e);
            }
        }

        BlockJournal.flushWatches();

        if (!TimeTravel.isLooping || !MocapAdapter.isReady()) return;

        GhostManager ghosts = s.ghosts;
        if (TimeTravel.countOnlineRecorded() == 0) {
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

        TimeTravel.timelineTick++;
        TimeTravel.config.timelineTick = TimeTravel.timelineTick;
        GhostTracks.pollAll();
        ghosts.replayEvents();
        TimeTravel.JOURNALS.tickAll();
        if (TimeTravel.syncDayTime) {
            TimeTravel.serverLevel.setDayTime(TimeTravel.dayTimeOrigin + TimeTravel.timelineTick);
        }
        ghosts.tickActive();
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
