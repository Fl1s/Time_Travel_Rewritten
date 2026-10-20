package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SculkShriekerBlockEntity.class)
public abstract class ShriekerMixin {

    @Inject(method = "tryShriek", at = @At("HEAD"), cancellable = true)
    private void tt$noCloneShriek(ServerLevel level, ServerPlayer player, CallbackInfo ci) {
        if (player != null && Clones.is(player)) ci.cancel();
    }
}
