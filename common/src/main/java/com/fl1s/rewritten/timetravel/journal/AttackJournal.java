package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.compat.anim.AttackRelay;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Set;

public final class AttackJournal {
    private AttackJournal() {
    }

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit;
        public int comboCount;
        public boolean isSneaking;
        public int selectedSlot;
        public int cursorTarget;
        public int[] entityIds;
        public String dim;
    }

    private static final Journal<Entry> STORE = new Journal<>("timetravel_rewritten_attacks.json", new TypeToken<ArrayList<Entry>>() {
    }.getType(), e -> e.tick, e -> e.seq, e -> e.visit);

    public static void record(long tick, int visit, String dim, int comboCount, boolean isSneaking, int selectedSlot, int cursorTarget, int[] entityIds) {
        Entry e = new Entry();
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = visit;
        e.dim = dim;
        e.comboCount = comboCount;
        e.isSneaking = isSneaking;
        e.selectedSlot = selectedSlot;
        e.cursorTarget = cursorTarget;
        e.entityIds = entityIds.clone();
        STORE.entries().add(e);
        STORE.markDirty();
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        STORE.tick(AttackJournal::replay);
    }

    private static void replay(Entry e) {
        AttackRelay relay = AttackRelay.get();
        if (relay == null) return;
        Entity target = TimeTravel.entityOfVisit(e.visit);
        if (!(target instanceof ServerPlayer sp) || sp.isRemoved() || !Clones.is(sp)) return;
        relay.replay(e, sp);
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void onJump(long from, long to) {
        STORE.rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
    }

    public static void load() {
        STORE.load();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        STORE.clear();
    }
}
