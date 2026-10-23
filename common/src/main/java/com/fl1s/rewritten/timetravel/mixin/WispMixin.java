package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.MobTrailJournal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.entity.spells.wisp.WispEntity", remap = false)
public abstract class WispMixin {

    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$skipOwnerCheck(CallbackInfo ci) {
        if (MobTrailJournal.isPuppeted((Mob) (Object) this)) ci.cancel();
    }

    @Inject(method = "popAndDie", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$skipPop(CallbackInfo ci) {
        if (MobTrailJournal.isPuppeted((Mob) (Object) this)) ci.cancel();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;discard()V"), require = 0)
    private void tt$noDiscard(Entity self) {
        if (!(self instanceof Mob mob && MobTrailJournal.isPuppeted(mob))) {
            self.discard();
        }
    }
}
