package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.util.Ghosts;
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
 *   <li>When a ghost (past self) is killed during playback outside the ticks
 *       that were recorded, {@link #collapseChain} wipes every visit at or
 *       after the paradox point, erases the corresponding journal entries,
 *       and kills the live player to close the loop.</li>
 * </ul>
 * Owned by {@link TimelineSession}.
 */
public final class DeathChain {

    public void tick() {
        pollDeaths();
        pollGhostKills();
    }

    private void pollDeaths() {
        LoopSceneManager scene = TimeTravel.loopSceneManager;
        if (scene == null || TimeTravel.server == null) return;
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) {
            if (Ghosts.is(player)) continue;
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

    private void pollGhostKills() {
        TimelineSession s = TimeTravel.session();
        GhostManager ghosts = s.ghosts;
        if (ghosts.roots().isEmpty()) return;

        for (GhostManager.Ghost ghost : new ArrayList<>(ghosts.roots())) {
            GhostFx fx = ghost.fx;
            if (!fx.isDead()) {
                fx.killHandled = false;
                continue;
            }
            if (fx.killHandled) continue;
            fx.killHandled = true;

            if (!TimeTravel.chainDeath || TimeTravel.timelineTick <= fx.graceUntil) continue;
            if (isRecordedDeath(ghost.playerName, ghost.visitId)) continue;

            TimeTravel.LOOP_LOGGER.info("Clone of {} (visit {}) was killed during playback!!! Collapsing the chain",
                    ghost.playerName, ghost.visitId);
            collapseChain(ghost.playerName, ghost.visitId);
        }
    }

    private boolean isRecordedDeath(String playerName, int visitId) {
        LoopSceneManager scene = TimeTravel.loopSceneManager;
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
        LoopSceneManager scene = TimeTravel.loopSceneManager;
        if (scene == null || TimeTravel.server == null) return;
        PlayerData pd = scene.getRecordingPlayer(playerName);
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

        GhostManager ghosts = TimeTravel.session().ghosts;
        ghosts.stopFromVisit(playerName, fromVisitId);
        VoicechatCompat.stopPlaybackFrom(playerName, fromVisitId);
        ghosts.removePendingFrom(playerName, fromVisitId);

        for (Visit v : doomedVisits) {
            for (Visit.Piece piece : v.pieces) MocapAdapter.deleteRecording(piece.recording);
            VoicechatCompat.deleteVisitAudio(pd, v.id);
        }
        pd.getVisits().removeIf(v -> v.id >= fromVisitId);

        BlockJournal.eraseVisits(ids);
        MobJournal.eraseVisits(ids);
        ChatTimeline.eraseVisits(ids);
        AdvancementTimeline.eraseVisits(ids);
        EntityJournal.eraseVisits(ids);
        EffectJournal.eraseVisits(ids);
        MobBinding.eraseVisits(ids);
        WorldEntityJournal.eraseVisits(ids);
        ChatTimeline.resend(TimeTravel.timelineTick);

        scene.saveRecordingPlayers();
        TimeTravel.config.save();

        if (killLive) {
            ServerPlayer player = TimeTravel.server.getPlayerList().getPlayerByName(playerName);
            if (player != null && player.isAlive()) player.kill();
        }
        TimeTravel.server.getPlayerList().broadcastSystemMessage(
                Component.literal("Time paradox: the timeline of " + playerName
                        + " collapsed from visit #" + fromVisitId), false);
    }
}
