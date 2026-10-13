package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.compat.VoicechatInteractionCompat;
import com.fl1s.rewritten.timetravel.journal.EnderDragonFightJournal;
import com.fl1s.rewritten.timetravel.journal.WeatherJournal;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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

        TimelineSession s = TimeTravel.session();
        GhostManager ghosts = s.ghosts;
        ghosts.setIdle(false);
        ghosts.markNeedsRebuild();

        TimeTravel.forEachOnlineRecorded(s.visits::beginVisit);
        BlockJournal.rebuildUpcoming();
        MobJournal.rebuildUpcoming();
        EntityJournal.rebuildUpcoming();
        EffectJournal.rebuildUpcoming();
        ProcessJournal.rebuildUpcoming();
        ChatTimeline.rebuildUpcoming();
        AdvancementTimeline.rebuildUpcoming();
        EnderDragonFightJournal.get().rebuildUpcoming();
        WorldEntityJournal.rebuildUpcoming();
        WeatherJournal.get().rebuildUpcoming();
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
        BlockJournal.save();
        MobJournal.save();
        ChatTimeline.save();
        AdvancementTimeline.save();
        EntityJournal.save();
        EffectJournal.save();
        MobBinding.save();
        EnderDragonFightJournal.get().save();
        WorldEntityJournal.save();
        WeatherJournal.get().save();

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
        BlockJournal.clear();
        MobJournal.clear();
        ProcessJournal.clear();
        ChatTimeline.clear();
        AdvancementTimeline.clear();
        EntityJournal.clear();
        EffectJournal.clear();
        MobBinding.clear();
        EnderDragonFightJournal.get().clear();
        WorldEntityJournal.clear();
        WeatherJournal.get().clear();
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
        int visitId = initiatorPd != null && initiatorPd.getCurrentVisit() != null
                ? initiatorPd.getCurrentVisit().id : -1;
        TimeTravel.LOOP_LOGGER.info("Time jump by {}: {} -> {}", initiatorName, from, target);

        EnderDragonFightJournal.get().snapshotAll(visitId);
        WeatherJournal.get().snapshotAll(visitId);

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

        EnderDragonFightJournal.get().restore(target);
        WeatherJournal.get().restore(target);

        BlockJournal.onJump(from, target);
        MobJournal.onJump(from, target);
        WorldEntityJournal.onJump(from, target);
        EntityJournal.onJump();
        EffectJournal.rebuildUpcoming();
        ProcessJournal.afterJump(from, target);
        ChatTimeline.onJump(target);
        AdvancementTimeline.onJump();
        EnderDragonFightJournal.get().rebuildUpcoming();
        WorldEntityJournal.rebuildUpcoming();
        WeatherJournal.get().rebuildUpcoming();

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
        BlockJournal.tick();
        EntityJournal.tick();
        MobJournal.tick();
        ProcessJournal.tick();
        ChatTimeline.tick();
        AdvancementTimeline.tick();
        EffectJournal.tick();
        EnderDragonFightJournal.get().tick();
        WeatherJournal.get().tick();
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
            BlockJournal.saveIfDirty();
            MobJournal.saveIfDirty();
            ChatTimeline.saveIfDirty();
            AdvancementTimeline.saveIfDirty();
            EntityJournal.saveIfDirty();
            EffectJournal.saveIfDirty();
            MobBinding.saveIfDirty();
            EnderDragonFightJournal.get().saveIfDirty();
            WorldEntityJournal.saveIfDirty();
            WeatherJournal.get().saveIfDirty();
        }
    }
}
