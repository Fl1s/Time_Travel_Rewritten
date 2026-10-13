package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.BlockJournal;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void tt$schedule(ScheduledTick<T> tick, CallbackInfo ci) {
        if (BlockJournal.isApplying()) ci.cancel();
    }
}