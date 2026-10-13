package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.util.JsonStore;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public abstract class PersistentDataJournal {

    public static class Entry {
        public long tick;
        public long seq;
        public int visit = -1;
        public String id;
        public String nbt;
    }

    private static final Type LIST_TYPE = new TypeToken<ArrayList<Entry>>() {
    }.getType();

    private final String fileName;
    private final List<Entry> entries = new ArrayList<>();
    private final PriorityQueue<Entry> upcoming = new PriorityQueue<>(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
    private long nextSeq = 0;
    private boolean dirty = false;

    protected PersistentDataJournal(String fileName) {
        this.fileName = fileName;
    }

    protected abstract CompoundTag capture(String id);

    protected abstract void apply(String id, CompoundTag nbt);

    protected abstract Set<String> objectIds();

    public List<Entry> entries() {
        return entries;
    }

    public long nextSeq() {
        return nextSeq++;
    }

    public void markDirty() {
        dirty = true;
    }

    public void snapshotAll(int visitId) {
        for (String id : objectIds()) {
            CompoundTag tag = capture(id);
            if (tag == null) continue;
            Entry e = new Entry();
            e.tick = TimeTravel.timelineTick;
            e.seq = nextSeq++;
            e.visit = visitId;
            e.id = id;
            e.nbt = tag.toString();
            entries.add(e);
            dirty = true;
        }
    }

    public void snapshotOne(String objectId, int visitId) {
        CompoundTag tag = capture(objectId);
        if (tag == null) return;
        Entry e = new Entry();
        e.tick = TimeTravel.timelineTick;
        e.seq = nextSeq++;
        e.visit = visitId;
        e.id = objectId;
        e.nbt = tag.toString();
        entries.add(e);
        dirty = true;
    }

    public void restore(long targetTick) {
        Map<String, Entry> best = new HashMap<>();
        for (Entry e : entries) {
            if (e.tick > targetTick) continue;
            Entry cur = best.get(e.id);
            if (cur == null || e.tick > cur.tick || (e.tick == cur.tick && e.seq > cur.seq)) {
                best.put(e.id, e);
            }
        }
        for (Map.Entry<String, Entry> en : best.entrySet()) {
            try {
                CompoundTag tag = TagParser.parseTag(en.getValue().nbt);
                apply(en.getKey(), tag);
            } catch (Exception ex) {
                TimeTravel.LOOP_LOGGER.error("Failed to restore persistent data '{}'", en.getKey(), ex);
            }
        }
    }

    public void load() {
        entries.clear();
        upcoming.clear();
        nextSeq = 0;
        if (TimeTravel.worldFolder != null) {
            Path f = TimeTravel.worldFolder.resolve(fileName);
            List<Entry> loaded = JsonStore.load(f, LIST_TYPE);
            if (loaded != null) {
                entries.addAll(loaded);
                for (Entry e : loaded) nextSeq = Math.max(nextSeq, e.seq + 1);
            }
        }
        dirty = false;
        rebuildUpcoming();
    }

    public boolean save() {
        if (TimeTravel.worldFolder == null) return false;
        Path f = TimeTravel.worldFolder.resolve(fileName);
        boolean ok = JsonStore.save(f, entries, LIST_TYPE);
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
        for (Entry e : entries) {
            if (e.tick > TimeTravel.timelineTick) upcoming.add(e);
        }
    }

    public void eraseVisits(Set<Integer> visitIds) {
        entries.removeIf(e -> visitIds.contains(e.visit));
        upcoming.removeIf(e -> visitIds.contains(e.visit));
        dirty = true;
    }

    public void tick() {
    }
}
