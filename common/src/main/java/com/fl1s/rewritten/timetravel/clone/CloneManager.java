package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.compat.VoicechatCompat;
import com.fl1s.rewritten.timetravel.data.*;
import com.fl1s.rewritten.timetravel.mocap.ClonePlayback;
import com.fl1s.rewritten.timetravel.mocap.MocapAdapter;
import com.fl1s.rewritten.timetravel.net.CloneAppearPayload;
import com.fl1s.rewritten.timetravel.net.CloneVanishPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.time.TimeHead;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import com.fl1s.rewritten.timetravel.util.TTSounds;
import com.fl1s.rewritten.timetravel.util.TimelineSceneManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

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
    public static final int FLOWER_VANISH_LEAD_TICKS = 64;

    public static final int APPEAR_SOUND_LEAD_TICKS = 0;
    public static final int VANISH_SOUND_LEAD_TICKS = 25;
    public static final int OG_VANISH_SOUND_LEAD_TICKS = 5;
    public static final int FLOWER_VANISH_SOUND_LEAD_TICKS = 10;
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
        long head;
        int pendingSkip;
        boolean shielded;
        boolean wasInvulnerable;
        long endTick = -1;
        boolean vanishSent;

        Clone(ClonePlayback root, String playerName, int visitId, CloneFx fx, int pendingSkip, Visit visit, long head) {
            this.root = root;
            this.playerName = playerName;
            this.visitId = visitId;
            this.fx = fx;
            this.pendingSkip = pendingSkip;
            this.visit = visit;
            this.head = head;
        }
    }

    public static final long NO_HEAD = Long.MIN_VALUE;

    private final List<Clone> roots = new ArrayList<>();
    private final Map<Long, PriorityQueue<PendingPiece>> pending = new HashMap<>();
    private final Map<Long, PriorityQueue<PendingSound>> pendingSounds = new HashMap<>();
    private final Set<Long> built = new HashSet<>();
    private boolean needsRebuild = true;
    private boolean idle = false;

    static long positionOf(long head) {
        return Math.max(0, TimeTravel.presentTick - head);
    }

    private PriorityQueue<PendingPiece> pieces(long head) {
        return pending.computeIfAbsent(head,
                k -> new PriorityQueue<>(Comparator.comparingLong(PendingPiece::startTick)));
    }

    private PriorityQueue<PendingSound> sounds(long head) {
        return pendingSounds.computeIfAbsent(head,
                k -> new PriorityQueue<>(Comparator.comparingLong(PendingSound::tick)));
    }

    public long headOf(Entity entity) {
        for (Clone clone : roots) {
            if (clone.fx.entity() == entity) return clone.head;
        }
        return NO_HEAD;
    }

    public void markNeedsRebuild() { needsRebuild = true; }

    public boolean consumeNeedsRebuild() {
        boolean r = needsRebuild;
        needsRebuild = false;
        return r;
    }

    public boolean isIdle() { return idle; }
    public void setIdle(boolean v) { idle = v; }

    public int countPending() {
        int total = 0;
        for (PriorityQueue<PendingPiece> q : pending.values()) total += q.size();
        return total;
    }

    List<Clone> roots() { return roots; }

    public void clearPending() {
        pending.clear();
        pendingSounds.clear();
    }

    public void dropHead(long head) {
        pending.remove(head);
        pendingSounds.remove(head);
        built.remove(head);
        stopHead(head);
    }

    public void shiftHeads(long delta) {
        TimeHeads.shiftKeys(pending, delta);
        TimeHeads.shiftKeys(pendingSounds, delta);
        Set<Long> moved = new HashSet<>();
        for (Long head : built) moved.add(TimeHeads.shifted(head, delta));
        built.clear();
        built.addAll(moved);
        for (Clone clone : roots) clone.head = TimeHeads.shifted(clone.head, delta);
    }

    public void rebuildAll() {
        needsRebuild = false;
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;
        stopAll();
        pending.clear();
        pendingSounds.clear();
        built.clear();
        for (TimeHead head : heads.occupied()) rebuild(head.offset(), head.position());
    }

    public void flushAll() {
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;
        for (TimeHead head : heads.occupied()) {
            if (!built.contains(head.offset())) rebuild(head.offset(), head.position());
            flushPending(head.offset(), head.position());
            flushSounds(head.offset(), head.position());
        }
    }

    public void rebuild(long head, final long now) {
        built.add(head);
        pieces(head).clear();
        sounds(head).clear();

        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        if (scene == null) return;

        scene.forEachRecordingPlayer(pd -> {
            for (Visit visit : pd.getVisits()) queueVisit(head, now, pd, visit);
            queueVisit(head, now, pd, pd.getCurrentVisit());
        });
        TimeTravel.TIMELINE_LOGGER.info("Clone queue for head {} rebuilt at tick {}: {} piece(s) pending",
                head, now, pieces(head).size());
    }

    private void queueVisit(long head, long now, PlayerData pd, Visit visit) {
        if (visit == null || visit.pieces.isEmpty()) return;

        if (visit.arrivalDir != 0 && visit.startDim != null && visit.startTick >= now) {
            long arriveTick = Math.max(now, visit.startTick - APPEAR_SOUND_LEAD_TICKS);
            sounds(head).add(new PendingSound(arriveTick, visit.startDim,
                    visit.sx, visit.sy, visit.sz, visit.arrivalDir, pd.getName(), visit.id, true));
        }
        queueDeparture(head, now, pd, visit);
        queuePieces(head, now, pd, visit, 0);
    }

    private void queueDeparture(long head, long now, PlayerData pd, Visit visit) {
        if (visit.departDir == 0 || visit.endDim == null || visit.endTick <= now) return;
        int lead;
        if (TimeTravel.oldSfx) {
            lead = OG_VANISH_SOUND_LEAD_TICKS;
        } else if (TimeTravel.flowerManMode) {
            lead = FLOWER_VANISH_SOUND_LEAD_TICKS;
        } else {
            lead = VANISH_SOUND_LEAD_TICKS;
        }
        long soundTick = Math.max(now, visit.endTick - lead);
        sounds(head).add(new PendingSound(soundTick, visit.endDim,
                visit.ex, visit.ey, visit.ez, visit.departDir, pd.getName(), visit.id, false));
    }

    private void queuePieces(long head, long now, PlayerData pd, Visit visit, int fromIndex) {
        long tail = tailOf(pd, visit);
        for (int i = Math.max(0, fromIndex); i < visit.pieces.size(); i++) {
            Visit.Piece piece = visit.pieces.get(i);
            long end = (i + 1 < visit.pieces.size())
                    ? visit.pieces.get(i + 1).startTick
                    : tail;
            if (piece.startTick >= now) {
                pieces(head).add(new PendingPiece(piece.startTick, piece.recording, pd.getName(),
                        0, visit.id, i, visit.startTick));
            } else if (end > now) {
                pieces(head).add(new PendingPiece(now, piece.recording, pd.getName(),
                        (int) (now - piece.startTick), visit.id, i, visit.startTick));
            }
        }
    }

    private static long tailOf(PlayerData pd, Visit visit) {
        return visit.endTick >= 0 ? visit.endTick : pd.getChunkStartTick();
    }

    public void publishPieces(PlayerData pd, Visit visit, int fromIndex) {
        if (!TimeTravel.individualTime || visit == null) return;
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;
        for (TimeHead head : heads.occupied()) {
            if (!built.contains(head.offset())) continue;
            queuePieces(head.offset(), head.position(), pd, visit, fromIndex);
        }
    }

    public void publishDeparture(PlayerData pd, Visit visit) {
        if (!TimeTravel.individualTime || visit == null) return;
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;
        for (TimeHead head : heads.occupied()) {
            if (!built.contains(head.offset())) continue;
            queueDeparture(head.offset(), head.position(), pd, visit);
        }
    }

    public void flushPending(long head, long now) {
        PriorityQueue<PendingPiece> queue = pieces(head);
        while (!queue.isEmpty()
                && queue.peek().startTick() <= now + TimeTravel.cloneLookaheadTicks) {
            PendingPiece piece = queue.poll();
            start(piece, (int) Math.max(0, piece.startTick() - now), head, now);
        }
    }

    public void flushSounds(long head, long now) {
        PriorityQueue<PendingSound> queue = sounds(head);
        while (!queue.isEmpty() && queue.peek().tick() <= now) {
            playTimeSound(queue.poll(), head);
        }
    }

    public void replayEvents() {
        for (Clone clone : roots) {
            if (clone.pendingSkip > 0 || clone.root.isFinished()) continue;
            if (clone.visit == null) continue;
            if (clone.fx.entity() instanceof ServerPlayer sp) {
                CloneTracks.replay(clone.visit, sp, positionOf(clone.head));
            }
        }
    }

    public void tickActive() {
        for (Clone clone : roots) {
            long now = positionOf(clone.head);
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
        if (TimeTravel.presentTick % 100 == 0) {
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

    private void start(PendingPiece piece, int delayTicks, long head, long now) {
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
        fx.graceUntil = now + 20;

        Visit cloneVisit = null;
        for (Visit v : pd.getVisits()) {
            if (v.id == piece.visitId()) {
                cloneVisit = v;
                break;
            }
        }
        if (cloneVisit == null && pd.getCurrentVisit() != null
                && pd.getCurrentVisit().id == piece.visitId()) {
            cloneVisit = pd.getCurrentVisit();
        }

        int pendingSkip = piece.skipTicks();
        int syncSkip = Math.min(pendingSkip, CLONE_SYNC_SKIP_LIMIT);
        Clone clone = new Clone(playback, piece.playerName(), piece.visitId(), fx, pendingSkip - syncSkip, cloneVisit, head);
        clone.endTick = endTickOf(cloneVisit, piece.recording());
        roots.add(clone);
        HeadTracking.markDirty();

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
            fx.graceUntil = now + 20;
        }

        if (TimeTravel.trackVoice && TimeTravel.voiceChatLoaded) {
            VoicechatCompat.onCloneStart(pd, piece.visitId(), piece.segment(), piece.visitStartTick(), cloneEntity);
        }
    }

    private static long endTickOf(Visit visit, String recording) {
        if (visit == null || visit.endTick <= 0) return -1;
        int last = visit.pieces.size() - 1;
        if (last < 0 || !visit.pieces.get(last).recording.equals(recording)) return -1;
        return visit.endTick;
    }

    private static void announceVanish(Clone g, long now) {
        if (g.vanishSent || g.endTick < 0 || g.pendingSkip > 0) return;
        long remaining = g.endTick - now;
        int lead = TimeTravel.flowerManMode ? FLOWER_VANISH_LEAD_TICKS : VANISH_LEAD_TICKS;
        if (remaining > lead) return;
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

    public void retainHeads(Set<Long> live) {
        List<Long> stale = new ArrayList<>();
        for (Long head : pending.keySet()) if (!live.contains(head)) stale.add(head);
        for (Long head : pendingSounds.keySet()) if (!live.contains(head) && !stale.contains(head)) stale.add(head);
        for (Clone clone : roots) if (!live.contains(clone.head) && !stale.contains(clone.head)) stale.add(clone.head);
        for (Long head : stale) dropHead(head);
    }

    public void stopHead(long head) {
        roots.removeIf(g -> {
            if (g.head != head) return false;
            try {
                if (!g.root.isFinished()) g.root.stop();
            } catch (Exception e) {
                TimeTravel.TIMELINE_LOGGER.error("Failed to stop clone of a released head", e);
            }
            return true;
        });
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
        for (PriorityQueue<PendingPiece> q : pending.values()) {
            q.removeIf(pp -> pp.playerName().equals(playerName) && pp.visitId() >= fromVisitId);
        }
        for (PriorityQueue<PendingSound> q : pendingSounds.values()) {
            q.removeIf(ps -> ps.playerName().equals(playerName) && ps.visitId() >= fromVisitId);
        }
    }

    private void playTimeSound(PendingSound s, long head) {
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(s.dim()));
            if (level == null) return;
            emitTravel(level, s.x(), s.y(), s.z(), s.dir(), s.appear(),
                    TimeTravel.individualTime ? listener -> TimeTravel.headOffsetOf(listener) == head : null);
        } catch (Exception e) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to play time sound", e);
        }
    }

    public static void emitTravel(ServerLevel level, double x, double y, double z, int dir,
                                  boolean appear, Predicate<ServerPlayer> audience) {
        if (TimeTravel.oldSfx) {
            if (dir > 0) {
                emit(level, x, y, z, audience, SoundEvents.ENDERMAN_TELEPORT, 1.6f, 1.7f);
                emit(level, x, y, z, audience, SoundEvents.AMETHYST_BLOCK_CHIME, 1.4f, 1.5f);
            } else {
                emit(level, x, y, z, audience, SoundEvents.ENDERMAN_TELEPORT, 1.6f, 0.6f);
                emit(level, x, y, z, audience, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4f, 0.5f);
            }
            return;
        }
        emit(level, x, y, z, audience, TTSounds.travel(dir > 0), 1.6f,
                appear ? APPEAR_SOUND_PITCH : 1.0f);
    }

    private static void emit(ServerLevel level, double x, double y, double z,
                             Predicate<ServerPlayer> audience, SoundEvent sound,
                             float volume, float pitch) {
        if (audience == null) {
            level.playSound(null, x, y, z, sound, SoundSource.PLAYERS, volume, pitch);
            return;
        }

        double radius = volume > 1.0f ? 16.0 * volume : 16.0;
        double radiusSq = radius * radius;
        ClientboundSoundPacket packet = new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.PLAYERS,
                x, y, z, volume, pitch, level.getRandom().nextLong());

        for (ServerPlayer listener : level.players()) {
            if (Clones.is(listener)) continue;
            if (!audience.test(listener)) continue;
            if (listener.distanceToSqr(x, y, z) > radiusSq) continue;
            listener.connection.send(packet);
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
                    return v.startTick > TimeTravel.sharedTick
                            && v.startTick - TimeTravel.sharedTick <= PENDING_VISIT_WINDOW;
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
