package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.MobStateJournal;
import net.minecraft.world.entity.animal.Sheep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Sheep.class)
public abstract class SheepMixin {
    @Inject(method = "setSheared", at = @At("RETURN"))
    private void tt$afterSetSheared(boolean sheared, CallbackInfo ci) {
        MobStateJournal.onChanged((Sheep) (Object) this);
    }
}
