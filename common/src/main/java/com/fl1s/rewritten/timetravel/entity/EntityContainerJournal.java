package com.fl1s.rewritten.timetravel.entity;

import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Visit;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Journal for the contents of entity containers (chest minecarts, chest boats,
 * and any other {@link ContainerEntity}).
 */
public final class EntityContainerJournal {
    private EntityContainerJournal() {
    }

    public static final class Entry {
        public long tick;
        public long seq;
        public int visit = -1;
        public UUID uuid;
        public String dim;
        public String oldItems;
        public String newItems;
    }

    private static final class Snap {
        final ServerLevel level;
        final int visit;
        List<ItemStack> last;
        boolean seen;

        Snap(ServerLevel level, int visit, List<ItemStack> last) {
            this.level = level;
            this.visit = visit;
            this.last = last;
        }
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_entity_containers.json",
            new TypeToken<ArrayList<Entry>>() {
            }.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, Snap> snaps = new HashMap<>();

    public static void tick() {
        if (TimeTravel.isLooping && TimeTravel.loopSceneManager != null) poll();
        STORE.tick(e -> apply(e.uuid, e.dim, e.newItems));
    }

    private static void poll() {
        for (Snap snap : snaps.values()) snap.seen = false;

        TimeTravel.forEachOnlineRecorded((player, pd) -> {
            if (player.containerMenu == player.inventoryMenu) return;
            Visit visit = pd.getCurrentVisit();
            if (visit == null) return;
            for (Slot slot : player.containerMenu.slots) {
                if (!(slot.container instanceof ContainerEntity)) continue;
                if (!(slot.container instanceof Entity entity)) continue;
                if (!(entity.level() instanceof ServerLevel level)) continue;
                Snap snap = snaps.get(entity.getUUID());
                if (snap == null) {
                    snap = new Snap(level, visit.id, BlockJournal.copyOf(slot.container));
                    snaps.put(entity.getUUID(), snap);
                }
                snap.seen = true;
            }
        });

        Iterator<Map.Entry<UUID, Snap>> it = snaps.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Snap> en = it.next();
            Snap snap = en.getValue();
            Entity entity = snap.level.getEntity(en.getKey());
            if (!(entity instanceof Container container) || entity.isRemoved()) {
                it.remove();
                continue;
            }
            List<ItemStack> now = BlockJournal.copyOf(container);
            if (!BlockJournal.sameItems(snap.last, now)) {
                Entry e = new Entry();
                e.tick = TimeTravel.timelineTick;
                e.seq = STORE.nextSeq();
                e.visit = snap.visit;
                e.uuid = en.getKey();
                e.dim = snap.level.dimension().location().toString();
                e.oldItems = BlockJournal.serializeItems(snap.last);
                e.newItems = BlockJournal.serializeItems(now);
                STORE.entries().add(e);
                STORE.markDirty();
                snap.last = now;
            }
            if (!snap.seen) it.remove();
        }
    }

    private static void apply(UUID id, String dim, String items) {
        if (TimeTravel.server == null) return;
        ServerLevel level = TimeTravel.server.getLevel(Levels.key(dim));
        if (level == null) return;
        Entity entity = level.getEntity(id);
        if (!(entity instanceof Container container) || entity.isRemoved()) return;
        List<ItemStack> list = BlockJournal.parseItems(items, container.getContainerSize());
        for (int i = 0; i < list.size(); i++) container.setItem(i, list.get(i).copy());
        container.setChanged();
        snaps.remove(id);
    }

    private static boolean before(Entry a, Entry b) {
        return a.tick < b.tick || (a.tick == b.tick && a.seq < b.seq);
    }

    public static void onJump(long from, long to) {
        Map<UUID, Entry> first = new HashMap<>();
        Map<UUID, Entry> latest = new HashMap<>();
        for (Entry e : STORE.entries()) {
            Entry f = first.get(e.uuid);
            if (f == null || before(e, f)) first.put(e.uuid, e);
            if (e.tick <= to) {
                Entry l = latest.get(e.uuid);
                if (l == null || before(l, e)) latest.put(e.uuid, e);
            }
        }
        for (Map.Entry<UUID, Entry> en : first.entrySet()) {
            Entry l = latest.get(en.getKey());
            if (l != null) apply(l.uuid, l.dim, l.newItems);
            else apply(en.getValue().uuid, en.getValue().dim, en.getValue().oldItems);
        }
        rebuildUpcoming();
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        snaps.clear();
    }

    public static void rebuildUpcoming() {
        STORE.rebuildUpcoming();
    }

    public static void load() {
        snaps.clear();
        STORE.load();
    }

    public static void save() {
        STORE.save();
    }

    public static void saveIfDirty() {
        STORE.saveIfDirty();
    }

    public static void clear() {
        snaps.clear();
        STORE.clear();
    }
}
