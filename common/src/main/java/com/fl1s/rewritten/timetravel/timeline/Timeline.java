package com.fl1s.rewritten.timetravel.timeline;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.api.TimelineEventBus;
import com.fl1s.rewritten.timetravel.clone.CloneManager;
import com.fl1s.rewritten.timetravel.clone.HeadTracking;
import com.fl1s.rewritten.timetravel.clone.TravelFx;
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
import com.fl1s.rewritten.timetravel.time.TimeHead;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import com.fl1s.rewritten.timetravel.util.TimeFormat;
import com.fl1s.rewritten.timetravel.vehicle.VehicleSpawnJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleTrailJournal;
import net.minecraft.network.chat.Component;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        HeadTracking.markDirty();
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
        TravelFx.clear();
        HeadTracking.markDirty();
        TimeTravel.config.timelineTick = TimeTravel.sharedTick;
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

        TimeTravel.sharedTick = 0;
        TimeTravel.config.timelineTick = 0;
        TimeTravel.presentTick = 0;
        TimeTravel.config.presentTick = 0;
        s.heads.resetAll();
        TimeTravel.config.nextVisitId = 1;
        TimeTravel.timelineSceneManager.forEachRecordingPlayer(pd -> pd.setLastJumpServerTick(-1));
        s.lastJumpOrigin = -1;
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
    }

    private static void relocate(TimelineSession s, PlayerData pd, long from, long target, int dir) {
        ServerPlayer player = TimeTravel.server.getPlayerList().getPlayerByName(pd.getName());
        if (pd.getCurrentVisit() != null) s.visits.endVisit(pd, dir);
        pd.setLastJumpOrigin(from);
        long fromOffset = pd.getTimeOffset();
        long toOffset = Math.max(0, TimeTravel.presentTick - target);
        s.heads.move(pd, toOffset);
        if (player != null) {
            TravelFx.onTravel(player, fromOffset, toOffset, dir);
            s.visits.beginVisit(player, pd, dir);
            TimeTravel.sendSync(player);
        }
    }

    private String jumpHead(long target, String initiatorName, List<ServerPlayer> bring) {
        TimelineSession s = TimeTravel.session();
        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(initiatorName);
        if (pd == null) return "Only recorded players can travel while individualTime is on";

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

        int dir = target > from ? 1 : -1;
        relocate(s, pd, from, target, dir);

        int brought = 0;
        for (ServerPlayer passenger : bring) {
            String name = passenger.getName().getString();
            if (name.equalsIgnoreCase(pd.getName())) continue;
            PlayerData ppd = TimeTravel.timelineSceneManager.getRecordingPlayer(name);
            if (ppd == null || !ppd.getActive()) continue;
            relocate(s, ppd, s.heads.positionOf(ppd), target, dir);
            passenger.displayClientMessage(Component.literal(initiatorName + " brought you to "
                    + TimeFormat.format(target, TimeTravel.displayTimeInTicks)), true);
            brought++;
        }

        TimeTravel.timelineSceneManager.saveRecordingPlayers();
        TimeTravel.config.save();

        TimeTravel.TIMELINE_LOGGER.info("Individual time jump by {}: {} -> {} (offset {}, brought {})",
                initiatorName, from, target, pd.getTimeOffset(), brought);
        return null;
    }

    public String jumpTo(long target, String initiatorName) {
        return jumpTo(target, initiatorName, List.of());
    }

    public String jumpTo(long target, String initiatorName, List<ServerPlayer> bring) {
        if (!TimeTravel.isLooping) return "Timeline is not running (use /timetravel start)";
        if (!MocapAdapter.isReady()) return "Mocap is not ready yet";

        target = Math.max(0, target);
        if (TimeTravel.individualTime) return jumpHead(target, initiatorName, bring);
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
        BlockJournal.beforeJump();
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
        if (target < from) EffectJournal.onJumpBack(target);
        TimeTravel.broadcastStopDragonSound();

        TimeTravel.forEachOnlineRecorded((p, pd) -> s.visits.beginVisit(p, pd, dir));
        clones.rebuildAll();
        clones.flushAll();

        TimeTravel.timelineSceneManager.saveRecordingPlayers();
        TimeTravel.config.save();
        TimeTravel.broadcastSync();
        TimelineEventBus.fireAfterJump(from, target);

        if (TimeTravel.showTimelineMessages){
            String msg = initiatorName + " jumped " + (target > from ? "forward" : "back") + " to "
                    + TimeFormat.format(target, TimeTravel.displayTimeInTicks) + " ("
                    + TimeFormat.delta(target - from, TimeTravel.displayTimeInTicks) + ")";
            TimeTravel.server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        }
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
        TravelFx.tick();
        HeadTracking.refreshIfDirty();

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
        s.visits.rotateTrailing();

        Set<Long> liveHeads = new HashSet<>();
        for (TimeHead head : s.heads.occupied()) liveHeads.add(head.offset());
        clones.retainHeads(liveHeads);

        if (clones.consumeNeedsRebuild()) clones.rebuildAll();
        clones.flushAll();

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


        if (TimeTravel.sharedTick % 20 == 0) TimeTravel.broadcastSync();
        if (TimeTravel.sharedTick % 1200 == 0) TimeTravel.config.save();
        if (TimeTravel.sharedTick % 1200 == 0) MobBinding.prune();
        if (TimeTravel.sharedTick % 6000 == 0) {
            TimeTravel.JOURNALS.saveIfDirtyAll();
            MobBinding.saveIfDirty();
        }
    }
}
