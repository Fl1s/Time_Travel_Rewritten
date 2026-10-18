package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.entity.EntityStateJournal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystal.class)
public abstract class EndCrystalMixin {
    @Inject(method = {"setBeamTarget(Lnet/minecraft/core/BlockPos;)V", "setShowBottom(Z)V"}, at = @At("HEAD"))
    private void tt$before(CallbackInfo ci) {
        EntityStateJournal.beforeChange((Entity) (Object) this);
    }

    @Inject(method = {"setBeamTarget(Lnet/minecraft/core/BlockPos;)V", "setShowBottom(Z)V"}, at = @At("RETURN"))
    private void tt$after(CallbackInfo ci) {
        EntityStateJournal.onChanged((Entity) (Object) this);
    }
}
