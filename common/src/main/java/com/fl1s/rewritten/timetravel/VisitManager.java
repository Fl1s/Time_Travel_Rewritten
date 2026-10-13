package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle of recorded visits: opening a visit on jump / join, closing it on
 * leave / jump, and saving the resulting mocap pieces.
 * <p>
 * Owned by {@link TimelineSession}. All methods must be called on the server
 * thread while {@code TimeTravel.isLooping} is true.
 */
public final class VisitManager {

    public void beginVisit(ServerPlayer player, PlayerData pd) {
        beginVisit(player, pd, 0);
    }

    public void beginVisit(ServerPlayer player, PlayerData pd, int arrivalDir) {
        Visit visit = new Visit(TimeTravel.config.nextVisitId++, TimeTravel.timelineTick);
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

    public void endVisit(PlayerData pd) {
        endVisit(pd, 0);
    }

    public void endVisit(PlayerData pd, int departDir) {
        Visit visit = pd.getCurrentVisit();
        if (visit == null) return;

        ServerPlayer online = TimeTravel.server.getPlayerList().getPlayerByName(pd.getName());
        if (online != null) {
            visit.endDim = online.level().dimension().location().toString();
            visit.ex = online.getX();
            visit.ey = online.getY();
            visit.ez = online.getZ();
        }
        visit.departDir = (online != null) ? departDir : 0;

        finishChunk(pd);
        visit.endTick = TimeTravel.timelineTick;
        pd.setCurrentVisit(null);

        if (visit.doomed) {
            for (Visit.Piece piece : visit.pieces) MocapAdapter.deleteRecording(piece.recording);
            VoicechatCompat.deleteVisitAudio(pd, visit.id);
            TimeTravel.LOOP_LOGGER.info("Visit {} of '{}' was doomed by a death chain - erased",
                    visit.id, pd.getName());
        } else if (!visit.pieces.isEmpty() && visit.endTick > visit.startTick) {
            pd.getVisits().add(visit);
            if (TimeTravel.trackVoice) VoicechatCompat.saveVisitAudio(pd, visit.id);
            TimeTravel.LOOP_LOGGER.info("Visit {} of '{}' saved: ticks {}..{}, {} piece(s)",
                    visit.id, pd.getName(), visit.startTick, visit.endTick, visit.pieces.size());
        } else {
            TimeTravel.LOOP_LOGGER.info("Visit {} of '{}' was empty - dropped", visit.id, pd.getName());
        }
    }

    public void endAllVisits() {
        endAllVisits(0);
    }

    public void endAllVisits(int departDir) {
        for (PlayerData pd : new ArrayList<>(TimeTravel.loopSceneManager.getRecordingPlayers())) {
            if (pd.getCurrentVisit() != null) endVisit(pd, departDir);
        }
    }

    private void startChunk(ServerPlayer player, PlayerData pd) {
        pd.clearPieceStarts();
        pd.addPieceStart(TimeTravel.timelineTick);
        pd.setChunkStartTick(TimeTravel.timelineTick);
        MocapAdapter.startRecording(player, pd.getName());
    }

    private void finishChunk(PlayerData pd) {
        Visit visit = pd.getCurrentVisit();
        if (visit == null) {
            MocapAdapter.discardAllRecordings(pd.getName());
            return;
        }
        ArrayList<Long> starts = pd.getPieceStarts();
        int i = 0;
        for (var handle : MocapAdapter.stopAllRecordings(pd.getName())) {
            long startTick = startFor(starts, i);
            String name = String.format("%s_v%d_p%d", pd.getName().toLowerCase(), visit.id, pd.nextPieceNumber());
            if (MocapAdapter.save(handle, name)) {
                visit.pieces.add(new Visit.Piece(name, startTick));
            } else {
                TimeTravel.LOOP_LOGGER.error("Failed to save recording '{}' for player: {}", name, pd.getName());
            }
            i++;
        }
    }

    private static long startFor(List<Long> starts, int index) {
        if (starts.isEmpty()) return TimeTravel.timelineTick;
        return starts.get(Math.min(index, starts.size() - 1));
    }
}
