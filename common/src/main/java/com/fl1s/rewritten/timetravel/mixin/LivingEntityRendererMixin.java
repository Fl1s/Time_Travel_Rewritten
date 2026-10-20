package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import com.fl1s.rewritten.timetravel.client.VanishBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"))
    private void tt$vanishBegin(LivingEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
        CloneVanishFx.begin(entity, partialTick);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("RETURN"))
    private void tt$vanishEnd(LivingEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
        CloneVanishFx.end();
    }

    @ModifyVariable(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private MultiBufferSource tt$wrapEntityBuffer(MultiBufferSource originalBuffer) {
        return CloneVanishFx.active() ? new VanishBufferSource(originalBuffer) : originalBuffer;
    }
}
