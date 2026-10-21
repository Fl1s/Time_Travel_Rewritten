package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.BossFightJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.DragonRespawnAnimation;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(EndDragonFight.class)
public abstract class EndDragonFightMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = {"setDragonKilled", "scanState", "setRespawnStage", "respawnDragon", "findOrCreateDragon"}, at = @At("HEAD"))
    private void tt$before(CallbackInfo ci) {
        BossFightJournal.beforeChange(level, (EndDragonFight) (Object) this);
    }

    @Inject(method = {"setDragonKilled", "scanState", "setRespawnStage", "respawnDragon", "findOrCreateDragon"}, at = @At("RETURN"))
    private void tt$after(CallbackInfo ci) {
        BossFightJournal.onChanged(level, (EndDragonFight) (Object) this);
    }

    @Inject(method = "createNewDragon", at = @At("HEAD"), cancellable = true)
    private void tt$beforeCreate(CallbackInfoReturnable<EnderDragon> cir) {
        if (BossFightJournal.isProtected(level)) {
            cir.setReturnValue(null);
            return;
        }
        BossFightJournal.beforeChange(level, (EndDragonFight) (Object) this);
    }

    @Inject(method = "createNewDragon", at = @At("RETURN"))
    private void tt$afterCreate(CallbackInfoReturnable<EnderDragon> cir) {
        BossFightJournal.onChanged(level, (EndDragonFight) (Object) this);
    }

    @Inject(method = "setDragonKilled", at = @At("HEAD"), cancellable = true)
    private void tt$drivenKill(EnderDragon dragon, CallbackInfo ci) {
        if (BossFightJournal.isDrivenDeath(dragon)) ci.cancel();
    }

    @Inject(method = "respawnDragon", at = @At("HEAD"), cancellable = true)
    private void tt$noRespawnInReplay(List<EndCrystal> crystals, CallbackInfo ci) {
        if (BossFightJournal.isProtected(level)) ci.cancel();
    }

    @Inject(method = "setRespawnStage", at = @At("HEAD"), cancellable = true)
    private void tt$respawnEnd(DragonRespawnAnimation stage, CallbackInfo ci) {
        if (stage == DragonRespawnAnimation.END && BossFightJournal.isProtected(level)) {
            BossFightJournal.finishProtectedRespawn((EndDragonFight) (Object) this);
            ci.cancel();
        }
    }

    @Inject(method = "resetSpikeCrystals", at = @At("HEAD"), cancellable = true)
    private void tt$noSpikeReset(CallbackInfo ci) {
        if (BossFightJournal.isProtected(level)) ci.cancel();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/dimension/end/DragonRespawnAnimation;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/dimension/end/EndDragonFight;Ljava/util/List;ILnet/minecraft/core/BlockPos;)V"))
    private void tt$stageTick(DragonRespawnAnimation stage, ServerLevel lvl, EndDragonFight fight, List<EndCrystal> crystals, int time, BlockPos portal) {
        if (BossFightJournal.isProtected(lvl)) {
            BossFightJournal.cosmeticRespawnTick(stage, lvl, crystals, time);
        } else {
            stage.tick(lvl, fight, crystals, time, portal);
        }
    }
}
