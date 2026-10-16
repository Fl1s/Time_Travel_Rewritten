package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BaseRailBlock.class)
public interface BaseRailBlockAccessor {
    @Invoker("updateState")
    BlockState tt$updateState(BlockState state, Level level, BlockPos pos, boolean placed);
}