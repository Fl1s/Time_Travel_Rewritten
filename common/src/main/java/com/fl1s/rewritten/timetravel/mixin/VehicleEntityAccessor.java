package com.fl1s.rewritten.timetravel.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VehicleEntity.class)
public interface VehicleEntityAccessor {
    @Invoker("destroy")
    void tt$destroy(DamageSource source);
}
