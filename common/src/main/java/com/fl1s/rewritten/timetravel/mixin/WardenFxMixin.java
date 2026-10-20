package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.WardenEventJournal;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class WardenFxMixin {

    @Inject(method = "broadcastEntityEvent", at = @At("HEAD"))
    private void tt$wardenEvent(Entity entity, byte state, CallbackInfo ci) {
        if (entity instanceof Warden warden) WardenEventJournal.onEvent(warden, state);
    }

    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I", at = @At("HEAD"))
    private void tt$boomParticles(ParticleOptions type, double x, double y, double z, int count, double xOffset, double yOffset, double zOffset, double speed, CallbackInfoReturnable<Integer> cir) {
        if (type.getType() == ParticleTypes.SONIC_BOOM) {
            WardenEventJournal.onBoomParticle((ServerLevel) (Object) this, x, y, z);
        }
    }
}
