package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.WorldEntityJournal;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityRemoveMixin {

    @Inject(method = "remove", at = @At("HEAD"))
    private void tt$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                || reason == Entity.RemovalReason.CHANGED_DIMENSION) return;
        Entity self = (Entity) (Object) this;
        if (WorldEntityJournal.isTracked(self)) {
            WorldEntityJournal.onEntityDeath(self);
        }
    }
}