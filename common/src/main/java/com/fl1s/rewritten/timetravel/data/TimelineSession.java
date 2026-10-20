package com.fl1s.rewritten.timetravel.data;

import com.fl1s.rewritten.timetravel.clone.CloneManager;
import com.fl1s.rewritten.timetravel.clone.DeathChain;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import com.fl1s.rewritten.timetravel.timeline.Timeline;
import com.fl1s.rewritten.timetravel.util.VisitManager;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class TimelineSession {

    /** Clone playback, pending queue, sounds, skip budget. */
    public final CloneManager clones = new CloneManager();

    /** Death tracking and paradox chain collapse. */
    public final DeathChain deathChain = new DeathChain();

    /** Visit lifecycle: begin/end on join/leave/jump, save mocap pieces. */
    public final VisitManager visits = new VisitManager();

    /** Core timeline: clock, jumps, per-tick work. */
    public final Timeline timeline = new Timeline();

    /** Read positions into shared history; the shared head is where non-travellers sit. */
    public final TimeHeads heads = new TimeHeads();

    /** Server tick of the previous jump (for the return command). */
    public long lastJumpOrigin = -1;

    /** Tasks deferred to the next server tick. */
    public final Queue<Runnable> delayedTasks = new ConcurrentLinkedQueue<>();
}