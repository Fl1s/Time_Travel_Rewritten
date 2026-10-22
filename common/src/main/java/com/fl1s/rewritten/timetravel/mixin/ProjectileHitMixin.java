package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.entity.ClonePearlExt;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Projectile.class)
public abstract class ProjectileHitMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$clonePearlPasses(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ClonePearlExt pearl && pearl.tt$isClone() && Clones.is(entity)) {
            cir.setReturnValue(false);
        }
    }
}
