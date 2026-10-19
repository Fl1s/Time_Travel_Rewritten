package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.MobJournal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragon.class)
public abstract class EnderDragonMixin {
    @Inject(method = "tickDeath", at = @At("HEAD"))
    private void tt$finish(CallbackInfo ci) {
        EnderDragon self = (EnderDragon) (Object) this;
        if (self.dragonDeathTime == 199) MobJournal.onDragonKilled(self);
    }
}
