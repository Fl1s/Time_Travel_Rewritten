package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.ArmorStandJournal;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityRemoveMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void tt$remove(Entity.RemovalReason reason, CallbackInfo ci) {
        ArmorStandJournal.onRemoved((Entity) (Object) this, reason);
    }
}
