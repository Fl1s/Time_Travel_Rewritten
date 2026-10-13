package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.util.Ghosts;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {

    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void tt$blockGhostEdit(Player player, boolean isFrontText, SignText text, CallbackInfo ci) {
        if (Ghosts.is(player)) ci.cancel();
    }
}