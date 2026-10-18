package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.BlockEntityJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void tt$setLevel(Level level, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            BlockEntityJournal.onBlockEntityInit(serverLevel, (BlockEntity) (Object) this);
        }
    }

    @Inject(method = "setChanged", at = @At("HEAD"))
    private void tt$setChanged(CallbackInfo ci) {
        BlockEntityJournal.onChanged((BlockEntity) (Object) this);
    }
}