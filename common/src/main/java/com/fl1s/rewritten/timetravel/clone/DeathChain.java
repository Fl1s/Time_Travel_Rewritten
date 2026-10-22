package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.TimelineSession;
import com.fl1s.rewritten.timetravel.data.Visit;
import com.fl1s.rewritten.timetravel.journal.ItemFlowJournal;
import com.fl1s.rewritten.timetravel.mob.MobBinding;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.timeline.ChatTimeline;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.util.TimelineSceneManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Death tracking and causality-chain collapse.
 * <p>
 * Two responsibilities:
 * <ul>
 *   <li>{@link #tick()} tracks when a live recorded player dies so the death
 *       tick is stored on the current visit.</li>
 *   <li>When a clone (past self) is killed during playback outside the ticks
 *       that were recorded, {@link #collapseChain} wipes every visit at or
 *       after the paradox point, erases the corresponding journal entries,
 *       and kills the live player to close the loop.</li>
 * </ul>
 * Owned by {@link TimelineSession}.
 */
public final class DeathChain {

    public void tick() {
        pollDeaths();
        pollCloneKills();
    }

    private void pollDeaths() {
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        if (scene == null || TimeTravel.server == null) return;
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) {
            if (Clones.is(player)) continue;
            PlayerData pd = scene.getRecordingPlayer(player.getGameProfile().getName());
            if (pd == null || !pd.getActive()) continue;
            Visit visit = pd.getCurrentVisit();
            if (visit == null) continue;

            boolean dead = player.isDeadOrDying();
            if (dead && !visit.wasDead) {
                visit.deathTicks.add(TimeTravel.timelineTick);
                if (visit.deathTick < 0) visit.deathTick = TimeTravel.timelineTick;
            }
            visit.wasDead = dead;
        }
    }

    private void pollCloneKills() {
        TimelineSession s = TimeTravel.session();
        CloneManager clones = s.clones;
        if (clones.roots().isEmpty()) return;

        for (CloneManager.Clone clone : new ArrayList<>(clones.roots())) {
            CloneFx fx = clone.fx;
            if (!fx.isDead()) {
                fx.killHandled = false;
                continue;
            }
            if (fx.killHandled) continue;
            fx.killHandled = true;

            if (!TimeTravel.chainDeath || TimeTravel.timelineTick <= fx.graceUntil) continue;
            if (isRecordedDeath(clone.playerName, clone.visitId)) continue;

            TimeTravel.TIMELINE_LOGGER.info("Clone of {} (visit {}) was killed during playback!!! Collapsing the chain",
                    clone.playerName, clone.visitId);
            collapseChain(clone.playerName, clone.visitId);
        }
    }

    private boolean isRecordedDeath(String playerName, int visitId) {
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        if (scene == null) return true;
        PlayerData pd = scene.getRecordingPlayer(playerName);
        if (pd == null) return true;
        for (Visit v : pd.getVisits()) {
            if (v.id != visitId) continue;
            for (long d : v.deathTicks) {
                if (Math.abs(TimeTravel.timelineTick - d) <= 15) return true;
            }
            return v.deathTick >= 0 && Math.abs(TimeTravel.timelineTick - v.deathTick) <= 15;
        }
        return true;
    }

    private void collapseChain(String playerName, int fromVisitId) {
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        if (scene == null || TimeTravel.server == null) return;
        PlayerData pd = scene.getRecordingPlayer(playerName);
        if (pd == null) return;

        Visit live = pd.getCurrentVisit();
        boolean killLive = live != null && live.id >= fromVisitId;
        if (killLive) {
            live.doomed = true;
        }

        Set<Integer> ids = new HashSet<>();
        List<Visit> doomedVisits = new ArrayList<>();
        for (Visit v : pd.getVisits()) {
            if (v.id >= fromVisitId) {
                doomedVisits.add(v);
                ids.add(v.id);
            }
        }
        if (live != null && live.id >= fromVisitId) {
            ids.add(live.id);
        }

        CloneManager clones = TimeTravel.session().clones;
        clones.stopFromVisit(playerName, fromVisitId);
        VoicechatCompat.stopPlaybackFrom(playerName, fromVisitId);
        clones.removePendingFrom(playerName, fromVisitId);

        if (live != null) {
            TimeTravel.session().visits.endVisit(pd);
        }

        for (Visit v : doomedVisits) {
            for (Visit.Piece piece : v.pieces) MocapAdapter.deleteRecording(piece.recording);
            VoicechatCompat.deleteVisitAudio(pd, v.id);
        }
        pd.getVisits().removeIf(v -> v.id >= fromVisitId);

        TimeTravel.JOURNALS.eraseVisitsAll(ids);
        MobBinding.eraseVisits(ids);
        ChatTimeline.resend(TimeTravel.timelineTick);

        scene.saveRecordingPlayers();
        TimeTravel.config.save();

        if (killLive) {
            ServerPlayer player = TimeTravel.server.getPlayerList().getPlayerByName(playerName);
            if (player != null && player.isAlive()) {
                ItemFlowJournal.setSuppressDrop(true);
                try {
                    player.getInventory().clearContent();
                    player.kill();
                } finally {
                    ItemFlowJournal.setSuppressDrop(false);
                }
            }
        }
        TimeTravel.server.getPlayerList().broadcastSystemMessage(
                Component.literal("Time paradox: the timeline of " + playerName
                        + " collapsed from visit #" + fromVisitId), false);
    }
}
