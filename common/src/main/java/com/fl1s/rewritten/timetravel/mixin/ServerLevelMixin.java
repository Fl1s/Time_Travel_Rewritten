package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), cancellable = true)
    private void tt$sound(Player except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        if (BlockJournal.isApplying()) { ci.cancel(); return; }
        EffectJournal.recordSound((ServerLevel) (Object) this, except, x, y, z, sound, source, volume, pitch);
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), cancellable = true)
    private void tt$entitySound(Player except, Entity entity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        if (BlockJournal.isApplying()) { ci.cancel(); return; }
        EffectJournal.recordSound((ServerLevel) (Object) this, except, entity.getX(), entity.getY(), entity.getZ(), sound, source, volume, pitch);
    }

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), cancellable = true)
    private void tt$levelEvent(Player except, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (BlockJournal.isApplying()) { ci.cancel(); return; }
        EffectJournal.recordLevelEvent((ServerLevel) (Object) this, except, type, pos, data);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tt$captureTickLevel(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        RedstoneJournal.setCurrentLevel((ServerLevel) (Object) this);
    }

    @Inject(method = "blockEvent(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V", at = @At("HEAD"), cancellable = true)
    private void tt$blockEvent(BlockPos pos, Block block, int a, int b, CallbackInfo ci) {
        if (BlockJournal.isApplying()) { ci.cancel(); return; }
        EffectJournal.recordBlockEvent((ServerLevel) (Object) this, pos, block, a, b);
    }

    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void tt$addEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (EntityJournal.shouldBlock(entity)) {
            cir.setReturnValue(false);
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        EntityJournal.onSpawn(self, entity);
        MobJournal.onEntitySpawn(self, entity);
        ItemFlowJournal.onEntitySpawn(self, entity);
        VehicleJournal.onEntitySpawn(self, entity);
        ArmorStandJournal.onSpawn(self, entity);
    }

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
    private void tt$suppressFastSimUpdates(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (ProcessJournal.isFastSimulating()) { ci.cancel(); return; }
        ServerLevel self = (ServerLevel) (Object) this;
        if (BlockJournal.isApplying() || BlockEntityJournal.isApplying()) return;
        if (!com.fl1s.rewritten.timetravel.TimeTravel.isLooping) return;
        BlockEntity be = self.getBlockEntity(pos);
        if (be != null) BlockEntityJournal.onChanged(be);
    }
}
