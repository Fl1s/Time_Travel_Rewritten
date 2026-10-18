package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.entity.EntityStateJournal;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin {
    @Inject(method = "setItemSlot", at = @At("HEAD"))
    private void tt$before(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        EntityStateJournal.beforeChange((ArmorStand) (Object) this);
    }

    @Inject(method = "setItemSlot", at = @At("RETURN"))
    private void tt$after(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        EntityStateJournal.onChanged((ArmorStand) (Object) this);
    }
}
