package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("attackAnim")
    void tt$setAttackAnim(float v);

    @Accessor("oAttackAnim")
    void tt$setOAttackAnim(float v);

    @Accessor("useItem")
    ItemStack tt$getUseItem();

    @Accessor("useItem")
    void tt$setUseItem(ItemStack stack);

    @Accessor("useItemRemaining")
    int tt$getUseItemRemaining();

    @Accessor("useItemRemaining")
    void tt$setUseItemRemaining(int v);

    @Invoker("setLivingEntityFlag")
    void tt$setLivingEntityFlag(int flag, boolean value);
}
