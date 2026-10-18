package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.GhostFx;
import com.fl1s.rewritten.timetravel.MobBinding;
import com.fl1s.rewritten.timetravel.MobJournal;
import com.fl1s.rewritten.timetravel.MobSpawnJournal;
import com.fl1s.rewritten.timetravel.MobTrailJournal;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.util.DoomedFeedback;
import com.fl1s.rewritten.timetravel.util.Ghosts;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class MobHurtMixin {

    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true)
    private float tt$amount(float amount, DamageSource source) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (MobJournal.isApplying()) return amount;
        if (MobJournal.isDoomed(self)) {
            float cur = self.getHealth();
            if (cur <= 1.0f) return 0.0f;
            return Math.min(amount, cur - 1.0f);
        }
        float v = tt$crit(self, source, amount);
        if (self instanceof Mob mob && MobTrailJournal.isReplaying() && MobTrailJournal.isPuppeted(mob)) {
            float cur = mob.getHealth();
            if (cur <= 1.0f) return 0.0f;
            return Math.min(v, cur - 1.0f);
        }
        return v;
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void tt$hurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (MobJournal.isApplying()) return;

        if (self instanceof Mob om
                && source.getEntity() != null
                && Ghosts.is(source.getEntity())
                && MobSpawnJournal.isOutOfTime(om.getUUID())
                && !MobTrailJournal.isPuppeted(om)) {
            cir.setReturnValue(false);
            return;
        }

        boolean doomed = MobJournal.isDoomed(self);
        boolean replayPuppet = self instanceof Mob mob
                && MobTrailJournal.isReplaying()
                && MobTrailJournal.isPuppeted(mob);
        if ((doomed || replayPuppet) && self.getHealth() - amount <= 1.0f) {
            Entity attacker = source.getEntity();
            if (attacker instanceof ServerPlayer sp && !Ghosts.is(sp)
                    && self.level() instanceof ServerLevel sl) {
                DoomedFeedback.tryShow(sp, sl, doomed);
            }
        }

        if (tt$cloneBlocks(self, source)) {
            cir.setReturnValue(false);
            return;
        }

        if (self instanceof ServerPlayer sp && Ghosts.is(sp) && MobJournal.isDoomed(source.getEntity())) {
            self.level().broadcastEntityEvent(self, (byte) 2);
            cir.setReturnValue(false);
            return;
        }

        if (self instanceof Mob mob) {
            MobBinding.onHurt(mob, source.getEntity());
        }
    }

    @Unique
    private static float tt$crit(LivingEntity victim, DamageSource src, float amount) {
        Entity a = src.getEntity();
        if (a == null || src.getDirectEntity() != a || a == victim) return amount;
        if (!(a instanceof ServerPlayer sp) || !Ghosts.is(a)) return amount;
        if (!src.is(DamageTypes.PLAYER_ATTACK) || sp.isSprinting()) return amount;
        if (a.fallDistance > 0.0f && !a.onGround()) return amount;
        GhostFx fx = TimeTravel.fxOf(a);
        if (fx == null || !fx.falling()) return amount;

        if (victim.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY(0.5), victim.getZ(),
                    10, 0.3, 0.3, 0.3, 0.2);
            sl.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.0f);
        }
        return amount * 1.5f;
    }

    @Unique
    private static boolean tt$cloneBlocks(LivingEntity self, DamageSource source) {
        if (!(self instanceof ServerPlayer) || !Ghosts.is(self)) return false;
        if (!self.isUsingItem()) return false;
        ItemStack use = self.getUseItem();
        boolean shield = use.is(Items.SHIELD)
                || self.getOffhandItem().is(Items.SHIELD)
                || self.getMainHandItem().is(Items.SHIELD);
        if (!shield) return false;
        if (source.is(DamageTypeTags.BYPASSES_SHIELD)) return false;

        Vec3 from = source.getSourcePosition();
        if (from == null) return false;
        Vec3 toSrc = self.position().vectorTo(from);
        toSrc = new Vec3(toSrc.x, 0.0, toSrc.z).normalize();
        Vec3 view = self.getViewVector(1.0f);
        view = new Vec3(view.x, 0.0, view.z).normalize();
        if (toSrc.dot(view) <= 0.0) return false;

        self.level().broadcastEntityEvent(self, (byte) 29);
        self.level().playSound(null, self.getX(), self.getY(), self.getZ(),
                SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0f, 0.8f + self.level().random.nextFloat() * 0.4f);
        if (source.getDirectEntity() instanceof AbstractArrow arrow) arrow.discard();
        return true;
    }
}
