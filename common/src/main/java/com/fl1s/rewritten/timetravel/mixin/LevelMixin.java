package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.BlockJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "markAndNotifyBlock", at = @At("HEAD"))
    private void tt$blockChanged(BlockPos pos, LevelChunk chunk, BlockState oldState, BlockState newState, int flags, int recursion, CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level) BlockJournal.onBlockChanged(level, pos, oldState, newState);
    }
}