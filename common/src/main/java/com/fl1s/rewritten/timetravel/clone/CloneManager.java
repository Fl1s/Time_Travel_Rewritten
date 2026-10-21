package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.data.*;
import com.fl1s.rewritten.timetravel.mocap.ClonePlayback;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.net.CloneAppearPayload;
import com.fl1s.rewritten.timetravel.net.CloneVanishPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.util.TTSounds;
import com.fl1s.rewritten.timetravel.util.TimelineSceneManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Runtime manager for past-self ("clone") playback: queued pieces, active
 * playback roots, skip budget, and queued time-jump sounds.
 * <p>
 * Owned by {@link com.fl1s.rewritten.timetravel.data.TimelineSession}; every instance is discarded when the
 * world unloads, so no clone state leaks between worlds.
 */
public final class CloneManager {

    public static final long PENDING_VISIT_WINDOW = 1200;
    public static final int CLONE_SYNC_SKIP_LIMIT = 8000;
    public static final int CLONE_SKIP_PER_TICK = 40;
    public static final int VANISH_LEAD_TICKS = 32;
    public static final int APPEAR_SOUND_LEAD_TICKS = 0;
    public static final int VANISH_SOUND_LEAD_TICKS = 25;
    public static final int OG_VANISH_SOUND_LEAD_TICKS = 5;
    private static final float APPEAR_SOUND_PITCH = 1.8f;

    public record PendingPiece(long startTick, String recording, String playerName, int skipTicks,
                               int visitId, int segment, long visitStartTick) {}

    public record PendingSound(long tick, String dim, double x, double y, double z, int dir,
                               String playerName, int visitId, boolean appear) {}

    static final class Clone {
        final ClonePlayback root;
        final String playerName;
        final int visitId;
        final CloneFx fx;
        final Visit visit;
        int pendingSkip;
        boolean shielded;
        boolean wasInvulnerable;
        long endTick = -1;
        boolean vanishSent;

        Clone(ClonePlayback root, String playerName, int visitId, CloneFx fx, int pendingSkip, Visit visit) {
            this.root = root;
            this.playerName = playerName;
            this.visitId = visitId;
            this.fx = fx;
            this.pendingSkip = pendingSkip;
            this.visit = visit;
        }
    }

    private final List<Clone> roots = new ArrayList<>();
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

    List<Clone> roots() { return roots; }

    public void clearPending() {
        pending.clear();
        pendingSounds.clear();
    }

