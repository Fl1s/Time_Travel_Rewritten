package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.mixin.CompoundContainerAccessor;
import com.fl1s.rewritten.timetravel.mixin.SlotAccessor;
import com.fl1s.rewritten.timetravel.time.HeadScope;
import com.fl1s.rewritten.timetravel.time.TimeContext;
import com.fl1s.rewritten.timetravel.time.TimeHeads;
import com.fl1s.rewritten.timetravel.vehicle.HeadVehicleView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PastContainers {

    private PastContainers() {
    }

    private record SlotKey(String dim, long pos, int slot) {}

    private record Remembered(ItemStack stack, long until) {}

    private static final Map<Long, Map<SlotKey, Remembered>> edits = new HashMap<>();

    public static void onMenuOpened(ServerPlayer player, AbstractContainerMenu menu) {
        if (!TimeTravel.isLooping || player == null || menu == null || Clones.is(player)) return;
        long head = TimeTravel.individualTime ? TimeTravel.headOffsetOf(player) : 0;
        boolean past = TimeTravel.individualTime && head > 0;
        long position = past ? Math.max(0, TimeTravel.presentTick - head) : TimeContext.now();

        Map<Container, Container> wrapped = new IdentityHashMap<>();
        boolean changed = false;
        for (Slot slot : menu.slots) {
            Container original = slot.container;
            if (original instanceof PastContainer) continue;
            Container replacement = wrapped.computeIfAbsent(original, c -> wrap(player, c, head, position, past));
            if (replacement != original) {
                ((SlotAccessor) slot).tt$setContainer(replacement);
                changed = true;
            }
        }
        if (changed) menu.sendAllDataToRemote();
    }

    private static Container wrap(ServerPlayer player, Container container, long head, long position, boolean past) {
        if (container instanceof CompoundContainer compound) {
            CompoundContainerAccessor parts = (CompoundContainerAccessor) compound;
            Container first = wrap(player, parts.tt$first(), head, position, past);
            Container second = wrap(player, parts.tt$second(), head, position, past);
            if (first == parts.tt$first() && second == parts.tt$second()) return container;
            return new CompoundContainer(first, second);
        }
        if (container instanceof Entity entity && HeadVehicleView.isCopy(entity)) {
            HeadVehicleView.diverge(entity);
            return container;
        }
        if (!(container instanceof BlockEntity block) || !(block.getLevel() instanceof ServerLevel level)) return container;
        if (!storage(block) || ExemptBlocks.covers(block)) return container;
        if (!past && !TimeTravel.noDuplication) return container;

        String dim = Levels.canonicalId(level);
        BlockPos pos = block.getBlockPos();
        List<BlockJournal.Entry> later = BlockJournal.containerChangesAfter(dim, pos, position);
        if (!past && later.isEmpty()) return container;

        int size = container.getContainerSize();
        NonNullList<ItemStack> view = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < size; i++) view.set(i, container.getItem(i).copy());
        long[] next = new long[size];
        Arrays.fill(next, PastContainer.NO_CHANGE);
        undo(later, view, next);
        if (past) overlay(head, dim, pos, position, view);
        int[] bound = bindExempt(container, view);
        return new PastContainer(container, player, dim, pos, past ? head : 0, false, view, next, bound);
    }

    private static int[] bindExempt(Container real, NonNullList<ItemStack> view) {
        int[] bound = new int[view.size()];
        Arrays.fill(bound, PastContainer.UNBOUND);
        List<Integer> moved = new ArrayList<>();
        for (int r = 0; r < view.size(); r++) {
            ItemStack stack = real.getItem(r);
            if (!Exempt.is(stack)) continue;
            if (view.get(r).isEmpty() || ItemStack.matches(view.get(r), stack)) {
                view.set(r, stack.copy());
                bound[r] = r;
            } else {
                moved.add(r);
            }
        }
        for (int r : moved) {
            int slot = -1;
            for (int i = 0; i < view.size() && slot < 0; i++) {
                if (view.get(i).isEmpty() && bound[i] == PastContainer.UNBOUND) slot = i;
            }
            if (slot < 0) continue;
            view.set(slot, real.getItem(r).copy());
            bound[slot] = r;
        }
        return bound;
    }

    private static boolean storage(BlockEntity block) {
        return block instanceof Container && !ProcessJournal.isProcess(block) && !(block instanceof JukeboxBlockEntity);
    }

    private static void undo(List<BlockJournal.Entry> later, NonNullList<ItemStack> view, long[] next) {
        int size = view.size();
        for (int k = later.size() - 1; k >= 0; k--) {
            BlockJournal.Entry e = later.get(k);
            List<ItemStack> before = BlockJournal.parseItems(e.oldItems, size);
            List<ItemStack> after = BlockJournal.parseItems(e.newItems, size);
            for (int i = 0; i < size; i++) {
                if (ItemStack.matches(before.get(i), after.get(i))) continue;
                view.set(i, before.get(i).copy());
                next[i] = e.tick;
            }
        }
    }

    private static void overlay(long head, String dim, BlockPos pos, long position, NonNullList<ItemStack> view) {
        Map<SlotKey, Remembered> mine = edits.get(head);
        if (mine == null) return;
        for (int i = 0; i < view.size(); i++) {
            Remembered kept = mine.get(new SlotKey(dim, pos.asLong(), i));
            if (kept != null && kept.until() > position) view.set(i, kept.stack().copy());
        }
    }

    public static boolean tryOpenGhost(ServerPlayer player, BlockPos pos) {
        if (!TimeTravel.individualTime || !TimeTravel.isLooping || player == null || Clones.is(player)) return false;
        long head = TimeTravel.headOffsetOf(player);
        if (head <= 0) return false;
        if (player.isSecondaryUseActive() && !player.getMainHandItem().isEmpty()) return false;

        ServerLevel level = player.serverLevel();
        String dim = Levels.canonicalId(level);
        BlockState past = HeadWorldView.overlayAt(head, dim, pos.asLong());
        if (past == null) return false;
        long position = Math.max(0, TimeTravel.presentTick - head);

        BlockEntity shape = ghostShape(level, pos, past);
        if (shape == null) return false;
        Container container = ghost(player, dim, pos, head, position, ((Container) shape).getContainerSize());

        if (past.getBlock() instanceof ChestBlock && past.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(past));
            BlockState other = HeadWorldView.overlayAt(head, dim, otherPos.asLong());
            if (other != null && other.is(past.getBlock()) && ghostShape(level, otherPos, other) != null
                    && other.getValue(ChestBlock.TYPE) == past.getValue(ChestBlock.TYPE).getOpposite()) {
                Container second = ghost(player, dim, otherPos, head, position, container.getContainerSize());
                container = past.getValue(ChestBlock.TYPE) == ChestType.RIGHT
                        ? new CompoundContainer(container, second) : new CompoundContainer(second, container);
            }
        }

        Container shown = container;
        boolean shulker = shape instanceof ShulkerBoxBlockEntity;
        int size = shown.getContainerSize();
        if (size != 5 && size != 9 && size != 27 && size != 54) return false;
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> menuFor(id, inventory, shown, shulker),
                past.getBlock().getName()));
        return true;
    }

    private static BlockEntity ghostShape(ServerLevel level, BlockPos pos, BlockState past) {
        if (!(past.getBlock() instanceof EntityBlock entityBlock)) return null;
        BlockEntity shape = entityBlock.newBlockEntity(pos, past);
        if (!(shape instanceof BaseContainerBlockEntity) || !storage(shape)) return null;

        HeadScope.push(0);
        try {
            BlockState real = level.getBlockState(pos);
            if (real.is(past.getBlock()) && level.getBlockEntity(pos) instanceof Container) return null;
        } finally {
            HeadScope.pop();
        }
        return shape;
    }

    private static Container ghost(ServerPlayer player, String dim, BlockPos pos, long head, long position, int size) {
        List<BlockJournal.Entry> all = BlockJournal.containerChangesAfter(dim, pos, Long.MIN_VALUE);
        BlockJournal.Entry before = null;
        BlockJournal.Entry after = null;
        for (BlockJournal.Entry e : all) {
            if (e.tick <= position) before = e;
            else if (after == null) after = e;
        }
        NonNullList<ItemStack> view = NonNullList.withSize(size, ItemStack.EMPTY);
        String items = before != null ? before.newItems : after != null ? after.oldItems : null;
        if (items != null) {
            List<ItemStack> parsed = BlockJournal.parseItems(items, size);
            for (int i = 0; i < size; i++) view.set(i, parsed.get(i).copy());
        }

        long[] next = new long[size];
        Arrays.fill(next, PastContainer.FOREVER);
        for (BlockJournal.Entry e : all) {
            if (e.tick <= position) continue;
            List<ItemStack> was = BlockJournal.parseItems(e.oldItems, size);
            List<ItemStack> became = BlockJournal.parseItems(e.newItems, size);
            for (int i = 0; i < size; i++) {
                if (next[i] == PastContainer.FOREVER && !ItemStack.matches(was.get(i), became.get(i))) next[i] = e.tick;
            }
        }
        overlay(head, dim, pos, position, view);
        int[] bound = new int[size];
        Arrays.fill(bound, PastContainer.UNBOUND);
        return new PastContainer(new SimpleContainer(size), player, dim, pos, head, true, view, next, bound);
    }

    private static AbstractContainerMenu menuFor(int id, Inventory inventory, Container container, boolean shulker) {
        return switch (container.getContainerSize()) {
            case 5 -> new HopperMenu(id, inventory, container);
            case 9 -> new DispenserMenu(id, inventory, container);
            case 54 -> ChestMenu.sixRows(id, inventory, container);
            default -> shulker ? new ShulkerBoxMenu(id, inventory, container) : ChestMenu.threeRows(id, inventory, container);
        };
    }

    static void remember(long head, String dim, BlockPos pos, int slot, ItemStack stack, long until) {
        if (head <= 0) return;
        edits.computeIfAbsent(head, k -> new HashMap<>()).put(new SlotKey(dim, pos.asLong(), slot), new Remembered(stack, until));
    }

    private static PastContainer partOf(Container container, int slot, int[] local) {
        if (container instanceof PastContainer past) {
            local[0] = slot;
            return past;
        }
        if (container instanceof CompoundContainer compound) {
            CompoundContainerAccessor parts = (CompoundContainerAccessor) compound;
            int firstSize = parts.tt$first().getContainerSize();
            return slot < firstSize ? partOf(parts.tt$first(), slot, local)
                    : partOf(parts.tt$second(), slot - firstSize, local);
        }
        return null;
    }

    public static boolean lockedTake(Container container, int slot) {
        int[] local = new int[1];
        PastContainer past = partOf(container, slot, local);
        return past != null && past.lockedTake(local[0]);
    }

    public static boolean lockedPlace(Container container, int slot, ItemStack stack) {
        int[] local = new int[1];
        PastContainer past = partOf(container, slot, local);
        return past != null && past.lockedPlace(local[0], stack);
    }

    public static void dropHead(long head) {
        edits.remove(head);
    }

    public static void shiftHeads(long delta) {
        TimeHeads.shiftKeys(edits, delta);
        if (TimeTravel.server == null) return;
        Set<PastContainer> open = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) {
            for (Slot slot : player.containerMenu.slots) collect(slot.container, open);
        }
        for (PastContainer past : open) past.shift(delta);
    }

    private static void collect(Container container, Set<PastContainer> out) {
        if (container instanceof PastContainer past) {
            out.add(past);
        } else if (container instanceof CompoundContainer compound) {
            CompoundContainerAccessor parts = (CompoundContainerAccessor) compound;
            collect(parts.tt$first(), out);
            collect(parts.tt$second(), out);
        }
    }

    public static void clear() {
        edits.clear();
    }
}
