package com.fl1s.rewritten.timetravel;

import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.mocap.GhostPlayback;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import com.fl1s.rewritten.timetravel.util.Levels;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Runtime manager for past-self ("ghost") playback: queued pieces, active
 * playback roots, skip budget, and queued time-jump sounds.
 * <p>
 * Owned by {@link TimelineSession}; every instance is discarded when the
 * world unloads, so no ghost state leaks between worlds.
 */
public final class GhostManager {

    public static final long PENDING_VISIT_WINDOW = 1200;
    public static final int GHOST_SYNC_SKIP_LIMIT = 8000;
    public static final int GHOST_SKIP_PER_TICK = 40;

    public record PendingPiece(long startTick, String recording, String playerName, int skipTicks,
                               int visitId, int segment, long visitStartTick) {}

    public record PendingSound(long tick, String dim, double x, double y, double z, int dir,
                               String playerName, int visitId) {}

    static final class Ghost {
        final GhostPlayback root;
        final String playerName;
        final int visitId;
        final GhostFx fx;
        int pendingSkip;

        Ghost(GhostPlayback root, String playerName, int visitId, GhostFx fx, int pendingSkip) {
            this.root = root;
            this.playerName = playerName;
            this.visitId = visitId;
            this.fx = fx;
            this.pendingSkip = pendingSkip;
        }
    }

    private final List<Ghost> roots = new ArrayList<>();
    private final PriorityQueue<PendingPiece> pending = new PriorityQueue<>(
            Comparator.comparingLong(PendingPiece::startTick));
    private final PriorityQueue<PendingSound> pendingSounds = new PriorityQueue<>(
            Comparator.comparingLong(PendingSound::tick));
    private boolean needsRebuild = true;
    private boolean idle = false;

    public void markNeedsRebuild() { needsRebuild = true; }

    public boolean consumeNeedsRebuild() {
        boolean r = needsRebuild;
        needsRebuild = false;
        return r;
    }

    public boolean isIdle() { return idle; }
    public void setIdle(boolean v) { idle = v; }

    public int countPending() { return pending.size(); }

    List<Ghost> roots() { return roots; }

    public void clearPending() {
        pending.clear();
        pendingSounds.clear();
    }

    public void rebuild() {
        pending.clear();
        pendingSounds.clear();

        LoopSceneManager scene = TimeTravel.loopSceneManager;
        final long now = TimeTravel.timelineTick;
        if (scene == null) {
            needsRebuild = false;
            return;
        }

        scene.forEachRecordingPlayer(pd -> {
            for (Visit visit : pd.getVisits()) {
                if (visit.pieces.isEmpty()) continue;

                if (visit.arrivalDir != 0 && visit.startDim != null && visit.startTick >= now) {
                    pendingSounds.add(new PendingSound(visit.startTick, visit.startDim,
                            visit.sx, visit.sy, visit.sz, visit.arrivalDir, pd.getName(), visit.id));
                }
                if (visit.departDir != 0 && visit.endDim != null && visit.endTick > now) {
                    pendingSounds.add(new PendingSound(visit.endTick, visit.endDim,
                            visit.ex, visit.ey, visit.ez, visit.departDir, pd.getName(), visit.id));
                }

                for (int i = 0; i < visit.pieces.size(); i++) {
                    Visit.Piece piece = visit.pieces.get(i);
                    long end = (i + 1 < visit.pieces.size())
                            ? visit.pieces.get(i + 1).startTick
                            : visit.endTick;
                    if (piece.startTick >= now) {
                        pending.add(new PendingPiece(piece.startTick, piece.recording, pd.getName(),
                                0, visit.id, i, visit.startTick));
                    } else if (end > now) {
                        pending.add(new PendingPiece(now, piece.recording, pd.getName(),
                                (int) (now - piece.startTick), visit.id, i, visit.startTick));
                    }
                }
            }
        });
        needsRebuild = false;
        TimeTravel.LOOP_LOGGER.info("Ghost queue rebuilt at tick {}: {} piece(s) pending",
                now, pending.size());
    }

    public void flushPending() {
        final long now = TimeTravel.timelineTick;
        while (!pending.isEmpty()
                && pending.peek().startTick() <= now + TimeTravel.ghostLookaheadTicks) {
            PendingPiece piece = pending.poll();
            start(piece, (int) Math.max(0, piece.startTick() - now));
        }
    }

    public void flushSounds() {
        final long now = TimeTravel.timelineTick;
        while (!pendingSounds.isEmpty() && pendingSounds.peek().tick() <= now) {
            playTimeSound(pendingSounds.poll());
        }
    }

    public void tickActive() {
        for (Ghost ghost : roots) {
            if (ghost.pendingSkip > 0) {
                int step = Math.min(ghost.pendingSkip, GHOST_SKIP_PER_TICK);
                ghost.root.tick(step);
                ghost.pendingSkip -= step;
                continue;
            }
            if (!ghost.root.isFinished()) ghost.fx.tick();
        }
        if (TimeTravel.timelineTick % 100 == 0) {
            roots.removeIf(g -> g.root.isFinished());
        }
    }

