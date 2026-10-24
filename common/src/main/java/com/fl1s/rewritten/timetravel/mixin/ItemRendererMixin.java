package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.ExemptGlow;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    @Unique
    private static boolean tt$glowing;

    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At("HEAD"), cancellable = true)
    private void tt$exemptGlow(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack,
                               MultiBufferSource buffers, int light, int overlay, BakedModel model, CallbackInfo ci) {
        if (tt$glowing || !ExemptGlow.glows(stack)) return;
        tt$glowing = true;
        try {
            ((ItemRenderer) (Object) this).render(stack, context, leftHand, poseStack, ExemptGlow.wrap(buffers), light, overlay, model);
        } finally {
            tt$glowing = false;
        }
        ci.cancel();
    }
}
