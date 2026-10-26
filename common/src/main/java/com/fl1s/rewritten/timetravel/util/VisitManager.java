package com.fl1s.rewritten.timetravel.util;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.TimelineSession;
import com.fl1s.rewritten.timetravel.clone.CloneTracks;
import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.data.Visit;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.time.TimeHead;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
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

    public static final int MIN_CHUNK_TICKS = 40;

    private static long positionOf(PlayerData pd) {
        TimeHeads heads = TimeTravel.heads();
        return heads == null ? TimeTravel.sharedTick : heads.positionOf(pd);
    }

    public void beginVisit(ServerPlayer player, PlayerData pd) {
        beginVisit(player, pd, 0);
    }

    public void beginVisit(ServerPlayer player, PlayerData pd, int arrivalDir) {
        Visit visit = new Visit(TimeTravel.config.nextVisitId++, positionOf(pd));
        visit.arrivalDir = arrivalDir;
        visit.startDim = player.level().dimension().location().toString();
        visit.sx = player.getX();
        visit.sy = player.getY();
        visit.sz = player.getZ();
        pd.setCurrentVisit(visit);
        pd.resetSegments();
        pd.resetPieceCounter();
        pd.setLastDimensionKey(player.level().dimension());
        CloneTracks.poll(player, pd);
        startChunk(player, pd);
    }

    public void rotateTrailing() {
        if (!TimeTravel.individualTime) return;
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;

        long edge = trailingEdge(heads);
        if (edge < 0) return;

        TimeTravel.forEachOnlineRecorded((player, pd) -> {
            if (pd.getCurrentVisit() == null) return;
            long start = pd.getChunkStartTick();
            if (start > edge) return;
            if (positionOf(pd) - start < MIN_CHUNK_TICKS) return;
            rotate(player, pd);
        });
    }

    private static long trailingEdge(TimeHeads heads) {
        long edge = -1;
        for (TimeHead head : heads.occupied()) {
            if (head.offset() <= 0 || head.isEmpty()) continue;
            if (head.position() > edge) edge = head.position();
        }
        return edge;
    }

    public void rotate(ServerPlayer player, PlayerData pd) {
        Visit visit = pd.getCurrentVisit();
        if (visit == null) return;

        int from = visit.pieces.size();
        finishChunk(pd);
        pd.setSegment(visit.pieces.size());
        startChunk(player, pd);

        if (visit.pieces.size() > from) {
            TimeTravel.session().clones.publishPieces(pd, visit, from);
            TimeTravel.TIMELINE_LOGGER.debug("Published history of '{}' up to tick {} ({} new piece(s))",
                    pd.getName(), pd.getChunkStartTick(), visit.pieces.size() - from);
        }
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

        int from = visit.pieces.size();
        finishChunk(pd);
        visit.endTick = positionOf(pd);
        pd.setCurrentVisit(null);

        if (visit.doomed) {
            for (Visit.Piece piece : visit.pieces) MocapAdapter.deleteRecording(piece.recording);
            VoicechatCompat.deleteVisitAudio(pd, visit.id);
            TimeTravel.TIMELINE_LOGGER.info("Visit {} of '{}' was doomed by a death chain - erased",
                    visit.id, pd.getName());
        } else if (!visit.pieces.isEmpty() && visit.endTick > visit.startTick) {
            pd.getVisits().add(visit);
            TimeTravel.session().clones.publishPieces(pd, visit, from);
            TimeTravel.session().clones.publishDeparture(pd, visit);
            if (TimeTravel.trackVoice) VoicechatCompat.saveVisitAudio(pd, visit.id);
            TimeTravel.TIMELINE_LOGGER.info("Visit {} of '{}' saved: ticks {}..{}, {} piece(s)",
                    visit.id, pd.getName(), visit.startTick, visit.endTick, visit.pieces.size());
        } else {
            TimeTravel.TIMELINE_LOGGER.info("Visit {} of '{}' was empty - dropped", visit.id, pd.getName());
        }
    }

    public void endAllVisits() {
        endAllVisits(0);
    }

    public void endAllVisits(int departDir) {
        for (PlayerData pd : new ArrayList<>(TimeTravel.timelineSceneManager.getRecordingPlayers())) {
            if (pd.getCurrentVisit() != null) endVisit(pd, departDir);
        }
    }

    private void startChunk(ServerPlayer player, PlayerData pd) {
        pd.clearPieceStarts();
        pd.addPieceStart(positionOf(pd));
        pd.setChunkStartTick(positionOf(pd));
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
            long startTick = startFor(starts, i, positionOf(pd));
            String name = String.format("%s_v%d_p%d", pd.getName().toLowerCase(), visit.id, pd.nextPieceNumber());
            if (MocapAdapter.save(handle, name)) {
                visit.pieces.add(new Visit.Piece(name, startTick));
            } else {
                TimeTravel.TIMELINE_LOGGER.error("Failed to save recording '{}' for player: {}", name, pd.getName());
            }
            i++;
        }
    }

    private static long startFor(List<Long> starts, int index, long fallback) {
        if (starts.isEmpty()) return fallback;
        return starts.get(Math.min(index, starts.size() - 1));
    }
}
