package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DropperBlock.class)
public class DropperBlockMixin {
    private static final ThreadLocal<List<net.minecraft.world.item.ItemStack>> TT_BEFORE = new ThreadLocal<>();

    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void tt$head(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (BlockJournal.shouldSkipAutomation()) {
            ci.cancel();
            return;
        }
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) TT_BEFORE.set(BlockJournal.copyOf(c));
    }

    @Inject(method = "dispenseFrom", at = @At("RETURN"))
    private void tt$return(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        List<net.minecraft.world.item.ItemStack> before = TT_BEFORE.get();
        TT_BEFORE.remove();
        if (before != null) BlockJournal.recordContainerChange(level, pos, before);
    }
}
