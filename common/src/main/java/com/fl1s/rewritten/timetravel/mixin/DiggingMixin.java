package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.MobJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.warden.Digging;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Digging.class)
public abstract class DiggingMixin {

    @Inject(method = "stop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;J)V", at = @At("HEAD"))
    private void tt$dug(ServerLevel level, Warden warden, long time, CallbackInfo ci) {
        if (warden.getRemovalReason() == null) MobJournal.onWardenDug(warden);
    }
}
