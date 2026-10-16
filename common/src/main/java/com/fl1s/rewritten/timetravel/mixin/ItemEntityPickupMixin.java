package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.ItemFlowJournal;
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

    @Unique private ItemStack tt$prePickup = ItemStack.EMPTY;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void tt$onPickupHead(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        tt$prePickup = self.getItem().copy();
    }

    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void tt$onPickupReturn(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (!tt$prePickup.isEmpty() && self.getItem().isEmpty()) {
            ItemFlowJournal.recordPickup(self, player, tt$prePickup);
        }
        tt$prePickup = ItemStack.EMPTY;
    }
}
