package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Removal;
import com.fl1s.rewritten.timetravel.vehicle.HeadVehicleView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VehicleEntity.class)
public abstract class VehicleEntityMixin {

    @Inject(method = "hurt", at = @At("HEAD"))
    private void tt$divergeOnHit(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (source.getEntity() instanceof ServerPlayer player && !Clones.is(player)) {
            HeadVehicleView.diverge((VehicleEntity) (Object) this);
        }
    }

    @Redirect(method = "hurt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/VehicleEntity;destroy(Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void tt$copiesBreakClean(VehicleEntity self, DamageSource source) {
        if (TimeTravel.noDuplication && HeadVehicleView.isCopy(self)) {
            self.ejectPassengers();
            Removal.discardWithoutDrops(self);
            return;
        }
        ((VehicleEntityAccessor) self).tt$destroy(source);
    }
}
