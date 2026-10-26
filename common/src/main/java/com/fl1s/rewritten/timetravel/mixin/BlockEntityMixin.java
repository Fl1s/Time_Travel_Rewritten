package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.BlockEntityJournal;
import com.fl1s.rewritten.timetravel.journal.ExemptBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
            ExemptBlocks.onBlockEntity((BlockEntity) (Object) this);
            BlockEntityJournal.onBlockEntityInit(serverLevel, (BlockEntity) (Object) this);
        }
    }

    @Inject(method = "loadWithComponents", at = @At("TAIL"))
    private void tt$loaded(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        ExemptBlocks.onBlockEntity((BlockEntity) (Object) this);
    }

    @Inject(method = "setChanged", at = @At("HEAD"))
    private void tt$setChanged(CallbackInfo ci) {
        ExemptBlocks.onBlockEntity((BlockEntity) (Object) this);
        BlockEntityJournal.onChanged((BlockEntity) (Object) this);
    }
}