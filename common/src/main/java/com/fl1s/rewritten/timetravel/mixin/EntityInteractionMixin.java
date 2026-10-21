package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.clone.HeadInteraction;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityInteractionMixin {

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void tt$blockCrossHeadPush(Entity other, CallbackInfo ci) {
        if (!HeadInteraction.allows((Entity) (Object) this, other)) ci.cancel();
    }

    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    private void tt$blockCrossHeadCollision(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (!HeadInteraction.allows((Entity) (Object) this, other)) cir.setReturnValue(false);
    }
}
