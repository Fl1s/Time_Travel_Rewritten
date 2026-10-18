package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.entity.EntityJournal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void tt$pre(CallbackInfo ci) {
        if (((Entity) (Object) this).getTags().contains(EntityJournal.TAG)) EntityJournal.suppress = true;
    }

    @Inject(method = "explode", at = @At("RETURN"))
    private void tt$post(CallbackInfo ci) {
        EntityJournal.suppress = false;
    }
}