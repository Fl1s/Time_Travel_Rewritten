package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.MobJournal;
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
        if (MobJournal.isDoomed(creeper) && this.swell >= 29) {
            this.swell = 0;
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void tt$postTickCreeper(CallbackInfo ci) {
        Creeper creeper = (Creeper) (Object) this;
        if (creeper.isRemoved() && this.swell >= 29 && !MobJournal.isDoomed(creeper)) {
            MobJournal.onEntityDeath(creeper, true);
        }
    }
}