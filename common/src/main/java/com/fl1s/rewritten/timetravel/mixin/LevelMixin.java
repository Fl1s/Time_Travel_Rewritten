package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.journal.ExemptBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void tt$setBlockHead(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel level) {
            ExemptBlocks.beforeSet(level, pos, state);
            BlockJournal.beginSet(level, pos, state);
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void tt$setBlockReturn(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel level) {
            BlockJournal.endSet(level, pos, cir.getReturnValueZ());
            ExemptBlocks.afterSet(level, pos);
        }
    }

    @Inject(method = "markAndNotifyBlock", at = @At("HEAD"))
    private void tt$blockChanged(BlockPos pos, LevelChunk chunk, BlockState oldState, BlockState newState, int flags, int recursion, CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level) BlockJournal.onBlockChanged(level, pos, oldState, newState);
    }
}
