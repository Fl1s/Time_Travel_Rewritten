package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.clone.CloneTracks;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.entity.FishingHookExt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingHook.class)
public abstract class FishingHookMixin implements FishingHookExt {
    @Shadow
    private int nibble;

    @Shadow
    @Final
    private static EntityDataAccessor<Boolean> DATA_BITING;

    @Unique
    private int tt$prevNibble;

    @Unique
    private FishingHook tt$self() {
        return (FishingHook) (Object) this;
    }

    @Unique
    private boolean tt$cloneOwned() {
        Player owner = tt$self().getPlayerOwner();
        return owner != null && Clones.is(owner);
    }

    @Override
    public void tt$bite() {
        FishingHook self = tt$self();
        nibble = 30;
        self.getEntityData().set(DATA_BITING, true);
        if (self.level() instanceof ServerLevel level) {
            double w = self.getBbWidth();
            int count = (int) (1.0F + w * 20.0F);
            double y = self.getBoundingBox().minY + 0.5;
            level.sendParticles(ParticleTypes.BUBBLE, self.getX(), y, self.getZ(), count, w, 0.0, w, 0.2);
            level.sendParticles(ParticleTypes.FISHING, self.getX(), y, self.getZ(), count, w, 0.0, w, 0.2);
        }
    }

    @Inject(method = "catchingFish", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$fishHead(BlockPos pos, CallbackInfo ci) {
        if (tt$cloneOwned()) {
            if (nibble <= 0) ci.cancel();
            return;
        }
        tt$prevNibble = nibble;
    }

    @Inject(method = "catchingFish", at = @At("RETURN"), require = 0)
    private void tt$fishTail(BlockPos pos, CallbackInfo ci) {
        if (tt$prevNibble > 0 || nibble <= 0) return;
        if (tt$self().getPlayerOwner() instanceof ServerPlayer sp) CloneTracks.onBite(sp);
    }

    @Inject(method = "shouldStopFishing", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$keepLine(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!Clones.is(player)) return;
        cir.setReturnValue(player.isRemoved() || !player.isAlive() || tt$self().distanceToSqr(player) > 1024.0);
    }

    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$noCloneHooking(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (tt$cloneOwned() && Clones.is(entity)) cir.setReturnValue(false);
    }

    @Inject(method = "retrieve", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$retrieveHead(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        FishingHook self = tt$self();
        if (tt$cloneOwned()) {
            Entity hooked = self.getHookedIn();
            Entity owner = self.getOwner();
            if (hooked != null && !hooked.isRemoved() && owner != null) {
                Vec3 pull = new Vec3(owner.getX() - self.getX(), owner.getY() - self.getY(), owner.getZ() - self.getZ()).scale(0.1);
                hooked.setDeltaMovement(hooked.getDeltaMovement().add(pull));
                hooked.hurtMarked = true;
                self.level().broadcastEntityEvent(self, (byte) 31);
            }
            self.discard();
            cir.setReturnValue(0);
            return;
        }
        CloneTracks.beginReel(self.getUUID());
    }

    @Inject(method = "retrieve", at = @At("RETURN"), require = 0)
    private void tt$retrieveTail(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        FishingHook self = tt$self();
        if (self.getPlayerOwner() instanceof ServerPlayer sp) {
            CloneTracks.onHookReeled(self.getUUID(), sp, cir.getReturnValue());
        }
    }
}
