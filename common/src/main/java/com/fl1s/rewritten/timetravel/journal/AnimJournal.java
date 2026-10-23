package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.compat.anim.RelayAdapter;
import com.fl1s.rewritten.timetravel.compat.anim.RelayRegistry;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Set;

public final class AnimJournal {
    private AnimJournal() {}

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit;
        public String typeId;
        public String data;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_anims.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    public static void record(long tick, int visit, ResourceLocation typeId, String data) {
        Entry e = new Entry();
        e.tick = tick;
        e.seq = STORE.nextSeq();
        e.visit = visit;
        e.typeId = typeId.toString();
        e.data = data;
        STORE.entries().add(e);
        STORE.markDirty();
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        STORE.tick(AnimJournal::replay);
    }

    private static void replay(Entry e) {
        try {
            ResourceLocation typeId = ResourceLocation.parse(e.typeId);
            RelayAdapter adapter = RelayRegistry.get(typeId);
            if (adapter == null) return;

            Entity target = TimeTravel.entityOfVisit(e.visit);
            if (!(target instanceof ServerPlayer sp) || sp.isRemoved()) return;
            if (!Clones.is(sp)) return;

            adapter.replay(e.data, sp.getId(), (ServerLevel) sp.level());
        } catch (Exception ex) {
            TimeTravel.TIMELINE_LOGGER.error("Failed to replay anim entry", ex);
        }
    }

    public static void rebuildUpcoming() { STORE.rebuildUpcoming(); }
    public static void onJump(long from, long to) { STORE.rebuildUpcoming(); }
    public static void eraseVisits(Set<Integer> visitIds) { STORE.eraseVisits(visitIds); }
    public static void load() { STORE.load(); }
    public static void save() { STORE.save(); }
    public static void saveIfDirty() { STORE.saveIfDirty(); }
    public static void clear() { STORE.clear(); }
}
