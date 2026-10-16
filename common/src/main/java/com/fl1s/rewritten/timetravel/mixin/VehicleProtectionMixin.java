package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.VehicleJournal;
import com.fl1s.rewritten.timetravel.VehicleTrailJournal;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class VehicleProtectionMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void tt$protectReservedVehicle(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!TimeTravel.isLooping) return;
        if (!VehicleJournal.isTracked(self)) return;
        if (VehicleTrailJournal.hasFutureSamples(self.getUUID(), TimeTravel.timelineTick)) {
            cir.setReturnValue(false);
        }
    }
}