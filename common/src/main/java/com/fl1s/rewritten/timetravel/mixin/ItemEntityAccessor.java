package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("age") int tt$getAge();
    @Accessor("age") void tt$setAge(int age);
    @Accessor("pickupDelay") int tt$getPickupDelay();
}