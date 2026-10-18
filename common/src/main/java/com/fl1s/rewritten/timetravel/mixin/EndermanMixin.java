package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.MobStateJournal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderMan.class)
public abstract class EndermanMixin {
    @Inject(method = "setCarriedBlock", at = @At("RETURN"))
    private void tt$afterSetCarriedBlock(BlockState state, CallbackInfo ci) {
        MobStateJournal.onChanged((EnderMan) (Object) this);
    }
}