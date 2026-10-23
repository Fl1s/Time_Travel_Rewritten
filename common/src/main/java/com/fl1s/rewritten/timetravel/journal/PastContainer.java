package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class PastContainer implements Container {

    static final long NO_CHANGE = Long.MAX_VALUE;
    static final long FOREVER = Long.MAX_VALUE - 1;

    private final Container real;
    private final ServerPlayer viewer;
    private final String dim;
    private final BlockPos pos;
    private long head;
    private final boolean ghost;
    private final NonNullList<ItemStack> view;
    private final NonNullList<ItemStack> committed;
    private final long[] nextChange;

    PastContainer(Container real, ServerPlayer viewer, String dim, BlockPos pos, long head, boolean ghost,
                  NonNullList<ItemStack> view, long[] nextChange) {
        this.real = real;
        this.viewer = viewer;
        this.dim = dim;
        this.pos = pos.immutable();
        this.head = head;
        this.ghost = ghost;
        this.view = view;
        this.nextChange = nextChange;
        this.committed = NonNullList.withSize(view.size(), ItemStack.EMPTY);
        for (int i = 0; i < view.size(); i++) committed.set(i, view.get(i).copy());
    }

    void shift(long delta) {
        if (head > 0) head += delta;
    }

    private boolean historied(int slot) {
        return slot >= 0 && slot < nextChange.length && nextChange[slot] != NO_CHANGE;
    }

    public boolean lockedTake(int slot) {
        return TimeTravel.noDuplication && historied(slot);
    }

    public boolean lockedPlace(int slot) {
        return ghost || historied(slot);
    }

    @Override
    public int getContainerSize() {
        return view.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : view) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < view.size() ? view.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (lockedTake(slot)) return ItemStack.EMPTY;
        ItemStack taken = ContainerHelper.removeItem(view, slot, amount);
        if (!taken.isEmpty()) setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (lockedTake(slot)) return ItemStack.EMPTY;
        ItemStack taken = ContainerHelper.takeItem(view, slot);
        setChanged();
        return taken;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= view.size()) return;
        stack.limitSize(getMaxStackSize(stack));
        view.set(slot, stack);
        setChanged();
    }

    @Override
    public void setChanged() {
        boolean touchedReal = false;
        for (int i = 0; i < view.size(); i++) {
            ItemStack now = view.get(i);
            ItemStack before = committed.get(i);
            if (ItemStack.matches(now, before)) continue;

            if (refused(i, now, before)) {
                view.set(i, before.copy());
                continue;
            }
            committed.set(i, now.copy());
            if (ghost || historied(i)) {
                PastContainers.remember(head, dim, pos, i, now.copy(), nextChange[i]);
            } else {
                real.setItem(i, now.copy());
                touchedReal = true;
            }
        }
        if (touchedReal) real.setChanged();
    }

    private boolean refused(int slot, ItemStack now, ItemStack before) {
        boolean grew = !now.isEmpty() && (before.isEmpty() || now.getCount() > before.getCount()
                || !ItemStack.isSameItemSameComponents(now, before));
        if (grew && lockedPlace(slot)) {
            ItemStack extra = ItemStack.isSameItemSameComponents(now, before)
                    ? now.copyWithCount(now.getCount() - before.getCount()) : now.copy();
            if (viewer != null && !extra.isEmpty()) viewer.getInventory().placeItemBackInInventory(extra);
            return true;
        }
        return !grew && lockedTake(slot);
    }

    @Override
    public boolean stillValid(Player player) {
        if (!ghost) return real.stillValid(player);
        if (player instanceof ServerPlayer sp && TimeTravel.headOffsetOf(sp) != head) return false;
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !lockedPlace(slot) && (ghost || real.canPlaceItem(slot, stack));
    }

    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return !lockedTake(slot) && (ghost || real.canTakeItem(target, slot, stack));
    }

    @Override
    public int getMaxStackSize() {
        return real.getMaxStackSize();
    }

    @Override
    public void clearContent() {
    }
}
