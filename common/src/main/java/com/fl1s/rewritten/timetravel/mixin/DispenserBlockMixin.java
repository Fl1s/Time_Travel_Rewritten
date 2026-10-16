package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.BlockJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DispenserBlock.class)
public class DispenserBlockMixin {
    private static final ThreadLocal<List<ItemStack>> TT_BEFORE = new ThreadLocal<>();

    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void tt$head(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (level.getBlockEntity(pos) instanceof Container c) TT_BEFORE.set(BlockJournal.copyOf(c));
    }

    @Inject(method = "dispenseFrom", at = @At("RETURN"))
    private void tt$return(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        List<ItemStack> before = TT_BEFORE.get();
        TT_BEFORE.remove();
        if (before != null) BlockJournal.recordContainerChange(level, pos, before);
    }
}
