package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.ExemptBlocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.loot.LootParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockDropsMixin {

    @Inject(method = "getDrops", at = @At("RETURN"))
    private void tt$exemptDrops(LootParams.Builder params, CallbackInfoReturnable<List<ItemStack>> cir) {
        ExemptBlocks.markDrops(params, cir.getReturnValue());
    }
}
