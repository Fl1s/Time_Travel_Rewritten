package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BaseSpawner.class)
public abstract class BaseSpawnerMixin {
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private void tt$noSpawnInReplay(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        if (TimeTravel.isLooping && ReplayState.isReplaying()) ci.cancel();
    }
}
