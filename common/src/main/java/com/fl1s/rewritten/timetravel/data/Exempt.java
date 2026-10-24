package com.fl1s.rewritten.timetravel.data;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Exempt {
    private Exempt() {
    }

    public static final String TAG = "tt_exempt";
    public static final TagKey<EntityType<?>> ENTITY_TYPES = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "exempt"));
    public static final TagKey<Item> ITEMS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "exempt"));

    private static final int HORSE_SADDLE = 400;
    private static final int HORSE_CHEST = 500;
    private static final int HORSE_CHEST_SLOTS = 15;
    private static final Set<String> TEMPLATES = Set.of("Offers");

    public static boolean is(Entity entity) {
        if (entity == null || entity instanceof Player) return false;
        if (entity.getTags().contains(TAG) || entity.getType().is(ENTITY_TYPES)) return true;
        if (entity instanceof AbstractArrow arrow) return is(arrow.getPickupItemStackOrigin());
        return entity instanceof ItemEntity item && is(item.getItem());
    }

    public static boolean is(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(ITEMS)) return true;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(TAG);
    }

    public static boolean byType(Entity entity) {
        if (entity == null) return false;
        if (entity.getType().is(ENTITY_TYPES)) return true;
        return entity instanceof ItemEntity item && item.getItem().is(ITEMS);
    }

    public static void mark(ItemStack stack, boolean exempt) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (exempt) tag.putBoolean(TAG, true);
            else tag.remove(TAG);
        });
    }

    public static void mark(Entity entity, boolean exempt) {
        if (entity instanceof ItemEntity item) {
            ItemStack stack = item.getItem().copy();
            mark(stack, exempt);
            item.setItem(stack);
        }
        if (exempt) entity.addTag(TAG);
        else entity.removeTag(TAG);
    }

    public static void strip(CompoundTag tag) {
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (TEMPLATES.contains(key)) continue;
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                if (!isItem(compound)) strip(compound);
                else if (isExemptItem(compound)) tag.remove(key);
            } else if (child instanceof ListTag list) {
                strip(list);
            }
        }
    }

    private static void strip(ListTag list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!(list.get(i) instanceof CompoundTag compound)) continue;
            if (!isItem(compound)) strip(compound);
            else if (!isExemptItem(compound)) continue;
            else if (compound.contains("Slot")) list.remove(i);
            else list.set(i, new CompoundTag());
        }
    }

    private static boolean isItem(CompoundTag tag) {
        return tag.contains("id", Tag.TAG_STRING) && tag.contains("count", Tag.TAG_ANY_NUMERIC);
    }

    private static boolean isExemptItem(CompoundTag tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("id"));
        if (id != null && BuiltInRegistries.ITEM.get(id).builtInRegistryHolder().is(ITEMS)) return true;
        return tag.getCompound("components").getCompound("minecraft:custom_data").contains(TAG);
    }

    public static ItemStack masked(ItemStack stack) {
        return is(stack) ? ItemStack.EMPTY : stack;
    }

    public static void mask(List<ItemStack> stacks) {
        for (int i = 0; i < stacks.size(); i++) {
            if (is(stacks.get(i))) stacks.set(i, ItemStack.EMPTY);
        }
    }

    public static void put(Container container, int slot, ItemStack target, List<ItemStack> displaced) {
        ItemStack wanted = masked(target);
        ItemStack current = container.getItem(slot);
        if (is(current)) {
            if (wanted.isEmpty()) return;
            displaced.add(current);
        }
        container.setItem(slot, wanted.copy());
    }

    public static void rehome(Container container, List<ItemStack> displaced, Level level, Vec3 at) {
        List<ItemStack> homeless = new ArrayList<>();
        for (ItemStack stack : displaced) {
            int free = -1;
            for (int i = 0; i < container.getContainerSize() && free < 0; i++) {
                if (container.getItem(i).isEmpty() && container.canPlaceItem(i, stack)) free = i;
            }
            if (free >= 0) container.setItem(free, stack);
            else homeless.add(stack);
        }
        drop(level, at, homeless);
    }

    public static void eject(Container container, Level level, Vec3 at) {
        List<ItemStack> out = new ArrayList<>();
        take(container, out);
        drop(level, at, out);
    }

    public static void eject(Entity entity) {
        if (entity == null || is(entity)) return;
        List<ItemStack> out = new ArrayList<>();
        if (entity instanceof Container container) take(container, out);
        if (entity instanceof InventoryCarrier carrier) take(carrier.getInventory(), out);
        if (entity instanceof AbstractHorse horse) {
            take(horse.getSlot(HORSE_SADDLE), out);
            for (int i = 0; i < HORSE_CHEST_SLOTS; i++) take(horse.getSlot(HORSE_CHEST + i), out);
        }
        if (entity instanceof ItemFrame frame && is(frame.getItem())) {
            out.add(frame.getItem().copy());
            frame.setItem(ItemStack.EMPTY, false);
        }
        if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!is(stack)) continue;
                out.add(stack.copy());
                living.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        drop(entity.level(), entity.position(), out);
    }

    private static void take(SlotAccess slot, List<ItemStack> out) {
        ItemStack stack = slot.get();
        if (!is(stack)) return;
        out.add(stack.copy());
        slot.set(ItemStack.EMPTY);
    }

    private static void take(Container container, List<ItemStack> out) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (is(container.getItem(i))) out.add(container.removeItemNoUpdate(i));
        }
    }

    private static void drop(Level level, Vec3 at, List<ItemStack> stacks) {
        if (level == null || at == null) return;
        for (ItemStack stack : stacks) Containers.dropItemStack(level, at.x, at.y, at.z, stack);
    }
}
