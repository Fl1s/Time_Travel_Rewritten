package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.timeline.AdvancementTimeline;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Shadow private ServerPlayer player;
    @Shadow public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder holder);

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void tt$blockClone(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (AdvancementTimeline.isClone(player)) cir.setReturnValue(false);
    }

    @Inject(method = "award", at = @At("RETURN"))
    private void tt$record(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && getOrStartProgress(holder).isDone()) {
            AdvancementTimeline.onAwarded(player, holder);
        }
    }
}