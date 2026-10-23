package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.clone.CloneTracks;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.entity.ClonePearlExt;
import com.fl1s.rewritten.timetravel.entity.EntityJournal;
import com.fl1s.rewritten.timetravel.entity.EntityStateJournal;
import com.fl1s.rewritten.timetravel.journal.*;
import com.fl1s.rewritten.timetravel.mob.MobJournal;
import com.fl1s.rewritten.timetravel.vehicle.VehicleJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), cancellable = true)
    private void tt$sound(Player except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        if (BlockJournal.isApplying()) {
            ci.cancel();
            return;
        }
        EffectJournal.recordSound((ServerLevel) (Object) this, except, x, y, z, sound, source, volume, pitch);
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), cancellable = true)
    private void tt$entitySound(Player except, Entity entity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        if (BlockJournal.isApplying()) {
            ci.cancel();
            return;
        }
        EffectJournal.recordSound((ServerLevel) (Object) this, except, entity.getX(), entity.getY(), entity.getZ(), sound, source, volume, pitch);
    }

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), cancellable = true)
    private void tt$levelEvent(Player except, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (BlockJournal.isApplying()) {
            ci.cancel();
            return;
        }
        EffectJournal.recordLevelEvent((ServerLevel) (Object) this, except, type, pos, data);
    }

    @Inject(method = "broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V", at = @At("HEAD"))
    private void tt$entityEvent(Entity entity, byte event, CallbackInfo ci) {
        EffectJournal.recordEntityEvent((ServerLevel) (Object) this, entity, event);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tt$captureTickLevel(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        RedstoneJournal.setCurrentLevel((ServerLevel) (Object) this);
    }

    @Inject(method = "blockEvent(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V", at = @At("HEAD"), cancellable = true)
    private void tt$blockEvent(BlockPos pos, Block block, int a, int b, CallbackInfo ci) {
        if (BlockJournal.isApplying()) {
            ci.cancel();
            return;
        }
        EffectJournal.recordBlockEvent((ServerLevel) (Object) this, pos, block, a, b);
    }

    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void tt$addEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        boolean blocked = false;
        try {
            blocked = EntityJournal.shouldBlock(entity);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Entity block check failed", t);
        }
        if (blocked) {
            cir.setReturnValue(false);
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        if (entity instanceof ThrownEnderpearl pearl && pearl.getOwner() instanceof ServerPlayer owner) {
            if (Clones.is(owner)) ((ClonePearlExt) pearl).tt$markClone();
            else tt$guard("pearls", () -> CloneTracks.onPearlThrown(owner));
        }
        if (entity instanceof FishingHook hook) tt$guard("fishing", () -> CloneTracks.onHookSpawned(hook));
        tt$guard("entities", () -> EntityJournal.onSpawn(self, entity));
        tt$guard("projectiles", () -> ProjectileJournal.onSpawn(self, entity));
        tt$guard("tracked_entities", () -> TrackedEntityJournal.onSpawn(self, entity));
        tt$guard("mobs", () -> MobJournal.onEntitySpawn(self, entity));
        tt$guard("item_flow", () -> ItemFlowJournal.onEntitySpawn(self, entity));
        tt$guard("vehicles", () -> VehicleJournal.onEntitySpawn(self, entity));
        tt$guard("entity_states", () -> EntityStateJournal.onSpawn(self, entity));
    }

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void tt$puppetTickHead(Entity entity, CallbackInfo ci) {
        if (!TimeTravel.isLooping) return;
        if (PuppetGuard.isInert(entity)) {
            entity.setOldPosAndRot();
            ci.cancel();
            return;
        }
        PuppetGuard.beginTick(entity);
    }

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
    private void tt$puppetTickReturn(Entity entity, CallbackInfo ci) {
        PuppetGuard.endTick();
    }

    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
            at = @At("HEAD"))
    private void tt$captureParticles(ParticleOptions type,
                                     double x, double y, double z, int count,
                                     double dx, double dy, double dz, double speed,
                                     CallbackInfoReturnable<Integer> cir) {
        ParticleJournal.onSpawn((ServerLevel) (Object) this, type, x, y, z, count, dx, dy, dz, speed, false);
    }

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
    private void tt$suppressFastSimUpdates(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (ProcessJournal.isFastSimulating()) {
            ci.cancel();
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        if (BlockJournal.isApplying() || BlockEntityJournal.isApplying()) return;
        if (!TimeTravel.isLooping) return;
        try {
            BlockEntity be = self.getBlockEntity(pos);
            if (be != null) BlockEntityJournal.onChanged(be);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Block entity change hook failed at {}", pos, t);
        }
    }

    @Unique
    private static void tt$guard(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.error("Journal '{}' failed on entity spawn", name, t);
        }
    }
}