    private void start(PendingPiece piece, int delayTicks) {
        LoopSceneManager scene = TimeTravel.loopSceneManager;
        PlayerData pd = scene == null ? null : scene.getRecordingPlayer(piece.playerName());
        if (pd == null) return;

        GhostPlayback playback = MocapAdapter.startPlayback(
                piece.recording(), pd.getNickname(), pd.getSkin(), delayTicks);
        if (playback == null) return;

        int pendingSkip = piece.skipTicks();
        if (pendingSkip > 0) {
            int syncSkip = Math.min(pendingSkip, GHOST_SYNC_SKIP_LIMIT);
            playback.tick(syncSkip);
            pendingSkip -= syncSkip;
        }

        Entity ghostEntity = GhostFx.findNewestGhost(pd.getNickname());
        GhostFx fx = new GhostFx(ghostEntity);
        if (piece.skipTicks() > 0) fx.graceUntil = TimeTravel.timelineTick + 10;
        roots.add(new Ghost(playback, piece.playerName(), piece.visitId(), fx, pendingSkip));
        if (TimeTravel.trackVoice && TimeTravel.voiceChatLoaded) {
            VoicechatCompat.onGhostStart(pd, piece.visitId(), piece.segment(),
                    piece.visitStartTick(), ghostEntity);
        }
    }

    public void stopAll() {
        for (Ghost ghost : roots) {
            try {
                if (!ghost.root.isFinished()) ghost.root.stop();
            } catch (Exception e) {
                TimeTravel.LOOP_LOGGER.error("Failed to stop ghost playback", e);
            }
        }
        roots.clear();
        VoicechatCompat.stopPlayback(null);
    }

    public void stopFromVisit(String playerName, int fromVisitId) {
        roots.removeIf(g -> {
            if (!g.playerName.equals(playerName) || g.visitId < fromVisitId) return false;
            try {
                if (!g.root.isFinished()) g.root.stop();
            } catch (Exception e) {
                TimeTravel.LOOP_LOGGER.error("Failed to stop ghost of a collapsed chain", e);
            }
            return true;
        });
    }

    public void removePendingFrom(String playerName, int fromVisitId) {
        pending.removeIf(pp -> pp.playerName().equals(playerName) && pp.visitId() >= fromVisitId);
        pendingSounds.removeIf(ps -> ps.playerName().equals(playerName) && ps.visitId() >= fromVisitId);
    }

    private void playTimeSound(PendingSound s) {
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(s.dim()));
            if (level == null) return;
            if (s.dir() > 0) {
                level.playSound(null, s.x(), s.y(), s.z(), SoundEvents.ENDERMAN_TELEPORT,
                        SoundSource.PLAYERS, 1.6f, 1.7f);
                level.playSound(null, s.x(), s.y(), s.z(), SoundEvents.AMETHYST_BLOCK_CHIME,
                        SoundSource.PLAYERS, 1.4f, 1.5f);
            } else {
                level.playSound(null, s.x(), s.y(), s.z(), SoundEvents.ENDERMAN_TELEPORT,
                        SoundSource.PLAYERS, 1.6f, 0.6f);
                level.playSound(null, s.x(), s.y(), s.z(), SoundEvents.AMETHYST_BLOCK_RESONATE,
                        SoundSource.PLAYERS, 1.4f, 0.5f);
            }
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("Failed to play time sound", e);
        }
    }

    public int visitIdOf(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return -1;
        if (Ghosts.is(player)) {
            for (Ghost ghost : roots) {
                if (ghost.fx.entity() == entity) return ghost.visitId;
            }
            return -1;
        }
        LoopSceneManager scene = TimeTravel.loopSceneManager;
        if (scene == null) return -1;
        PlayerData pd = scene.getRecordingPlayer(player.getGameProfile().getName());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return -1;
        return pd.getCurrentVisit().id;
    }

    public GhostFx fxOf(Entity e) {
        for (Ghost g : roots) if (g.fx.entity() == e) return g.fx;
        return null;
    }

    public Entity entityOfVisit(int visitId) {
        for (Ghost ghost : roots) {
            if (ghost.visitId != visitId || ghost.root.isFinished()) continue;
            Entity entity = ghost.fx.entity();
            if (entity != null && !entity.isRemoved()) return entity;
        }
        LoopSceneManager scene = TimeTravel.loopSceneManager;
        if (scene == null || TimeTravel.server == null) return null;
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) {
            if (Ghosts.is(player)) continue;
            PlayerData pd = scene.getRecordingPlayer(player.getGameProfile().getName());
            if (pd != null && pd.getCurrentVisit() != null && pd.getCurrentVisit().id == visitId) {
                return player;
            }
        }
        return null;
    }

    public boolean visitPending(int visitId) {
        LoopSceneManager scene = TimeTravel.loopSceneManager;
        if (scene == null) return false;
        for (PlayerData pd : scene.getRecordingPlayers()) {
            for (Visit v : pd.getVisits()) {
                if (v.id == visitId) {
                    return v.startTick > TimeTravel.timelineTick
                            && v.startTick - TimeTravel.timelineTick <= PENDING_VISIT_WINDOW;
                }
            }
        }
        return false;
    }

    public boolean visitKnown(int visitId) {
        LoopSceneManager scene = TimeTravel.loopSceneManager;
        if (scene == null) return false;
        for (PlayerData pd : scene.getRecordingPlayers()) {
            if (pd.getCurrentVisit() != null && pd.getCurrentVisit().id == visitId) return true;
            for (Visit v : pd.getVisits()) if (v.id == visitId) return true;
        }
        return false;
    }
}
