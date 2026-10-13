package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.MobBinding;
import com.fl1s.rewritten.timetravel.MobJournal;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class MobHurtMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void tt$hurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (MobJournal.shouldShield(self, source)) {
            self.level().broadcastEntityEvent(self, (byte) 2);
            cir.setReturnValue(false);
            return;
        }
        if (self instanceof Mob mob) {
            MobBinding.onHurt(mob, source.getEntity());
            MobJournal.track(mob, source.getEntity());
        }
    }
}
