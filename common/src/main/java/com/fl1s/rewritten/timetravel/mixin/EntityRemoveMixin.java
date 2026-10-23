package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.clone.CloneTracks;
import com.fl1s.rewritten.timetravel.entity.ClonePearlExt;
import com.fl1s.rewritten.timetravel.entity.EntityStateJournal;
import com.fl1s.rewritten.timetravel.journal.ProjectileJournal;
import com.fl1s.rewritten.timetravel.journal.TrackedEntityJournal;
import com.fl1s.rewritten.timetravel.mob.MobSpawnJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleJournal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityRemoveMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void tt$remove(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.isRemoved()) return;
        try {
            EntityStateJournal.onRemoved(self, reason);
            VehicleJournal.onRemoved(self, reason);
            MobSpawnJournal.onRemoved(self, reason);
            if (reason.shouldDestroy()) ProjectileJournal.onRemoved(self);
            if (reason.shouldDestroy()) TrackedEntityJournal.onRemoved(self);
            if (self instanceof FishingHook hook && reason.shouldDestroy()) CloneTracks.onHookRemoved(hook);
            if (TimeTravel.DEBUG && self instanceof ClonePearlExt pearl && pearl.tt$isClone()) {
                TimeTravel.TIMELINE_LOGGER.info("[TT-PEARL] removed reason={} age={} pos={}", reason, self.tickCount, self.position(), new Throwable("pearl removal"));
            }
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Entity removal hook failed", t);
        }
    }
}
