package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.entity.ClonePearlExt;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrownEnderpearl.class)
public abstract class ClonePearlMixin implements ClonePearlExt {
    @Unique
    private boolean tt$clone;

    @Override
    public void tt$markClone() {
        tt$clone = true;
    }

    @Override
    public boolean tt$isClone() {
        return tt$clone;
    }

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$hit(HitResult result, CallbackInfo ci) {
        if (!tt$clone) return;
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        if (self.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.PORTAL, self.getX(), self.getY(), self.getZ(), 32, 0.2, 0.2, 0.2, 0.8);
        }
        if (!self.isRemoved()) self.discard();
        ci.cancel();
    }

    @ModifyVariable(method = "tick", at = @At("STORE"), ordinal = 0, require = 0)
    private Entity tt$owner(Entity owner) {
        return tt$clone ? null : owner;
    }
}
