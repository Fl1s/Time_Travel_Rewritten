package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.ExemptGlow;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Unique
    private static boolean tt$glowing;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void tt$exemptGlow(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
                               MultiBufferSource buffers, CallbackInfo ci) {
        if (tt$glowing || !ExemptGlow.glows(blockEntity)) return;
        tt$glowing = true;
        try {
            ((BlockEntityRenderDispatcher) (Object) this).render(blockEntity, partialTick, poseStack, ExemptGlow.wrap(buffers));
        } finally {
            tt$glowing = false;
        }
        ci.cancel();
    }
}
