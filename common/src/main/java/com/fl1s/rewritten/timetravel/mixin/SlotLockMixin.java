package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.PastContainers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class SlotLockMixin {

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void tt$lockedPlace(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        Slot self = (Slot) (Object) this;
        if (PastContainers.lockedPlace(self.container, self.getContainerSlot(), stack)) cir.setReturnValue(false);
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void tt$lockedPickup(Player player, CallbackInfoReturnable<Boolean> cir) {
        Slot self = (Slot) (Object) this;
        if (PastContainers.lockedTake(self.container, self.getContainerSlot())) cir.setReturnValue(false);
    }
}
