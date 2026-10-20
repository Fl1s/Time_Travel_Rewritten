package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.MobBinding;
import com.fl1s.rewritten.timetravel.mob.MobStateJournal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Warden.class)
public abstract class WardenMixin {

    @Inject(method = "canTargetEntity", at = @At("RETURN"), cancellable = true)
    private void tt$binding(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(entity instanceof LivingEntity target)) return;
        if (!MobBinding.allows((Mob) (Object) this, target)) cir.setReturnValue(false);
    }

    @Inject(method = "increaseAngerAt(Lnet/minecraft/world/entity/Entity;IZ)V", at = @At("RETURN"))
    private void tt$angerUp(Entity entity, int offset, boolean playListeningSound, CallbackInfo ci) {
        MobStateJournal.onChanged((Mob) (Object) this);
    }

    @Inject(method = "clearAnger", at = @At("RETURN"))
    private void tt$angerClear(Entity entity, CallbackInfo ci) {
        MobStateJournal.onChanged((Mob) (Object) this);
    }
}
