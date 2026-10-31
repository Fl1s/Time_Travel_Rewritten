package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.util.JsonStore;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
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
    private final List<E> entries = new ArrayList<>();
    private final PriorityQueue<E> upcoming;
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
        this.upcoming = new PriorityQueue<>(Comparator.comparingLong(tickFn).thenComparingLong(seqFn));
    }

    public List<E> entries() {
        return entries;
    }

    public PriorityQueue<E> upcoming() {
        return upcoming;
    }

    public long nextSeq() {
        return nextSeq++;
    }

    public void markDirty() {
        dirty = true;
    }

    public void load() {
        entries.clear();
        upcoming.clear();
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
        upcoming.clear();
        nextSeq = 0;
        dirty = true;
        save();
    }

    public void rebuildUpcoming() {
        upcoming.clear();
        for (E e : entries) {
            if (tickFn.applyAsLong(e) > TimeTravel.timelineTick) upcoming.add(e);
        }
    }

    public void tick(Consumer<E> onDue) {
        while (!upcoming.isEmpty() && tickFn.applyAsLong(upcoming.peek()) <= TimeTravel.timelineTick) {
            onDue.accept(upcoming.poll());
        }
    }

    public void eraseVisits(Set<Integer> visitIds) {
        if (visitIds == null || visitIds.isEmpty()) return;
        if (visitIds.contains(-1)) {
            HashSet<Integer> filtered = new HashSet<>(visitIds);
            filtered.remove(-1);
            if (filtered.isEmpty()) return;
            eraseVisits(filtered);
            return;
        }

        int before = entries.size();
        entries.removeIf(e -> visitIds.contains(visitFn.applyAsInt(e)));
        if (entries.size() == before) return;

        upcoming.clear();
        for (E e : entries) {
            if (tickFn.applyAsLong(e) > TimeTravel.timelineTick) upcoming.add(e);
        }
        dirty = true;
    }
}