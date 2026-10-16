package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.ItemFlowJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDropMixin {

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void tt$onDropHead(ServerLevel level, DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (ItemFlowJournal.isSuppressDrop()) {
            ci.cancel();
            return;
        }
        ItemFlowJournal.beginDeathCapture(self);
    }

    @Inject(method = "dropAllDeathLoot", at = @At("RETURN"))
    private void tt$onDropReturn(ServerLevel level, DamageSource source, CallbackInfo ci) {
        ItemFlowJournal.endDeathCapture();
    }
}
