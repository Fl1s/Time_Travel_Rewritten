package com.fl1s.rewritten.timetravel.vehicle;

import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.Journal;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Journal for the contents of rideable containers
 */
public final class VehicleContainerJournal {
    private VehicleContainerJournal() {}

    private static final int POLL_INTERVAL = 5;

    public static final class Entry {
        public String dim;
        public UUID uuid;
        public String oldItems;
        public String newItems;
        public long tick;
        public long seq;
        public int visit;
    }

    private static final Journal<Entry> STORE = new Journal<>(
            "timetravel_rewritten_vehicle_containers.json",
            new TypeToken<ArrayList<Entry>>() {}.getType(),
            e -> e.tick, e -> e.seq, e -> e.visit);

    private static final Map<UUID, String> last = new HashMap<>();
    private static boolean applying = false;

    public static boolean isApplying() { return applying; }

    private static boolean isContainer(Entity e) {
        return e instanceof MinecartChest || e instanceof ChestBoat;
    }

    private static String snapshot(Container container) {
        NonNullList<ItemStack> list = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < container.getContainerSize(); i++) list.set(i, container.getItem(i));
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, list, TimeTravel.server.registryAccess());
        return tag.toString();
    }

    public static void tick() {
        if (!TimeTravel.isLooping || TimeTravel.server == null) return;
        if (TimeTravel.timelineTick % POLL_INTERVAL != 0) return;

        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity e : level.getAllEntities()) {
                if (!isContainer(e)) continue;
                if (!(e instanceof Container container)) continue;

                UUID id = e.getUUID();
                String now = snapshot(container);
                String prev = last.get(id);
                if (prev == null) {
                    last.put(id, now);
                    continue;
                }
                if (prev.equals(now)) continue;

                PlayerData pd = BlockJournal.nearestRecorder(level, e.blockPosition());
                if (pd == null || pd.getCurrentVisit() == null) {
                    last.put(id, now);
                    continue;
                }

                Entry entry = new Entry();
                entry.dim = dim;
                entry.uuid = id;
                entry.oldItems = prev;
                entry.newItems = now;
                entry.tick = TimeTravel.timelineTick;
                entry.seq = STORE.nextSeq();
                entry.visit = pd.getCurrentVisit().id;
                STORE.entries().add(entry);
                last.put(id, now);
                STORE.markDirty();
            }
        }
    }

    public static void onJump(long from, long to) {
        List<Entry> affected = new ArrayList<>();
        if (to < from) {
            for (Entry e : STORE.entries()) if (e.tick > to && e.tick <= from) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq).reversed());
            for (Entry e : affected) apply(e, false);
        } else {
            for (Entry e : STORE.entries()) if (e.tick > from && e.tick <= to) affected.add(e);
            affected.sort(Comparator.comparingLong((Entry e) -> e.tick).thenComparingLong(e -> e.seq));
            for (Entry e : affected) apply(e, true);
        }
        rebuildLastIndex();
        STORE.rebuildUpcoming();
    }

    private static void apply(Entry e, boolean toNew) {
        applying = true;
        try {
            ServerLevel level = TimeTravel.server.getLevel(Levels.key(e.dim));
            if (level == null) return;
            Entity entity = findLoaded(level, e.uuid);
            if (!(entity instanceof Container container)) return;
            if (!isContainer(entity)) return;

            String tag = toNew ? e.newItems : e.oldItems;
            int size = container.getContainerSize();
            NonNullList<ItemStack> list = NonNullList.withSize(size, ItemStack.EMPTY);
            CompoundTag parsed = TagParser.parseTag(tag);
            ContainerHelper.loadAllItems(parsed, list, TimeTravel.server.registryAccess());
            for (int i = 0; i < size; i++) {
                container.setItem(i, list.get(i).copy());
            }
            container.setChanged();
        } catch (Exception ex) {
            TimeTravel.LOOP_LOGGER.error("Failed to apply vehicle container entry for {}", e.uuid, ex);
        } finally {
            applying = false;
        }
    }

    private static Entity findLoaded(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid);
    }

    private static void rebuildLastIndex() {
        last.clear();
        for (Entry e : STORE.entries()) {
            String prev = last.get(e.uuid);
            Entry prevEntry = null;
            for (Entry x : STORE.entries()) {
                if (x.uuid.equals(e.uuid) && x.tick <= e.tick) {
                    if (prevEntry == null || x.tick > prevEntry.tick
                            || (x.tick == prevEntry.tick && x.seq > prevEntry.seq)) {
                        prevEntry = x;
                    }
                }
            }
            if (prevEntry != null) last.put(e.uuid, prevEntry.newItems);
        }
    }

    public static void eraseVisits(Set<Integer> visitIds) {
        STORE.eraseVisits(visitIds);
        rebuildLastIndex();
    }

    public static void rebuildUpcoming() { STORE.rebuildUpcoming(); }

    public static void load() {
        last.clear();
        STORE.load();
        rebuildLastIndex();
    }

    public static void save() { STORE.save(); }
    public static void saveIfDirty() { STORE.saveIfDirty(); }

    public static void clear() {
        last.clear();
        STORE.clear();
    }
}
