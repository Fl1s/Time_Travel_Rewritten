package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.MobBinding;
import com.fl1s.rewritten.timetravel.MobJournal;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobTargetMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void tt$setTarget(LivingEntity target, CallbackInfo ci) {
        if (target == null) return;
        Mob mob = (Mob) (Object) this;
        if (!MobBinding.allows(mob, target)) {
            ci.cancel();
            return;
        }
        MobJournal.track(mob, target);
    }

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void tt$recheckTarget(CallbackInfo ci) {
        Mob mob = (Mob) (Object) this;
        LivingEntity target = mob.getTarget();
        if (target != null && !MobBinding.allows(mob, target)) {
            mob.setTarget(null);
        }
    }

    @Inject(method = "serverAiStep", at = @At("RETURN"))
    private void tt$recheckBrain(CallbackInfo ci) {
        Mob mob = (Mob) (Object) this;
        if (mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).ifPresent(t -> {
                if (!MobBinding.allows(mob, t)) {
                    mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                }
            });
        }
    }
}