    public void rebuild() {
        pending.clear();
        pendingSounds.clear();

        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        final long now = TimeTravel.timelineTick;
        if (scene == null) {
            needsRebuild = false;
            return;
        }

        scene.forEachRecordingPlayer(pd -> {
            for (Visit visit : pd.getVisits()) {
                if (visit.pieces.isEmpty()) continue;

                if (visit.arrivalDir != 0 && visit.startDim != null && visit.startTick >= now) {
                    long arriveTick = Math.max(now, visit.startTick - APPEAR_SOUND_LEAD_TICKS);
                    pendingSounds.add(new PendingSound(arriveTick, visit.startDim,
                            visit.sx, visit.sy, visit.sz, visit.arrivalDir, pd.getName(), visit.id, true));
                }
                if (visit.departDir != 0 && visit.endDim != null && visit.endTick > now) {
                    int lead = TimeTravel.oldSfx ? OG_VANISH_SOUND_LEAD_TICKS : VANISH_SOUND_LEAD_TICKS;
                    long soundTick = visit.endTick - lead;
                    if (soundTick < now) soundTick = now;
                    pendingSounds.add(new PendingSound(soundTick, visit.endDim,
                            visit.ex, visit.ey, visit.ez, visit.departDir, pd.getName(), visit.id, false));
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
        TimeTravel.TIMELINE_LOGGER.info("Clone queue rebuilt at tick {}: {} piece(s) pending",
                now, pending.size());
    }

    public void flushPending() {
        final long now = TimeTravel.timelineTick;
        while (!pending.isEmpty()
                && pending.peek().startTick() <= now + TimeTravel.cloneLookaheadTicks) {
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

    public void replayEvents() {
        long now = TimeTravel.timelineTick;
        for (Clone clone : roots) {
            if (clone.pendingSkip > 0 || clone.root.isFinished()) continue;
            if (clone.visit == null) continue;
            if (clone.fx.entity() instanceof ServerPlayer sp) CloneTracks.replay(clone.visit, sp, now);
        }
    }

    public void tickActive() {
        long now = TimeTravel.timelineTick;
        for (Clone clone : roots) {
            boolean protect = clone.pendingSkip > 0 || now < clone.fx.graceUntil;
            if (protect) shield(clone);
            else if (clone.shielded) unshield(clone, now);
            announceVanish(clone, now);

            if (clone.pendingSkip > 0) {
                int step = Math.min(clone.pendingSkip, CLONE_SKIP_PER_TICK);
                clone.root.tick(step);
                clone.pendingSkip -= step;
                if (clone.pendingSkip <= 0) clone.fx.graceUntil = now + 20;
                continue;
            }
            if (!clone.root.isFinished()) clone.fx.tick();
        }
        touchOrbs();
        if (now % 100 == 0) {
            roots.removeIf(g -> g.root.isFinished());
        }
    }

    private static void shield(Clone g) {
        if (!(g.fx.entity() instanceof ServerPlayer sp) || sp.isRemoved()) return;
        if (!g.shielded) {
            g.wasInvulnerable = sp.isInvulnerable();
            g.shielded = true;
        }
        sp.setInvulnerable(true);
        sp.fallDistance = 0;
        sp.setAirSupply(sp.getMaxAirSupply());
        sp.clearFire();
        sp.hurtTime = 0;
        sp.hurtDuration = 0;
        sp.hurtMarked = false;
        sp.invulnerableTime = 5;
    }

    private static void unshield(Clone g, long now) {
        g.shielded = false;
        if (!(g.fx.entity() instanceof ServerPlayer sp) || sp.isRemoved()) return;
        sp.setInvulnerable(g.wasInvulnerable);
        sp.fallDistance = 0;
        if (g.visit != null) CloneTracks.snapHealth(g.visit, sp, now);
        sp.hurtTime = 0;
        sp.hurtDuration = 0;
        sp.hurtMarked = false;
    }

    private void start(PendingPiece piece, int delayTicks) {
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        PlayerData pd = scene == null ? null : scene.getRecordingPlayer(piece.playerName());
        if (pd == null) return;

        ClonePlayback playback = MocapAdapter.startPlayback(piece.recording(), pd.getNickname(), pd.getSkin(), delayTicks);
        if (playback == null) return;

        Entity cloneEntity = CloneFx.findNewestClone(pd.getNickname());
        if (cloneEntity == null) {
            TimeTravel.TIMELINE_LOGGER.warn("[TT-CLONE] no entity right after startPlayback for {} (visit {}, delay {})", pd.getNickname(), piece.visitId(), delayTicks);
        } else {
            for (Clone g : roots) {
                if (g.fx.entity() == cloneEntity) {
                    TimeTravel.TIMELINE_LOGGER.warn("[TT-CLONE] findNewestClone returned an already bound clone (visit {} vs {})", g.visitId, piece.visitId());
                }
            }
        }

        CloneFx fx = new CloneFx(cloneEntity);
        fx.graceUntil = TimeTravel.timelineTick + 20;

        Visit cloneVisit = null;
        for (Visit v : pd.getVisits()) {
            if (v.id == piece.visitId()) {
                cloneVisit = v;
                break;
            }
        }

        int pendingSkip = piece.skipTicks();
        int syncSkip = Math.min(pendingSkip, CLONE_SYNC_SKIP_LIMIT);
        Clone clone = new Clone(playback, piece.playerName(), piece.visitId(), fx, pendingSkip - syncSkip, cloneVisit);
        clone.endTick = endTickOf(cloneVisit, piece.recording());
        roots.add(clone);

        if (cloneEntity != null && pendingSkip == 0
                && piece.segment() == 0
                && cloneVisit != null && cloneVisit.arrivalDir != 0
                && cloneEntity.level() instanceof ServerLevel level) {
            CloneAppearPayload p = new CloneAppearPayload(cloneEntity.getId(), delayTicks);
            for (ServerPlayer pl : new ArrayList<>(level.players())) {
                if (Clones.is(pl)) continue;
                TimelineNet.sendToPlayer(pl, p);
            }
        }

        shield(clone);
        if (syncSkip > 0) {
            playback.tick(syncSkip);
            fx.graceUntil = TimeTravel.timelineTick + 20;
        }

        if (TimeTravel.trackVoice && TimeTravel.voiceChatLoaded) {
            VoicechatCompat.onCloneStart(pd, piece.visitId(), piece.segment(), piece.visitStartTick(), cloneEntity);
        }
    }

    private static long endTickOf(Visit visit, String recording) {
        if (visit == null) return -1;
        for (int i = 0; i < visit.pieces.size(); i++) {
            if (!visit.pieces.get(i).recording.equals(recording)) continue;
            long end = i + 1 < visit.pieces.size() ? visit.pieces.get(i + 1).startTick : visit.endTick;
            return end > 0 ? end : -1;
        }
        return -1;
    }

    private static void announceVanish(Clone g, long now) {
        if (g.vanishSent || g.endTick < 0 || g.pendingSkip > 0) return;
        long remaining = g.endTick - now;
        if (remaining > VANISH_LEAD_TICKS) return;
        g.vanishSent = true;
        if (remaining <= 0) return;
        Entity entity = g.fx.entity();
        if (entity == null || entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) return;
        CloneVanishPayload payload = new CloneVanishPayload(entity.getId(), (int) remaining);
        for (ServerPlayer p : new ArrayList<>(level.players())) {
            if (Clones.is(p)) continue;
            TimelineNet.sendToPlayer(p, payload);
        }
    }

    public void stopAll() {
        for (Clone clone : roots) {
            try {
                if (!clone.root.isFinished()) clone.root.stop();
            } catch (Exception e) {
                TimeTravel.TIMELINE_LOGGER.error("Failed to stop clone playback", e);
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
                TimeTravel.TIMELINE_LOGGER.error("Failed to stop clone of a collapsed chain", e);
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

            if (TimeTravel.oldSfx) {
                playOldSfx(level, s);
                return;
            }

            SoundEvent sound = TTSounds.travel(s.dir() > 0);
            float pitch = s.appear() ? APPEAR_SOUND_PITCH : 1.0f;
            level.playSound(null, s.x(), s.y(), s.z(), sound, SoundSource.PLAYERS, 1.6f, pitch);
        } catch (Exception e) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to play time sound", e);
        }
    }

    private static void playOldSfx(ServerLevel level, PendingSound s) {
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
    }
    public int visitIdOf(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return -1;
        if (Clones.is(player)) {
            for (Clone clone : roots) {
                if (clone.fx.entity() == entity) return clone.visitId;
            }
            return -1;
        }
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        if (scene == null) return -1;
        PlayerData pd = scene.getRecordingPlayer(player.getGameProfile().getName());
        if (pd == null || !pd.getActive() || pd.getCurrentVisit() == null) return -1;
        return pd.getCurrentVisit().id;
    }

    private void touchOrbs() {
        for (Clone clone : roots) {
            if (clone.pendingSkip > 0 || clone.root.isFinished()) continue;
            if (!(clone.fx.entity() instanceof ServerPlayer sp) || sp.isRemoved()) continue;
            AABB box = sp.getBoundingBox().inflate(1.0, 0.5, 1.0);
            for (ExperienceOrb orb : sp.serverLevel().getEntitiesOfClass(ExperienceOrb.class, box)) {
                if (orb.isRemoved()) continue;
                sp.takeXpDelay = 0;
                orb.playerTouch(sp);
            }
        }
    }

    public CloneFx fxOf(Entity e) {
        for (Clone g : roots) if (g.fx.entity() == e) return g.fx;
        return null;
    }

    public Entity entityOfVisit(int visitId) {
        for (Clone clone : roots) {
            if (clone.visitId != visitId || clone.root.isFinished()) continue;
            Entity entity = clone.fx.entity();
            if (entity != null && !entity.isRemoved()) return entity;
        }
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        if (scene == null || TimeTravel.server == null) return null;
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) {
            if (Clones.is(player)) continue;
            PlayerData pd = scene.getRecordingPlayer(player.getGameProfile().getName());
            if (pd != null && pd.getCurrentVisit() != null && pd.getCurrentVisit().id == visitId) {
                return player;
            }
        }
        return null;
    }

    public boolean visitPending(int visitId) {
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
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
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        if (scene == null) return false;
        for (PlayerData pd : scene.getRecordingPlayers()) {
            if (pd.getCurrentVisit() != null && pd.getCurrentVisit().id == visitId) return true;
            for (Visit v : pd.getVisits()) if (v.id == visitId) return true;
        }
        return false;
    }
}
