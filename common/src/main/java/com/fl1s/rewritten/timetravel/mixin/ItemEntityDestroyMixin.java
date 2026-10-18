package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.ItemFlowJournal;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class ItemEntityDestroyMixin {
    @Inject(method = "hurt", at = @At("RETURN"))
    private void tt$afterHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        ItemEntity self = (ItemEntity) (Object) this;
        if (!self.isRemoved()) return;
        ItemFlowJournal.recordDestroy(self);
    }
}
