package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class CloneEnvDamageMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void tt$ignoreCloneEnvDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!TimeTravel.isLooping) return;
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayer sp) || !Clones.is(sp)) return;
        if (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.FALL)
                || source.is(DamageTypes.FLY_INTO_WALL) || source.is(DamageTypes.CRAMMING)) {
            cir.setReturnValue(false);
        }
    }
}
