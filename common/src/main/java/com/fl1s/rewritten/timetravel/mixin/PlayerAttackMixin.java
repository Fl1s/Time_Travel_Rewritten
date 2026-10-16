package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.GhostTracks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void tt$recordAttack(Entity target, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) GhostTracks.onAttack(player, target);
    }
}
