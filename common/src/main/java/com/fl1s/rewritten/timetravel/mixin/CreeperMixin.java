package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.mob.MobJournal;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Creeper.class)
public abstract class CreeperMixin {
    @Shadow private int swell;

    @Inject(method = "tick", at = @At("HEAD"))
    private void tt$preTickCreeper(CallbackInfo ci) {
        Creeper creeper = (Creeper) (Object) this;
        if (MobJournal.isApplying()) return;
        if (this.swell < 29) return;
        if (creeper.isAlive() && creeper.getSwellDir() > 0) {
            MobJournal.onEntityDeath(creeper, true);
        }
    }
}
