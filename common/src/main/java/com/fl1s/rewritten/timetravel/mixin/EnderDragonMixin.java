package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.mob.MobJournal;
import com.fl1s.rewritten.timetravel.mob.MobTrailJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(EnderDragon.class)
public abstract class EnderDragonMixin {
    @Unique
    private boolean tt$puppet() {
        EnderDragon self = (EnderDragon) (Object) this;
        return !self.level().isClientSide && MobTrailJournal.isPuppeted(self);
    }

    @Inject(method = "tickDeath", at = @At("HEAD"))
    private void tt$finish(CallbackInfo ci) {
        EnderDragon self = (EnderDragon) (Object) this;
        if (self.dragonDeathTime == 199) MobJournal.onDragonKilled(self);
    }

    @Redirect(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/boss/enderdragon/phases/DragonPhaseInstance;doServerTick()V"))
    private void tt$phaseTick(DragonPhaseInstance phase) {
        if (!tt$puppet()) phase.doServerTick();
    }

    @Redirect(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/boss/enderdragon/phases/DragonPhaseInstance;getFlyTargetLocation()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 tt$flyTarget(DragonPhaseInstance phase) {
        return tt$puppet() ? null : phase.getFlyTargetLocation();
    }

    @Inject(method = "checkWalls", at = @At("HEAD"), cancellable = true)
    private void tt$walls(AABB area, CallbackInfoReturnable<Boolean> cir) {
        if (tt$puppet()) cir.setReturnValue(false);
    }

    @Inject(method = "knockBack(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V", at = @At("HEAD"))
    private void tt$knock(ServerLevel level, List<Entity> targets, CallbackInfo ci) {
        if (tt$puppet()) targets.removeIf(e -> Clones.is(e));
    }

    @Inject(method = "hurt(Ljava/util/List;)V", at = @At("HEAD"))
    private void tt$hurtList(List<Entity> entities, CallbackInfo ci) {
        if (tt$puppet()) entities.removeIf(e -> Clones.is(e));
    }
}
