package com.fl1s.rewritten.timetravel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-world runtime state.
 * <p>
 * Created on server start, discarded on server stop. This prevents runtime
 * state (queued ghosts, pending pieces, delayed tasks, jump origin, idle flags)
 * from leaking between worlds in singleplayer or across world reloads.
 * <p>
 */
public final class TimelineSession {

    /** Ghost playback roots currently active in this world. */
    final List<TimeTravel.Ghost> ghostRoots = new ArrayList<>();

    /** Past-self pieces queued for playback, ordered by start tick. */
    final PriorityQueue<TimeTravel.PendingPiece> pending =
            new PriorityQueue<>(Comparator.comparingLong(TimeTravel.PendingPiece::startTick));

    /** Time-jump sounds queued for playback, ordered by tick. */
    final PriorityQueue<TimeTravel.PendingSound> pendingSounds =
            new PriorityQueue<>(Comparator.comparingLong(TimeTravel.PendingSound::tick));

    /** Server tick of the previous jump (for the return command). */
    long lastJumpOrigin = -1;

    /** Tasks deferred to the next server tick. */
    final Queue<Runnable> delayedTasks = new ConcurrentLinkedQueue<>();

    /** True when no recording players are online and playback is suspended. */
    boolean idle = false;

    /** True when the pending queue must be rebuilt on the next tick. */
    boolean needsRebuild = true;
}