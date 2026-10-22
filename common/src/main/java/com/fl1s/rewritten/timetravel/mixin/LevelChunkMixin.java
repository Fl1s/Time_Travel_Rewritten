package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.HeadWorldView;
import com.fl1s.rewritten.timetravel.time.HeadScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void tt$headBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!HeadScope.active()) return;
        BlockState state = HeadWorldView.resolve(((LevelChunk) (Object) this).getLevel(), pos);
        if (state != null) cir.setReturnValue(state);
    }

    @Inject(method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
            at = @At("HEAD"), cancellable = true)
    private void tt$headFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (!HeadScope.active()) return;
        BlockState state = HeadWorldView.resolve(((LevelChunk) (Object) this).getLevel(), pos);
        if (state != null) cir.setReturnValue(state.getFluidState());
    }
}
