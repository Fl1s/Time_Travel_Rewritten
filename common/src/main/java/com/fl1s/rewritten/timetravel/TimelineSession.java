package com.fl1s.rewritten.timetravel;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class TimelineSession {

    /** Ghost playback, pending queue, sounds, skip budget. */
    final GhostManager ghosts = new GhostManager();

    /** Death tracking and paradox chain collapse. */
    final DeathChain deathChain = new DeathChain();

    /** Visit lifecycle: begin/end on join/leave/jump, save mocap pieces. */
    final VisitManager visits = new VisitManager();

    /** Server tick of the previous jump (for the return command). */
    long lastJumpOrigin = -1;

    /** Tasks deferred to the next server tick. */
    final Queue<Runnable> delayedTasks = new ConcurrentLinkedQueue<>();
}