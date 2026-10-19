package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.BossFightJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndDragonFight.class)
public abstract class EndDragonFightMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = {"setDragonKilled", "scanState", "setRespawnStage"}, at = @At("HEAD"))
    private void tt$before(CallbackInfo ci) {
        BossFightJournal.beforeChange(level, (EndDragonFight) (Object) this);
    }

    @Inject(method = {"setDragonKilled", "scanState", "setRespawnStage"}, at = @At("RETURN"))
    private void tt$after(CallbackInfo ci) {
        BossFightJournal.onChanged(level, (EndDragonFight) (Object) this);
    }

    @Inject(method = "createNewDragon", at = @At("HEAD"))
    private void tt$beforeCreate(CallbackInfoReturnable<EnderDragon> cir) {
        BossFightJournal.beforeChange(level, (EndDragonFight) (Object) this);
    }

    @Inject(method = "createNewDragon", at = @At("RETURN"))
    private void tt$afterCreate(CallbackInfoReturnable<EnderDragon> cir) {
        BossFightJournal.onChanged(level, (EndDragonFight) (Object) this);
    }
}
