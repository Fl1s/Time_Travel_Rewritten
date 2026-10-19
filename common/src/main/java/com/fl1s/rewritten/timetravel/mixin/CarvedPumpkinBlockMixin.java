package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CarvedPumpkinBlock.class)
public abstract class CarvedPumpkinBlockMixin {

    @Inject(method = "trySpawnGolem", at = @At("HEAD"), cancellable = true)
    private void tt$enter(Level level, BlockPos pos, CallbackInfo ci) {
        if (BlockJournal.isApplying() || BlockJournal.isReplayingLive()) {
            ci.cancel();
            return;
        }
        BlockJournal.enterBuild();
    }

    @Inject(method = "trySpawnGolem", at = @At("RETURN"))
    private void tt$exit(Level level, BlockPos pos, CallbackInfo ci) {
        if (BlockJournal.isApplying() || BlockJournal.isReplayingLive()) return;
        BlockJournal.exitBuild();
    }
}
