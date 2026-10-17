package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.EntityStateJournal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {
    @Inject(method = {"setItem(Lnet/minecraft/world/item/ItemStack;)V", "setRotation(I)V"}, at = @At("HEAD"))
    private void tt$before(CallbackInfo ci) {
        EntityStateJournal.beforeChange((Entity) (Object) this);
    }

    @Inject(method = {"setItem(Lnet/minecraft/world/item/ItemStack;)V", "setRotation(I)V"}, at = @At("RETURN"))
    private void tt$after(CallbackInfo ci) {
        EntityStateJournal.onChanged((Entity) (Object) this);
    }
}
