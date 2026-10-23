package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.mixin.ItemEntityAccessor;
import com.fl1s.rewritten.timetravel.time.TimeContext;
import com.fl1s.rewritten.timetravel.time.TimeHead;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HeadItemView {

    private HeadItemView() {
    }

    public static final String TAG = "tt_head_item";
    static final int LIFESPAN = 6000;

    private record Copy(long head, UUID original, ServerLevel level) {}

    private static final Map<UUID, Copy> copies = new HashMap<>();
    private static final Map<Long, Map<UUID, UUID>> byHead = new HashMap<>();

    private static ItemEntity touchedItem;
    private static UUID touchedOriginal;
    private static ServerPlayer touchedBy;
    private static ItemStack touchedStack = ItemStack.EMPTY;

    private static boolean enabled() {
        return TimeTravel.individualTime && TimeTravel.isLooping;
    }

    public static boolean isCopy(Entity entity) {
        return entity instanceof ItemEntity && copies.containsKey(entity.getUUID());
    }

    public static void shiftHeads(long delta) {
        copies.replaceAll((id, copy) -> new Copy(TimeHeads.shifted(copy.head(), delta), copy.original(), copy.level()));
        TimeHeads.shiftKeys(byHead, delta);
    }

    public static boolean isOrphan(ItemEntity item) {
        return item.getTags().contains(TAG) && !copies.containsKey(item.getUUID());
    }

    public static UUID originalOf(Entity entity) {
        if (entity == touchedItem && touchedOriginal != null) return touchedOriginal;
        Copy copy = copies.get(entity.getUUID());
        return copy == null ? entity.getUUID() : copy.original();
    }

    private static long positionOf(ServerPlayer player) {
        if (!TimeTravel.individualTime) return TimeContext.now();
        if (Clones.is(player)) return TimeTravel.clonePosition(player);
        return Math.max(0, TimeTravel.presentTick - TimeTravel.headOffsetOf(player));
    }

    private static boolean reached(long position, UUID id) {
        long born = ItemFlowJournal.bornOf(id);
        return born == Long.MIN_VALUE || position >= born;
    }

    public static boolean visibleTo(Entity entity, ServerPlayer viewer) {
        if (!(entity instanceof ItemEntity)) return true;
        Copy copy = copies.get(entity.getUUID());
        if (copy != null) return copy.head() == TimeTravel.headOffsetOf(viewer);
        return reached(positionOf(viewer), entity.getUUID());
    }

    public static boolean canTake(ItemEntity item, Player player) {
        if (!TimeTravel.isLooping || !(player instanceof ServerPlayer taker)) return true;
        Copy copy = copies.get(item.getUUID());
        UUID original = copy == null ? item.getUUID() : copy.original();
        if (enabled()) {
            if (copy == null && !reached(positionOf(taker), original)) return false;
            if (copy != null && (Clones.is(taker) || copy.head() != TimeTravel.headOffsetOf(taker))) return false;
        }
        if (!TimeTravel.noDuplication || Clones.is(taker)) return true;
        return !ItemFlowJournal.pickedUpAfter(original, positionOf(taker));
    }

    public static void beginTouch(ItemEntity item, Player player) {
        touchedOriginal = originalOf(item);
        touchedItem = item;
        touchedBy = player instanceof ServerPlayer sp ? sp : null;
        touchedStack = item.getItem().copy();
    }

    public static void endTouch() {
        touchedItem = null;
        touchedOriginal = null;
        touchedBy = null;
        touchedStack = ItemStack.EMPTY;
    }

    public static void onRemoved(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof ItemEntity item)) return;
        Copy copy = copies.remove(item.getUUID());
        if (copy != null) {
            Map<UUID, UUID> mine = byHead.get(copy.head());
            if (mine != null) mine.remove(copy.original());
            return;
        }
        if (!enabled() || !reason.shouldDestroy() || ItemFlowJournal.isApplying()) return;
        if (!(item.level() instanceof ServerLevel level)) return;
        long born = ItemFlowJournal.bornOf(item.getUUID());
        if (born == Long.MIN_VALUE) return;
        TimeHeads heads = TimeTravel.heads();
        if (heads == null) return;

        boolean touched = touchedItem == item;
        long until = touched && touchedBy != null ? positionOf(touchedBy) : TimeTravel.presentTick;
        ItemStack stack = touched ? touchedStack : item.getItem();
        for (TimeHead head : heads.occupied()) {
            if (head.offset() <= 0 || head.isEmpty()) continue;
            long position = head.position();
            if (position < born || position >= until) continue;
            spawn(head.offset(), item.getUUID(), level, item.getX(), item.getY(), item.getZ(),
                    stack.copy(), Vec3.ZERO, 10, position - born);
        }
    }

    static void spawn(long head, UUID original, ServerLevel level, double x, double y, double z,
                      ItemStack stack, Vec3 motion, int pickupDelay, long age) {
        if (stack.isEmpty() || age < 0 || age >= LIFESPAN) return;
        Map<UUID, UUID> mine = byHead.computeIfAbsent(head, k -> new HashMap<>());
        if (mine.containsKey(original)) return;
        UUID id = UUID.nameUUIDFromBytes((TimeHeads.toTag(head) + "/" + original).getBytes(StandardCharsets.UTF_8));
        if (level.getEntity(id) != null) return;

        ItemEntity item = new ItemEntity(level, x, y, z, stack);
        item.setUUID(id);
        item.addTag(TAG);
        item.setDeltaMovement(motion);
        item.setPickUpDelay(pickupDelay > 0 ? pickupDelay : 10);
        ((ItemEntityAccessor) item).tt$setAge((int) age);

        copies.put(id, new Copy(head, original, level));
        mine.put(original, id);
        if (!ItemFlowJournal.whileApplying(() -> level.addFreshEntity(item))) {
            copies.remove(id);
            mine.remove(original);
        }
    }

    static void discard(long head, UUID original) {
        Map<UUID, UUID> mine = byHead.get(head);
        if (mine == null) return;
        UUID id = mine.get(original);
        if (id == null) return;
        discardCopy(id);
    }

    static void discardFrom(UUID original, long tick) {
        List<UUID> doomed = new ArrayList<>();
        for (Map.Entry<UUID, Copy> entry : copies.entrySet()) {
            Copy copy = entry.getValue();
            if (!copy.original().equals(original)) continue;
            if (Math.max(0, TimeTravel.presentTick - copy.head()) >= tick) doomed.add(entry.getKey());
        }
        for (UUID id : doomed) discardCopy(id);
    }

    static void discardHead(long head) {
        Map<UUID, UUID> mine = byHead.remove(head);
        if (mine == null) return;
        for (UUID id : new ArrayList<>(mine.values())) discardCopy(id);
    }

    public static void clear() {
        List<UUID> all = new ArrayList<>(copies.keySet());
        for (UUID id : all) discardCopy(id);
        copies.clear();
        byHead.clear();
        endTouch();
    }

    private static void discardCopy(UUID id) {
        Copy copy = copies.remove(id);
        if (copy == null) return;
        Map<UUID, UUID> mine = byHead.get(copy.head());
        if (mine != null) mine.remove(copy.original());
        Entity entity = copy.level().getEntity(id);
        if (entity != null && !entity.isRemoved()) entity.discard();
    }
}
