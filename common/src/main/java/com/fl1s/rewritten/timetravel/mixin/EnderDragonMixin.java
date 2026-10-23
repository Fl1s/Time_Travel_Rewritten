package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.journal.BossFightJournal;
import com.fl1s.rewritten.timetravel.journal.TrackedEntityJournal;
import com.fl1s.rewritten.timetravel.mob.MobEventJournal;
import com.fl1s.rewritten.timetravel.mob.MobJournal;
import com.fl1s.rewritten.timetravel.mob.MobTrailJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.level.dimension.end.EndDragonFight;
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
import java.util.UUID;

@Mixin(EnderDragon.class)
public abstract class EnderDragonMixin {
    @Unique
    private boolean tt$puppet() {
        EnderDragon self = (EnderDragon) (Object) this;
        return !self.level().isClientSide && TrackedEntityJournal.isPuppeted(self);
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void tt$bar(CallbackInfo ci) {
        EnderDragon self = (EnderDragon) (Object) this;
        if (!(self.level() instanceof ServerLevel level) || self.isRemoved()) return;
        EndDragonFight fight = level.getDragonFight();
        if (fight == null) return;
        EndDragonFightAccessor acc = (EndDragonFightAccessor) fight;
        UUID id = acc.tt$getDragonUUID();
        if (id == null || !(level.getEntity(id) instanceof EnderDragon other) || other.isRemoved()) {
            acc.tt$setDragonUUID(self.getUUID());
        } else if (other != self) {
            return;
        }
        acc.tt$setTicksSinceDragonSeen(0);
        float p = self.getHealth() / self.getMaxHealth();
        acc.tt$getDragonEvent().setProgress(Math.max(0.0F, Math.min(1.0F, p)));
    }

    @Inject(method = "tickDeath", at = @At("HEAD"))
    private void tt$finish(CallbackInfo ci) {
        EnderDragon self = (EnderDragon) (Object) this;
        if (self.level().isClientSide || self.dragonDeathTime != 199) return;
        if (MobEventJournal.deathOf(self.getUUID()) == null) MobJournal.onDragonKilled(self);
    }

    @Redirect(method = "tickDeath", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ExperienceOrb;award(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;I)V"))
    private void tt$xp(ServerLevel level, Vec3 pos, int amount) {
        if (!BossFightJournal.isDrivenDeath((EnderDragon) (Object) this)) ExperienceOrb.award(level, pos, amount);
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
