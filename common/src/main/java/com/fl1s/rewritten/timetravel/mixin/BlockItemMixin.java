package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.ExemptBlocks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"))
    private void tt$beginPlacing(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!context.getLevel().isClientSide) ExemptBlocks.beginPlacing(context.getItemInHand());
    }

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"))
    private void tt$endPlacing(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!context.getLevel().isClientSide) ExemptBlocks.endPlacing();
    }
}
