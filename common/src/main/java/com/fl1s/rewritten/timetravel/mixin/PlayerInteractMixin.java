package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.clone.CloneTracks;
import com.fl1s.rewritten.timetravel.util.DoomedFeedback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerInteractMixin {
    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$reserved(Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!TimeTravel.isLooping || !(target instanceof LivingEntity)) return;
        if (!((Object) this instanceof ServerPlayer sp) || !TimeTravel.isRealPlayer(sp)) return;
        if (!CloneTracks.isReservedByClone(target.getUUID(), TimeTravel.timelineTick)) return;
        if (target.level() instanceof ServerLevel sl) DoomedFeedback.tryShowSaddle(sp, sl);
        cir.setReturnValue(InteractionResult.FAIL);
    }
}
