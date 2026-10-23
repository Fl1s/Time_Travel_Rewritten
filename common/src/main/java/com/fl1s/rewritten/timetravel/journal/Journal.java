package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.time.TimeContext;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import com.fl1s.rewritten.timetravel.util.JsonStore;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.function.ToIntFunction;

/**
 * Shared storage for a timeline journal.
 * <p>
 * Holds the entry list, the future-tick queue, the sequence counter and the
 * dirty flag, and provides uniform {@code load/save/clear/tick/rebuildUpcoming/eraseVisits}
 * implementations. Concrete journals own a static instance and pass in the
 * {@code tick/seq/visit} accessors.
 *
 * @param <E> entry type
 */
public final class Journal<E> {
    public static final long SHARED_CURSOR = Long.MIN_VALUE;

    private static final List<Journal<?>> ALL = new ArrayList<>();

    private final List<E> entries = new ArrayList<>();
    private final Map<Long, PriorityQueue<E>> cursors = new HashMap<>();
    private final Comparator<E> order;
    private final Type listType;
    private final String fileName;
    private final ToLongFunction<E> tickFn;
    private final ToLongFunction<E> seqFn;
    private final ToIntFunction<E> visitFn;
    private long nextSeq = 0;
    private boolean dirty = false;

    public Journal(String fileName, Type listType, ToLongFunction<E> tickFn, ToLongFunction<E> seqFn, ToIntFunction<E> visitFn) {
        this.fileName = fileName;
        this.listType = listType;
        this.tickFn = tickFn;
        this.seqFn = seqFn;
        this.visitFn = visitFn;
        this.order = Comparator.comparingLong(tickFn).thenComparingLong(seqFn);
        ALL.add(this);
    }

    public static void shiftAllHeads(long delta) {
        for (Journal<?> journal : ALL) TimeHeads.shiftKeys(journal.cursors, delta);
    }

    public List<E> entries() {
        return entries;
    }

    private PriorityQueue<E> cursor(long head) {
        return cursors.computeIfAbsent(head, k -> new PriorityQueue<>(order));
    }

    public void removeUpcoming(Predicate<E> filter) {
        for (PriorityQueue<E> q : cursors.values()) q.removeIf(filter);
    }

    public void dropCursor(long head) {
        cursors.remove(head);
    }

    public void addUpcoming(long head, E entry) {
        PriorityQueue<E> q = cursors.get(head);
        if (q != null) q.add(entry);
    }

    public long nextSeq() {
        return nextSeq++;
    }

    public void markDirty() {
        dirty = true;
    }

    public void load() {
        entries.clear();
        cursors.clear();
        nextSeq = 0;
        if (TimeTravel.worldFolder != null) {
            Path f = TimeTravel.worldFolder.resolve(fileName);
            List<E> loaded = JsonStore.load(f, listType);
            if (loaded != null) {
                entries.addAll(loaded);
                for (E e : loaded) nextSeq = Math.max(nextSeq, seqFn.applyAsLong(e) + 1);
            }
        }
        dirty = false;
        rebuildUpcoming();
    }

    public boolean save() {
        if (TimeTravel.worldFolder == null) return false;
        Path f = TimeTravel.worldFolder.resolve(fileName);
        boolean ok = JsonStore.save(f, entries, listType);
        if (ok) dirty = false;
        return ok;
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void clear() {
        entries.clear();
        cursors.clear();
        nextSeq = 0;
        dirty = true;
        save();
    }

    public void rebuildUpcoming() {
        rebuildUpcoming(SHARED_CURSOR, TimeContext.now());
    }

    public void rebuildUpcoming(long position) {
        rebuildUpcoming(SHARED_CURSOR, position);
    }

    public void rebuildUpcoming(long head, long position) {
        PriorityQueue<E> q = cursor(head);
        q.clear();
        for (E e : entries) {
            if (tickFn.applyAsLong(e) > position) q.add(e);
        }
    }

    public void tick(Consumer<E> onDue) {
        tick(SHARED_CURSOR, TimeContext.now(), onDue);
    }

    public void tick(long position, Consumer<E> onDue) {
        tick(SHARED_CURSOR, position, onDue);
    }

    public void tick(long head, long position, Consumer<E> onDue) {
        PriorityQueue<E> q = cursor(head);
        while (!q.isEmpty() && tickFn.applyAsLong(q.peek()) <= position) {
            onDue.accept(q.poll());
        }
    }

    public void eraseVisits(Set<Integer> visitIds) {
        entries.removeIf(e -> visitIds.contains(visitFn.applyAsInt(e)));
        removeUpcoming(e -> visitIds.contains(visitFn.applyAsInt(e)));
        dirty = true;
    }
}