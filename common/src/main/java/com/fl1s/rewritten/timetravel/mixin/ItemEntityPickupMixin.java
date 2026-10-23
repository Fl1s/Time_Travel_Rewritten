package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.HeadItemView;
import com.fl1s.rewritten.timetravel.journal.ItemFlowJournal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {
    @Unique
    private int tt$before;

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void tt$head(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level().isClientSide) return;
        if (!HeadItemView.canTake(self, player)) {
            ci.cancel();
            return;
        }
        tt$before = self.getItem().getCount();
        HeadItemView.beginTouch(self, player);
    }

    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void tt$return(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level().isClientSide) return;
        try {
            ItemStack stack = self.getItem();
            int taken = self.isRemoved() ? tt$before : tt$before - stack.getCount();
            if (taken > 0) ItemFlowJournal.recordPickup(self, player, stack.copyWithCount(taken));
        } finally {
            HeadItemView.endTouch();
        }
    }
}
